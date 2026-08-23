package au.com.elied.vitalsignal.reasoning

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class OllamaAdapterFailureCode {
    ENDPOINT_REJECTED,
    REQUEST_ENCODING_FAILED,
    REQUEST_TOO_LARGE,
    INVALID_REQUEST_HEADERS,
    TRANSPORT_FAILED,
    REDIRECT_REJECTED,
    RESPONSE_TOO_LARGE,
    UNEXPECTED_HTTP_STATUS,
    UNEXPECTED_CONTENT_TYPE,
    MALFORMED_RESPONSE,
    SERVER_VERSION_MISMATCH,
    MODEL_NOT_FOUND,
    MODEL_NAME_MISMATCH,
    MODEL_DIGEST_MISMATCH,
    MODEL_QUANTIZATION_MISMATCH,
    INCOMPLETE_GENERATION,
    STRUCTURED_RESPONSE_INVALID,
    STRUCTURED_RESPONSE_TOO_LARGE,
    CLOCK_INVALID,
}

class OllamaAdapterException(
    val failureCode: OllamaAdapterFailureCode,
    message: String,
) : IllegalStateException(message)

data class PinnedOllamaModel(
    val name: String,
    /** Lowercase manifest digest as returned by GET /api/tags, without a prefix. */
    val digestSha256: String,
    val quantization: String,
) {
    init {
        require(name.isNotBlank() && name.length <= 200)
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*(?::[A-Za-z0-9][A-Za-z0-9._-]*)?")))
        require(digestSha256.matches(Regex("[a-f0-9]{64}")))
        require(quantization.isNotBlank() && quantization.length <= 100)
    }
}

data class PinnedUtf8Document(
    val text: String,
    val sha256: String,
) {
    init {
        require(text.isNotBlank())
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val actual = OllamaProtocolDigests.sha256Hex(OllamaProtocolDigests.strictUtf8(text))
        require(OllamaProtocolDigests.constantTimeEquals(sha256, actual)) {
            "Pinned document hash does not match its UTF-8 bytes"
        }
    }
}

data class OllamaHttpLimits(
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 120_000,
    val maxRequestBodyBytes: Int = 256 * 1024,
    val maxResponseBodyBytes: Int = 512 * 1024,
) {
    init {
        require(connectTimeoutMillis in 1..30_000)
        require(readTimeoutMillis in 1..600_000)
        require(maxRequestBodyBytes in 1_024..(4 * 1024 * 1024))
        require(maxResponseBodyBytes in 1_024..(4 * 1024 * 1024))
    }
}

data class OllamaCandidateLimits(
    val maxClaims: Int = 32,
    val maxReferencesPerClaim: Int = 64,
    val maxRecommendations: Int = 64,
    val maxIdentifierUtf8Bytes: Int = 256,
) {
    init {
        require(maxClaims in 1..256)
        require(maxReferencesPerClaim in 1..512)
        require(maxRecommendations in 1..512)
        require(maxIdentifierUtf8Bytes in 16..1_024)
    }
}

data class OllamaServerConfig(
    val baseUri: URI,
    val expectedOllamaVersion: String,
    val model: PinnedOllamaModel,
    val seed: Long,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val systemPrompt: PinnedUtf8Document = OllamaReasoningProtocol.systemPrompt(),
    val jsonSchema: PinnedUtf8Document = OllamaReasoningProtocol.jsonSchema(),
    val httpLimits: OllamaHttpLimits = OllamaHttpLimits(),
    val candidateLimits: OllamaCandidateLimits = OllamaCandidateLimits(),
) {
    init {
        require(expectedOllamaVersion.isNotBlank() && expectedOllamaVersion.length <= 100)
        require(expectedOllamaVersion.none { it.isWhitespace() || it.code < 0x20 })
        require(seed in 0L..Int.MAX_VALUE.toLong()) { "Seed must be a fixed non-negative Ollama integer" }
        require(contextTokens in 1..65_536)
        require(maxOutputTokens in 1..contextTokens)
        val schema = jsonSchema.text.trim()
        require(schema.startsWith('{') && schema.endsWith('}'))
    }
}

