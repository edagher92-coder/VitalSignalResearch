package au.com.elied.vitalsignal.reasoning

import java.net.URI
import java.util.Locale

enum class OllamaHttpMethod { GET, POST }

/**
 * Transport-neutral request. Implementations must enforce both timeouts, stop
 * reading at [maxResponseBodyBytes], and must not follow redirects. HTTPS
 * implementations must retain normal certificate-chain and hostname checks;
 * permissive trust managers or hostname verifiers violate this contract.
 */
class OllamaHttpRequest(
    val method: OllamaHttpMethod,
    val uri: URI,
    headers: Map<String, String>,
    bodyBytes: ByteArray,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maxResponseBodyBytes: Int,
    val followRedirects: Boolean = false,
) {
    val headers: Map<String, String> = java.util.Map.copyOf(headers)
    private val body = bodyBytes.copyOf()

    init {
        require(uri.isAbsolute)
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(maxResponseBodyBytes > 0)
        require(!followRedirects) { "Ollama redirects are prohibited" }
    }

    val bodySizeBytes: Int get() = body.size

    fun bodyBytes(): ByteArray = body.copyOf()
}

/**
 * The effective URI is mandatory so an adapter can detect a transport that
 * followed a redirect despite the request contract.
 */
class OllamaHttpResponse(
    val statusCode: Int,
    headers: Map<String, List<String>>,
    bodyBytes: ByteArray,
    val effectiveUri: URI,
) {
    val headers: Map<String, List<String>> = java.util.Map.copyOf(
        headers.mapValues { (_, values) -> java.util.List.copyOf(values) },
    )
    private val body = bodyBytes.copyOf()

    init {
        require(statusCode in 100..599)
        require(effectiveUri.isAbsolute)
    }

    val bodySizeBytes: Int get() = body.size

    fun bodyBytes(): ByteArray = body.copyOf()
}

fun interface OllamaHttpTransport {
    fun execute(request: OllamaHttpRequest): OllamaHttpResponse
}

/** Supplies short-lived gateway headers without placing secrets in config DTOs. */
fun interface OllamaHttpHeadersProvider {
    fun headersFor(method: OllamaHttpMethod, uri: URI): Map<String, String>
}

data class TrustedPrivateHttpsGateway(val baseUri: URI) {
    internal val endpoint: ValidatedOllamaEndpoint = validateBaseEndpoint(baseUri)

    init {
        require(endpoint.scheme == "https") { "A private gateway must use HTTPS" }
        require(!endpoint.isPublicIpLiteral) { "Public IP literals cannot be trusted gateways" }
        require(!endpoint.isLoopback) { "Use the loopback development policy for loopback" }
    }
}

/**
 * Default-deny SSRF boundary. Cleartext is allowed only for literal loopback
 * development endpoints. A remote endpoint must exactly match an explicitly
 * configured HTTPS gateway (host, effective port, and base path).
 *
 * DNS is deliberately not resolved here. Configuring a DNS gateway name is an
 * operator assertion that TLS and private routing for that exact name are
 * controlled by the deployment. Public IP literals remain denied even if
 * supplied in the allow-list.
 */
class OllamaEndpointPolicy(
    trustedPrivateHttpsGateways: Set<TrustedPrivateHttpsGateway> = emptySet(),
) {
    private val trusted = trustedPrivateHttpsGateways.mapTo(linkedSetOf()) { it.endpoint.key }

    fun requireAllowed(baseUri: URI): ValidatedOllamaEndpoint {
        val endpoint = try {
            validateBaseEndpoint(baseUri)
        } catch (_: IllegalArgumentException) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.ENDPOINT_REJECTED,
                "Ollama endpoint is not a canonical base URI",
            )
        }
        if (endpoint.isLoopback) {
            if (endpoint.scheme != "http" && endpoint.scheme != "https") {
                throw rejected()
            }
            return endpoint
        }
        if (endpoint.scheme != "https" || endpoint.key !in trusted || endpoint.isPublicIpLiteral) {
            throw rejected()
        }
        return endpoint
    }

    private fun rejected() = OllamaAdapterException(
        OllamaAdapterFailureCode.ENDPOINT_REJECTED,
        "Ollama endpoint is neither loopback nor an allowed private HTTPS gateway",
    )
}

