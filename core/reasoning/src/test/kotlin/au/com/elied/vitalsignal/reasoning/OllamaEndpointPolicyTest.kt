package au.com.elied.vitalsignal.reasoning

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OllamaEndpointPolicyTest {
    @Test
    fun loopbackHttpIsAllowedForDevelopment() {
        val endpoint = OllamaEndpointPolicy().requireAllowed(URI("http://127.0.0.1:11434"))

        assertEquals(URI("http://127.0.0.1:11434/api/generate"), endpoint.apiUri("/api/generate"))
    }

    @Test
    fun localhostAndIpv6LoopbackAreExactNotSuffixMatches() {
        OllamaEndpointPolicy().requireAllowed(URI("http://localhost:11434"))
        OllamaEndpointPolicy().requireAllowed(URI("http://[::1]:11434"))

        assertFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
            OllamaEndpointPolicy().requireAllowed(URI("http://localhost.attacker.invalid:11434"))
        }
    }

    @Test
    fun cleartextPrivateAndPublicServersAreRejected() {
        listOf(
            "http://192.168.1.20:11434",
            "http://10.0.0.20:11434",
            "http://ollama.internal:11434",
            "http://203.0.113.20:11434",
        ).forEach { endpoint ->
            assertFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
                OllamaEndpointPolicy().requireAllowed(URI(endpoint))
            }
        }
    }

    @Test
    fun untrustedHttpsHostIsRejected() {
        assertFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
            OllamaEndpointPolicy().requireAllowed(URI("https://ollama.internal"))
        }
    }

    @Test
    fun configuredPrivateHttpsGatewayAndExactBasePathAreAllowed() {
        val gateway = TrustedPrivateHttpsGateway(URI("https://server-pc.internal/ollama"))
        val policy = OllamaEndpointPolicy(setOf(gateway))

        val endpoint = policy.requireAllowed(URI("https://server-pc.internal/ollama/"))

        assertEquals(URI("https://server-pc.internal/ollama/api/tags"), endpoint.apiUri("/api/tags"))
        assertFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
            policy.requireAllowed(URI("https://server-pc.internal/other"))
        }
    }

    @Test
    fun publicIpLiteralCannotBecomeTrustedByConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            TrustedPrivateHttpsGateway(URI("https://8.8.8.8"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedPrivateHttpsGateway(URI("https://[2001:4860:4860::8888]"))
        }
    }

    @Test
    fun endpointCredentialsQueryFragmentAndTraversalAreRejected() {
        listOf(
            "http://user:secret@localhost:11434",
            "http://localhost:11434?next=https://attacker.invalid",
            "http://localhost:11434#fragment",
            "http://localhost:11434/%2e%2e/escape",
            "http://2130706433:11434",
        ).forEach { endpoint ->
            assertFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
                OllamaEndpointPolicy().requireAllowed(URI(endpoint))
            }
        }
    }

    private fun assertFailure(code: OllamaAdapterFailureCode, block: () -> Unit) {
        val error = assertThrows(OllamaAdapterException::class.java, block)
        assertEquals(code, error.failureCode)
    }
}