data class OllamaGenerationOptionsDto(
    val seed: Long,
    val temperature: Double = 0.0,
    val contextTokens: Int,
    val maxOutputTokens: Int,
) {
    init {
        require(seed in 0L..Int.MAX_VALUE.toLong()) { "Seed must be a fixed non-negative Ollama integer" }
        require(temperature == 0.0) { "Health explanation runs must use temperature zero" }
        require(contextTokens in 1..65_536)
        require(maxOutputTokens in 1..contextTokens)
    }
}

/** No tools, images, history, database handles, or model-authored actions exist in this DTO. */
data class OllamaGenerateRequestDto(
    val model: String,
    val system: String,
    val prompt: String,
    val formatJsonSchema: String,
    val options: OllamaGenerationOptionsDto,
    val stream: Boolean = false,
    val think: Boolean = false,
    val raw: Boolean = false,
    val keepAliveSeconds: Int = 0,
) {
    init {
        require(model.isNotBlank())
        require(system.isNotBlank())
        require(prompt.isNotBlank())
        require(!stream) { "Streaming is disabled so one bounded response can be verified" }
        require(!think) { "Unreturned thinking is disabled" }
        require(!raw)
        require(keepAliveSeconds == 0)
    }
}

data class OllamaVersionResponseDto(val version: String) {
    init {
        require(version.isNotBlank() && version.length <= 100)
    }
}

data class OllamaModelDescriptorDto(
    val name: String,
    val digestSha256: String,
    val quantization: String,
) {
    init {
        require(name.isNotBlank() && name.length <= 200)
        require(digestSha256.matches(Regex("[a-f0-9]{64}")))
        require(quantization.isNotBlank() && quantization.length <= 100)
    }
}

class OllamaTagsResponseDto(models: List<OllamaModelDescriptorDto>) {
    val models: List<OllamaModelDescriptorDto> = java.util.List.copyOf(models)
}

data class OllamaGenerateResponseDto(
    val model: String,
    /** JSON text produced under the request schema. */
    val structuredResponse: String,
    val done: Boolean,
    val doneReason: String?,
    val totalDurationNanos: Long,
    val loadDurationNanos: Long,
    val promptEvalCount: Int,
    val evalCount: Int,
) {
    init {
        require(model.isNotBlank() && model.length <= 200)
        require(totalDurationNanos >= 0L)
        require(loadDurationNanos >= 0L)
        require(promptEvalCount >= 0)
        require(evalCount >= 0)
    }
}

/**
 * Inject a JSON implementation at the application boundary. This module has no
 * JSON dependency, and tests can provide a fully offline decoder.
 */
interface OllamaWireResponseDecoder {
    fun decodeVersion(jsonUtf8: String): OllamaVersionResponseDto

    fun decodeTags(jsonUtf8: String): OllamaTagsResponseDto

    fun decodeGenerate(jsonUtf8: String): OllamaGenerateResponseDto
}

/** Converts only the schema-constrained model response into the existing domain DTO. */
fun interface OllamaStructuredResponseParser {
    fun parse(structuredJsonUtf8: String): LocalReasoningCandidate
}

/**
 * Safe Ollama server adapter. It implements the existing gateway only; the
 * deterministic policy and audit-before-delivery orchestrator remain mandatory
 * for anything user-visible.
 */