internal data class OllamaEndpointKey(
    val scheme: String,
    val host: String,
    val effectivePort: Int,
    val basePath: String,
)

class ValidatedOllamaEndpoint internal constructor(
    val scheme: String,
    val host: String,
    val declaredPort: Int,
    val basePath: String,
    val isLoopback: Boolean,
    val isPublicIpLiteral: Boolean,
) {
    internal val key = OllamaEndpointKey(
        scheme = scheme,
        host = host,
        effectivePort = if (declaredPort >= 0) declaredPort else if (scheme == "https") 443 else 80,
        basePath = basePath,
    )

    fun apiUri(path: String): URI {
        require(path.startsWith('/') && !path.startsWith("//"))
        require(".." !in path && '%' !in path && '\\' !in path)
        val fullPath = basePath + path
        return URI(scheme, null, host, declaredPort, fullPath, null, null)
    }
}

private fun validateBaseEndpoint(uri: URI): ValidatedOllamaEndpoint {
    require(uri.isAbsolute)
    require(uri.userInfo == null)
    require(uri.rawQuery == null)
    require(uri.rawFragment == null)
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Missing scheme")
    require(scheme == "http" || scheme == "https")
    val host = uri.host
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.removeSuffix(".")
        ?.lowercase(Locale.ROOT)
        ?: throw IllegalArgumentException("Missing host")
    require(host.isNotBlank())
    require(host.none { it.isWhitespace() || it == '/' || it == '\\' || it == '@' || it == '%' })
    require(uri.port == -1 || uri.port in 1..65_535)

    val rawPath = uri.rawPath.orEmpty()
    require('%' !in rawPath && '\\' !in rawPath)
    require("//" !in rawPath)
    val pathSegments = rawPath.split('/').filter { it.isNotEmpty() }
    require(pathSegments.none { it == "." || it == ".." })
    val basePath = when (rawPath) {
        "", "/" -> ""
        else -> rawPath.trimEnd('/')
    }

    val ipv4 = strictIpv4(host)
    if (ipv4 == null && host.all { it.isDigit() || it == '.' }) {
        throw IllegalArgumentException("Ambiguous numeric host")
    }
    val loopback = host == "localhost" || host == "::1" || (ipv4 != null && ipv4[0] == 127)
    val publicLiteral = when {
        ipv4 != null -> !loopback && !isPrivateIpv4(ipv4)
        ':' in host -> !loopback && !isUniqueLocalIpv6(host)
        else -> false
    }
    return ValidatedOllamaEndpoint(
        scheme = scheme,
        host = host,
        declaredPort = uri.port,
        basePath = basePath,
        isLoopback = loopback,
        isPublicIpLiteral = publicLiteral,
    )
}

private fun strictIpv4(host: String): IntArray? {
    val pieces = host.split('.')
    if (pieces.size != 4) return null
    val parsed = IntArray(4)
    pieces.forEachIndexed { index, piece ->
        if (piece.isEmpty() || piece.length > 3 || piece.any { !it.isDigit() }) return null
        if (piece.length > 1 && piece.startsWith('0')) return null
        val value = piece.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        parsed[index] = value
    }
    return parsed
}

private fun isPrivateIpv4(address: IntArray): Boolean =
    address[0] == 10 ||
        (address[0] == 172 && address[1] in 16..31) ||
        (address[0] == 192 && address[1] == 168)

private fun isUniqueLocalIpv6(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT)
    return normalized.startsWith("fc") || normalized.startsWith("fd")
}