class OllamaServerAdapter(
    config: OllamaServerConfig,
    endpointPolicy: OllamaEndpointPolicy,
    private val transport: OllamaHttpTransport,
    private val wireDecoder: OllamaWireResponseDecoder,
    private val structuredResponseParser: OllamaStructuredResponseParser,
    private val nowEpochMillis: () -> Long,
    private val headersProvider: OllamaHttpHeadersProvider = OllamaHttpHeadersProvider { _, _ -> emptyMap() },
) : LocalReasoningGateway {
    private val config = config
    private val endpoint = endpointPolicy.requireAllowed(config.baseUri)

    override fun generate(request: LocalReasoningRequest): Pair<LocalReasoningCandidate, OllamaRunReceipt> {
        val prompt = CanonicalLocalReasoningPrompt.render(request)
        val promptSha256 = OllamaProtocolDigests.promptSha256(config.systemPrompt.text, prompt)
        val generateDto = OllamaGenerateRequestDto(
            model = config.model.name,
            system = config.systemPrompt.text,
            prompt = prompt,
            formatJsonSchema = config.jsonSchema.text,
            options = OllamaGenerationOptionsDto(
                seed = config.seed,
                contextTokens = config.contextTokens,
                maxOutputTokens = config.maxOutputTokens,
            ),
        )
        val generateBody = encodeRequest(generateDto)

        val version = decodeSafely {
            wireDecoder.decodeVersion(call(OllamaHttpMethod.GET, "/api/version", EMPTY_BODY))
        }
        if (version.version != config.expectedOllamaVersion) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.SERVER_VERSION_MISMATCH,
                "Ollama server version does not match the pinned version",
            )
        }

        attestModel(decodeTags(call(OllamaHttpMethod.GET, "/api/tags", EMPTY_BODY)))

        val generated = decodeSafely {
            wireDecoder.decodeGenerate(call(OllamaHttpMethod.POST, "/api/generate", generateBody))
        }
        if (generated.model != config.model.name) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MODEL_NAME_MISMATCH,
                "Ollama response model name does not match the pinned name",
            )
        }
        if (!generated.done) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.INCOMPLETE_GENERATION,
                "Ollama did not return a completed non-streaming generation",
            )
        }

        // A second attestation closes the ordinary model-tag replacement race.
        attestModel(decodeTags(call(OllamaHttpMethod.GET, "/api/tags", EMPTY_BODY)))

        val candidate = try {
            structuredResponseParser.parse(generated.structuredResponse)
        } catch (_: Exception) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.STRUCTURED_RESPONSE_INVALID,
                "Structured Ollama output could not be converted to the domain DTO",
            )
        }
        enforceCandidateLimits(candidate)
        val completedAt = nowEpochMillis()
        if (completedAt <= 0L) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.CLOCK_INVALID,
                "Completion clock must be a positive epoch timestamp",
            )
        }
        return candidate to OllamaRunReceipt(
            ollamaVersion = version.version,
            modelName = config.model.name,
            modelDigest = config.model.digestSha256,
            quantization = config.model.quantization,
            promptSha256 = promptSha256,
            jsonSchemaSha256 = config.jsonSchema.sha256,
            seed = config.seed,
            temperature = 0.0,
            contextTokens = config.contextTokens,
            completedAtEpochMillis = completedAt,
        )
    }

    private fun encodeRequest(dto: OllamaGenerateRequestDto): ByteArray {
        val encoded = try {
            OllamaGenerateRequestJson.encode(dto)
        } catch (_: Exception) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.REQUEST_ENCODING_FAILED,
                "Ollama request could not be encoded as strict UTF-8 JSON",
            )
        }
        if (encoded.size > config.httpLimits.maxRequestBodyBytes) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.REQUEST_TOO_LARGE,
                "Ollama request exceeded its configured byte limit",
            )
        }
        return encoded
    }

    private fun decodeTags(json: String): OllamaTagsResponseDto = decodeSafely {
        wireDecoder.decodeTags(json)
    }

    private fun <T> decodeSafely(block: () -> T): T = try {
        block()
    } catch (known: OllamaAdapterException) {
        throw known
    } catch (_: Exception) {
        throw OllamaAdapterException(
            OllamaAdapterFailureCode.MALFORMED_RESPONSE,
            "Ollama returned malformed response JSON",
        )
    }

    private fun attestModel(tags: OllamaTagsResponseDto) {
        val matches = tags.models.filter { it.name == config.model.name }
        if (matches.isEmpty()) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MODEL_NOT_FOUND,
                "Pinned Ollama model name is not installed",
            )
        }
        if (matches.size != 1) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MODEL_NAME_MISMATCH,
                "Pinned Ollama model name is ambiguous",
            )
        }
        val actual = matches.single()
        if (!OllamaProtocolDigests.constantTimeEquals(config.model.digestSha256, actual.digestSha256)) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MODEL_DIGEST_MISMATCH,
                "Installed Ollama model digest does not match the pinned digest",
            )
        }
        if (actual.quantization != config.model.quantization) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MODEL_QUANTIZATION_MISMATCH,
                "Installed Ollama model quantization does not match the pinned value",
            )
        }
    }

    private fun call(method: OllamaHttpMethod, path: String, body: ByteArray): String {
        if (body.size > config.httpLimits.maxRequestBodyBytes) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.REQUEST_TOO_LARGE,
                "Ollama request exceeded its configured byte limit",
            )
        }
        val uri = endpoint.apiUri(path)
        val headers = requestHeaders(method, uri)
        val request = OllamaHttpRequest(
            method = method,
            uri = uri,
            headers = headers,
            bodyBytes = body,
            connectTimeoutMillis = config.httpLimits.connectTimeoutMillis,
            readTimeoutMillis = config.httpLimits.readTimeoutMillis,
            maxResponseBodyBytes = config.httpLimits.maxResponseBodyBytes,
            followRedirects = false,
        )
        val response = try {
            transport.execute(request)
        } catch (_: Exception) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.TRANSPORT_FAILED,
                "Ollama HTTP transport failed",
            )
        }
        if (response.statusCode in 300..399 || response.effectiveUri != uri) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.REDIRECT_REJECTED,
                "Ollama redirects are prohibited",
            )
        }
        if (response.bodySizeBytes > config.httpLimits.maxResponseBodyBytes) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.RESPONSE_TOO_LARGE,
                "Ollama response exceeded its configured byte limit",
            )
        }
        if (response.statusCode != 200) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.UNEXPECTED_HTTP_STATUS,
                "Ollama returned an unexpected HTTP status",
            )
        }
        val contentType = response.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (contentType != "application/json") {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.UNEXPECTED_CONTENT_TYPE,
                "Ollama response must be application/json",
            )
        }
        return try {
            OllamaProtocolDigests.decodeStrictUtf8(response.bodyBytes())
        } catch (_: Exception) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.MALFORMED_RESPONSE,
                "Ollama response was not strict UTF-8",
            )
        }
    }

    private fun requestHeaders(method: OllamaHttpMethod, uri: URI): Map<String, String> {
        val supplied = try {
            headersProvider.headersFor(method, uri)
        } catch (_: Exception) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.INVALID_REQUEST_HEADERS,
                "Ollama gateway headers could not be supplied",
            )
        }
        if (supplied.size > 32) invalidHeaders()
        val result = linkedMapOf("Accept" to "application/json")
        if (method == OllamaHttpMethod.POST) result["Content-Type"] = "application/json"
        var totalBytes = 0
        val suppliedNames = mutableSetOf<String>()
        supplied.forEach { (name, value) ->
            val lower = name.lowercase(Locale.ROOT)
            if (
                !name.matches(HEADER_NAME) ||
                lower in FORBIDDEN_SUPPLIED_HEADERS ||
                !suppliedNames.add(lower) ||
                value.any { it.code < 0x20 || it.code == 0x7f }
            ) {
                invalidHeaders()
            }
            totalBytes += name.length + value.length
            if (totalBytes > MAX_SUPPLIED_HEADER_CHARS) invalidHeaders()
            result[name] = value
        }
        return result
    }

    private fun invalidHeaders(): Nothing = throw OllamaAdapterException(
        OllamaAdapterFailureCode.INVALID_REQUEST_HEADERS,
        "Ollama gateway headers were unsafe",
    )

    private fun enforceCandidateLimits(candidate: LocalReasoningCandidate) {
        val limits = config.candidateLimits
        val tooLarge = candidate.claims.size > limits.maxClaims ||
            candidate.nextMeasurementIds.size > limits.maxRecommendations ||
            candidate.questionIdsForUser.size > limits.maxRecommendations ||
            candidate.claims.any { claim ->
                claim.metricReferenceIds.size > limits.maxReferencesPerClaim ||
                    claim.evidenceReferenceIds.size > limits.maxReferencesPerClaim ||
                    claim.disconfirmingEvidenceReferenceIds.size > limits.maxReferencesPerClaim ||
                    utf8Size(claim.id) > limits.maxIdentifierUtf8Bytes ||
                    utf8Size(claim.templateId) > limits.maxIdentifierUtf8Bytes ||
                    (claim.metricReferenceIds + claim.evidenceReferenceIds + claim.disconfirmingEvidenceReferenceIds)
                        .any { utf8Size(it) > limits.maxIdentifierUtf8Bytes }
            } ||
            (candidate.nextMeasurementIds + candidate.questionIdsForUser)
                .any { utf8Size(it) > limits.maxIdentifierUtf8Bytes }
        if (tooLarge) {
            throw OllamaAdapterException(
                OllamaAdapterFailureCode.STRUCTURED_RESPONSE_TOO_LARGE,
                "Structured Ollama output exceeded domain limits",
            )
        }
    }

    private fun utf8Size(value: String): Int = try {
        OllamaProtocolDigests.strictUtf8(value).size
    } catch (_: Exception) {
        Int.MAX_VALUE
    }

    private companion object {
        val EMPTY_BODY = ByteArray(0)
        val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
        val FORBIDDEN_SUPPLIED_HEADERS = setOf(
            "accept",
            "content-type",
            "content-length",
            "host",
            "connection",
            "transfer-encoding",
            "location",
        )
        const val MAX_SUPPLIED_HEADER_CHARS = 16 * 1024
    }
}

object OllamaReasoningProtocol {
    val SYSTEM_PROMPT: String = """
        You are a constrained research semantic selector. Use only the supplied verified typed packet.
        Return one JSON object matching the supplied schema and no other text.
        Never write prose. There is no claim-text field. Select narrative template IDs only from the approved set.
        Never create numbers, metric IDs, evidence IDs, diagnoses, probabilities, alerts, treatments, or actions.
        Select measurements and questions only from their approved ID sets.
        A hypothesis must cite disconfirming evidence. Abstain when evidence or quality is insufficient.
        You have no tools, network, database, memory, or authority over monitoring and clinical decisions.
    """.trimIndent()

    const val SYSTEM_PROMPT_SHA256: String = "32c6a86af3120b496e2113372dd9424a95994d8ff56c42fde6a41b8884a75fd3"

    val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "required": ["schemaVersion", "inputSnapshotSha256", "claims", "nextMeasurementIds", "questionIdsForUser", "abstain", "abstainReason"],
          "properties": {
            "schemaVersion": {"const": "local-reasoning-v2"},
            "inputSnapshotSha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
            "claims": {
              "type": "array",
              "maxItems": 32,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["id", "kind", "templateId", "metricReferenceIds", "evidenceReferenceIds", "disconfirmingEvidenceReferenceIds", "certainty"],
                "properties": {
                  "id": {"type": "string", "minLength": 1, "maxLength": 256},
                  "kind": {"enum": ["OBSERVATION", "TREND", "ENGINE_FORECAST", "HYPOTHESIS"]},
                  "templateId": {"enum": ["observation.personal-baseline-deviation.v1", "trend.directional-change.v1", "forecast.authoritative-engine-output.v1", "hypothesis.contextual-association.v1"]},
                  "metricReferenceIds": {"type": "array", "maxItems": 64, "uniqueItems": true, "items": {"type": "string", "maxLength": 256}},
                  "evidenceReferenceIds": {"type": "array", "maxItems": 64, "uniqueItems": true, "items": {"type": "string", "maxLength": 256}},
                  "disconfirmingEvidenceReferenceIds": {"type": "array", "maxItems": 64, "uniqueItems": true, "items": {"type": "string", "maxLength": 256}},
                  "certainty": {"enum": ["LOW", "MODERATE"]}
                }
              }
            },
            "nextMeasurementIds": {"type": "array", "maxItems": 64, "uniqueItems": true, "items": {"type": "string", "maxLength": 256}},
            "questionIdsForUser": {"type": "array", "maxItems": 64, "uniqueItems": true, "items": {"type": "string", "maxLength": 256}},
            "abstain": {"type": "boolean"},
            "abstainReason": {"type": ["string", "null"], "enum": ["INSUFFICIENT_EVIDENCE", "LOW_SIGNAL_QUALITY", "CONFLICTING_SIGNALS", "STALE_EVIDENCE", "MODEL_OR_SCHEMA_FAILURE", null]}
          }
        }
    """.trimIndent()

    const val JSON_SCHEMA_SHA256: String = "cb444b678b654cbc2893e05d48fc35ccc42dacce7f80f2f74f66e1ab57c00c03"

    fun systemPrompt() = PinnedUtf8Document(SYSTEM_PROMPT, SYSTEM_PROMPT_SHA256)

    fun jsonSchema() = PinnedUtf8Document(JSON_SCHEMA, JSON_SCHEMA_SHA256)
}

private object CanonicalLocalReasoningPrompt {
    fun render(request: LocalReasoningRequest): String = buildString {
        append("{\"task\":\"render-governed-local-reasoning\",\"request\":{")
        appendField("schemaVersion", request.schemaVersion)
        append(',')
        appendField("inputSnapshotSha256", request.inputSnapshotSha256)
        append(',')
        appendField("packetId", request.packetId)
        append(',')
        appendField("issuerId", request.issuerId)
        append(',')
        appendField("subjectPseudonym", request.subjectPseudonym)
        append(",\"issuedAtEpochMillis\":").append(request.issuedAtEpochMillis)
        append(",\"notBeforeEpochMillis\":").append(request.notBeforeEpochMillis)
        append(",\"expiresAtEpochMillis\":").append(request.expiresAtEpochMillis)
        append(",\"metricReferences\":[")
        request.metricReferences.sortedBy { it.id }.forEachIndexed { index, metric ->
            if (index > 0) append(',')
            append('{')
            appendField("id", metric.id)
            append(",\"value\":").append(metric.value.toString())
            append(',')
            appendField("unit", metric.unit)
            append(",\"quality\":").append(metric.quality.toString())
            append(',')
            appendField("windowId", metric.windowId)
            append('}')
        }
        append("],\"evidenceReferences\":[")
        request.evidenceReferences.sortedBy { it.id }.forEachIndexed { index, evidence ->
            if (index > 0) append(',')
            append('{')
            appendField("id", evidence.id)
            append(',')
            appendField("kind", evidence.kind.name)
            append(',')
            appendField("contentSha256", evidence.contentSha256)
            append(',')
            appendField("title", evidence.title)
            append(',')
            appendField("sourceUri", evidence.sourceUri)
            append(',')
            appendField("populationAndDevice", evidence.populationAndDevice)
            append(',')
            appendField("limitations", evidence.limitations)
            append(",\"verifiedAtEpochMillis\":").append(evidence.verifiedAtEpochMillis)
            append('}')
        }
        append("],\"approvedNextMeasurementIds\":")
        appendStringArray(request.approvedNextMeasurementIds.sorted())
        append(",\"approvedQuestionIds\":")
        appendStringArray(request.approvedQuestionIds.sorted())
        append(",\"approvedNarrativeTemplateIds\":")
        appendStringArray(request.approvedNarrativeTemplateIds.sorted())
        append(",\"qualityGaps\":")
        appendStringArray(request.qualityGaps.sorted())
        append(',')
        appendField("policyHashSha256", request.policyHashSha256)
        append("}}")
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append(OllamaJson.quote(name)).append(':').append(OllamaJson.quote(value))
    }

    private fun StringBuilder.appendStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(OllamaJson.quote(value))
        }
        append(']')
    }
}

private object OllamaGenerateRequestJson {
    fun encode(dto: OllamaGenerateRequestDto): ByteArray {
        val json = buildString {
            append('{')
            append("\"model\":").append(OllamaJson.quote(dto.model))
            append(",\"system\":").append(OllamaJson.quote(dto.system))
            append(",\"prompt\":").append(OllamaJson.quote(dto.prompt))
            append(",\"format\":").append(dto.formatJsonSchema.trim())
            append(",\"stream\":").append(dto.stream)
            append(",\"think\":").append(dto.think)
            append(",\"raw\":").append(dto.raw)
            append(",\"keep_alive\":").append(dto.keepAliveSeconds)
            append(",\"options\":{")
            append("\"seed\":").append(dto.options.seed)
            append(",\"temperature\":0.0")
            append(",\"num_ctx\":").append(dto.options.contextTokens)
            append(",\"num_predict\":").append(dto.options.maxOutputTokens)
            append("}}")
        }
        return OllamaProtocolDigests.strictUtf8(json)
    }
}

private object OllamaJson {
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

internal object OllamaProtocolDigests {
    fun promptSha256(systemPrompt: String, prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(strictUtf8(systemPrompt))
        digest.update(0)
        digest.update(strictUtf8(prompt))
        return digest.digest().toHex()
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        strictUtf8(left),
        strictUtf8(right),
    )

    fun strictUtf8(value: String): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun decodeStrictUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@toHex.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }
}
