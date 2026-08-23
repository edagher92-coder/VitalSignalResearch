package au.com.elied.vitalsignal.reasoning

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaServerAdapterTest {
    @Test
    fun httpHeaderCollectionsAreImmutableDeepSnapshots() {
        val requestHeaders = linkedMapOf("Authorization" to "Bearer original")
        val responseValues = mutableListOf("application/json")
        val responseHeaders = linkedMapOf("Content-Type" to responseValues)
        val request = OllamaHttpRequest(
            method = OllamaHttpMethod.POST,
            uri = URI("https://server-pc.internal/ollama/api/generate"),
            headers = requestHeaders,
            bodyBytes = "{}".toByteArray(),
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            maxResponseBodyBytes = 1_024,
        )
        val response = OllamaHttpResponse(
            statusCode = 200,
            headers = responseHeaders,
            bodyBytes = "{}".toByteArray(),
            effectiveUri = request.uri,
        )
        val models = mutableListOf(descriptor())
        val tags = OllamaTagsResponseDto(models)

        requestHeaders["Authorization"] = "Bearer changed"
        responseValues[0] = "text/html"
        responseHeaders["Location"] = mutableListOf("https://attacker.invalid")
        models.clear()
        assertEquals("Bearer original", request.headers["Authorization"])
        assertEquals(listOf("application/json"), response.headers["Content-Type"])
        assertFalse(response.headers.containsKey("Location"))
        assertEquals(1, tags.models.size)

        assertThrows(UnsupportedOperationException::class.java) {
            (request.headers as MutableMap<String, String>)["Authorization"] = "Bearer changed"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (response.headers as MutableMap<String, List<String>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (response.headers.getValue("Content-Type") as MutableList<String>)[0] = "text/html"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (tags.models as MutableList<OllamaModelDescriptorDto>).clear()
        }
    }

    @Test
    fun exactPinnedRunBuildsBoundedToolFreeRequestAndReceipt() {
        val transport = RecordingTransport()
        val adapter = adapter(transport = transport)

        val (actualCandidate, receipt) = adapter.generate(reasoningRequest())

        assertEquals(candidate(), actualCandidate)
        assertEquals(EXPECTED_VERSION, receipt.ollamaVersion)
        assertEquals(MODEL_NAME, receipt.modelName)
        assertEquals(MODEL_DIGEST, receipt.modelDigest)
        assertEquals(QUANTIZATION, receipt.quantization)
        assertEquals(73L, receipt.seed)
        assertEquals(0.0, receipt.temperature, 0.0)
        assertEquals(4_096, receipt.contextTokens)
        assertEquals(OllamaReasoningProtocol.JSON_SCHEMA_SHA256, receipt.jsonSchemaSha256)
        assertTrue(receipt.promptSha256.matches(Regex("[a-f0-9]{64}")))

        assertEquals(
            listOf("/api/version", "/api/tags", "/api/generate", "/api/tags"),
            transport.requests.map { it.uri.path },
        )
        assertTrue(transport.requests.all { !it.followRedirects })
        assertTrue(transport.requests.all { it.connectTimeoutMillis == 1_234 })
        assertTrue(transport.requests.all { it.readTimeoutMillis == 5_678 })
        assertTrue(transport.requests.all { it.maxResponseBodyBytes == 16_384 })
        val generation = transport.requests.single { it.uri.path == "/api/generate" }
        val body = OllamaProtocolDigests.decodeStrictUtf8(generation.bodyBytes())
        assertTrue(body.contains("\"model\":\"$MODEL_NAME\""))
        assertTrue(body.contains("\"seed\":73"))
        assertTrue(body.contains("\"temperature\":0.0"))
        assertTrue(body.contains("\"num_ctx\":4096"))
        assertTrue(body.contains("\"num_predict\":512"))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"think\":false"))
        assertTrue(body.contains("\"format\":{"))
        assertTrue(body.contains("sleeping-hr"))
        assertFalse(body.contains("\"tools\""))
        assertFalse(body.contains("/api/chat"))
        assertEquals("application/json", generation.headers["Content-Type"])
    }

    @Test
    fun promptHashIsDeterministicAndChangesWithTheTypedPacket() {
        val adapter = adapter()

        val first = adapter.generate(reasoningRequest()).second.promptSha256
        val repeated = adapter.generate(reasoningRequest()).second.promptSha256
        val changedPacket = reasoningPacket(extraQualityGaps = listOf("short-window"))
        val changed = adapter.generate(reasoningRequest(changedPacket)).second.promptSha256

        assertEquals(first, repeated)
        assertFalse(first == changed)
    }

    @Test
    fun defaultPromptAndSchemaHashesAreActuallyPinned() {
        assertEquals(
            OllamaReasoningProtocol.SYSTEM_PROMPT_SHA256,
            OllamaProtocolDigests.sha256Hex(OllamaProtocolDigests.strictUtf8(OllamaReasoningProtocol.SYSTEM_PROMPT)),
        )
        assertEquals(
            OllamaReasoningProtocol.JSON_SCHEMA_SHA256,
            OllamaProtocolDigests.sha256Hex(OllamaProtocolDigests.strictUtf8(OllamaReasoningProtocol.JSON_SCHEMA)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PinnedUtf8Document("{}", "0".repeat(64))
        }
    }

    @Test
    fun serverVersionMustMatchExactly() {
        val decoder = FixtureWireDecoder(version = OllamaVersionResponseDto("different"))

        assertAdapterFailure(OllamaAdapterFailureCode.SERVER_VERSION_MISMATCH) {
            adapter(decoder = decoder).generate(reasoningRequest())
        }
    }

    @Test
    fun modelDigestAndQuantizationMustMatchBeforeGeneration() {
        val wrongDigest = descriptor(digest = "2".repeat(64))
        val digestTransport = RecordingTransport()
        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_DIGEST_MISMATCH) {
            adapter(
                transport = digestTransport,
                decoder = FixtureWireDecoder(tagResponses = listOf(OllamaTagsResponseDto(listOf(wrongDigest)))),
            ).generate(reasoningRequest())
        }
        assertEquals(listOf("/api/version", "/api/tags"), digestTransport.requests.map { it.uri.path })

        val wrongQuantization = descriptor(quantization = "Q8_0")
        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_QUANTIZATION_MISMATCH) {
            adapter(
                decoder = FixtureWireDecoder(
                    tagResponses = listOf(OllamaTagsResponseDto(listOf(wrongQuantization))),
                ),
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun modelDigestIsAttestedAgainAfterGeneration() {
        val decoder = FixtureWireDecoder(
            tagResponses = listOf(
                goodTags(),
                OllamaTagsResponseDto(listOf(descriptor(digest = "2".repeat(64)))),
            ),
        )

        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_DIGEST_MISMATCH) {
            adapter(decoder = decoder).generate(reasoningRequest())
        }
    }

    @Test
    fun responseModelNameAndCompletionMustMatchExactly() {
        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_NAME_MISMATCH) {
            adapter(
                decoder = FixtureWireDecoder(generate = generated(model = "other:tag")),
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.INCOMPLETE_GENERATION) {
            adapter(
                decoder = FixtureWireDecoder(generate = generated(done = false)),
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun missingOrAmbiguousPinnedModelFailsClosed() {
        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_NOT_FOUND) {
            adapter(
                decoder = FixtureWireDecoder(
                    tagResponses = listOf(OllamaTagsResponseDto(listOf(descriptor(name = "other:tag")))),
                ),
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.MODEL_NAME_MISMATCH) {
            adapter(
                decoder = FixtureWireDecoder(
                    tagResponses = listOf(OllamaTagsResponseDto(listOf(descriptor(), descriptor()))),
                ),
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun redirectStatusAndChangedEffectiveUriAreBothRejected() {
        assertAdapterFailure(OllamaAdapterFailureCode.REDIRECT_REJECTED) {
            adapter(
                transport = RecordingTransport { request -> jsonResponse(request, status = 302) },
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.REDIRECT_REJECTED) {
            adapter(
                transport = RecordingTransport { request ->
                    jsonResponse(request, effectiveUri = URI("https://attacker.invalid/api/version"))
                },
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun responseStatusContentTypeUtf8AndBodyLimitsFailClosed() {
        assertAdapterFailure(OllamaAdapterFailureCode.UNEXPECTED_HTTP_STATUS) {
            adapter(
                transport = RecordingTransport { request -> jsonResponse(request, status = 503) },
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.UNEXPECTED_CONTENT_TYPE) {
            adapter(
                transport = RecordingTransport { request ->
                    jsonResponse(request, headers = mapOf("Content-Type" to listOf("text/html")))
                },
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.MALFORMED_RESPONSE) {
            adapter(
                transport = RecordingTransport { request ->
                    jsonResponse(request, body = byteArrayOf(0xc3.toByte(), 0x28))
                },
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.RESPONSE_TOO_LARGE) {
            adapter(
                config = config(httpLimits = limits(maxResponseBodyBytes = 1_024)),
                transport = RecordingTransport { request ->
                    jsonResponse(request, body = ByteArray(1_025) { 'x'.code.toByte() })
                },
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun requestBodyLimitIsEnforcedBeforeAnyNetworkCall() {
        val transport = RecordingTransport()
        val largePacket = reasoningPacket(extraQualityGaps = listOf("x".repeat(4_000)))
        val request = reasoningRequest(largePacket)

        assertAdapterFailure(OllamaAdapterFailureCode.REQUEST_TOO_LARGE) {
            adapter(
                config = config(httpLimits = limits(maxRequestBodyBytes = 1_024)),
                transport = transport,
            ).generate(request)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun transportDecoderParserAndCandidateLimitFailuresAreTypedAndDoNotLeakPayloads() {
        assertAdapterFailure(OllamaAdapterFailureCode.TRANSPORT_FAILED) {
            adapter(
                transport = RecordingTransport { throw IllegalStateException("secret transport body") },
            ).generate(reasoningRequest())
        }

        val malformed = assertThrows(OllamaAdapterException::class.java) {
            adapter(
                decoder = object : FixtureWireDecoder() {
                    override fun decodeVersion(jsonUtf8: String): OllamaVersionResponseDto {
                        throw IllegalArgumentException("secret response body")
                    }
                },
            ).generate(reasoningRequest())
        }
        assertEquals(OllamaAdapterFailureCode.MALFORMED_RESPONSE, malformed.failureCode)
        assertFalse(malformed.message.orEmpty().contains("secret"))

        assertAdapterFailure(OllamaAdapterFailureCode.STRUCTURED_RESPONSE_INVALID) {
            adapter(parser = OllamaStructuredResponseParser { throw IllegalArgumentException("bad") })
                .generate(reasoningRequest())
        }

        val manyClaims = (0..32).map { index ->
            candidate().claims.single().copy(id = "claim-$index")
        }
        assertAdapterFailure(OllamaAdapterFailureCode.STRUCTURED_RESPONSE_TOO_LARGE) {
            adapter(
                parser = OllamaStructuredResponseParser { candidate().copy(claims = manyClaims) },
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun unsafeOrOverridingGatewayHeadersAreRejected() {
        assertAdapterFailure(OllamaAdapterFailureCode.INVALID_REQUEST_HEADERS) {
            adapter(
                headersProvider = OllamaHttpHeadersProvider { _, _ -> mapOf("Host" to "attacker.invalid") },
            ).generate(reasoningRequest())
        }
        assertAdapterFailure(OllamaAdapterFailureCode.INVALID_REQUEST_HEADERS) {
            adapter(
                headersProvider = OllamaHttpHeadersProvider { _, _ -> mapOf("X-Token" to "ok\r\nHost: bad") },
            ).generate(reasoningRequest())
        }
    }

    @Test
    fun configuredHttpsGatewayUsesExactPrefixAndCanReceiveAuthHeader() {
        val transport = RecordingTransport()
        val gateway = TrustedPrivateHttpsGateway(URI("https://server-pc.internal/ollama"))
        val adapter = adapter(
            config = config(baseUri = URI("https://server-pc.internal/ollama")),
            endpointPolicy = OllamaEndpointPolicy(setOf(gateway)),
            transport = transport,
            headersProvider = OllamaHttpHeadersProvider { _, _ -> mapOf("Authorization" to "Bearer fixture") },
        )

        adapter.generate(reasoningRequest())

        assertTrue(transport.requests.all { it.uri.path.startsWith("/ollama/api/") })
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer fixture" })
    }

    @Test
    fun cleartextServerPcEndpointIsRejectedBeforeTransport() {
        val transport = RecordingTransport()

        assertAdapterFailure(OllamaAdapterFailureCode.ENDPOINT_REJECTED) {
            adapter(
                config = config(baseUri = URI("http://192.168.1.20:11434")),
                transport = transport,
            )
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun adapterStillPassesThroughVerifierAndAuditBeforeDelivery() {
        val packet = reasoningPacket()
        val unsafe = candidate(packet).copy(
            claims = listOf(
                candidate(packet).claims.single().copy(
                    kind = NarrativeClaimKind.TREND,
                    templateId = ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
                ),
            ),
        )
        val audit = RecordingAuditSink()
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = adapter(parser = OllamaStructuredResponseParser { unsafe }),
            authority = ReasoningTestFixtures.authority(),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { 9_000L },
        )

        val outcome = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, outcome.state)
        assertNull(outcome.candidate)
        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in outcome.policyFailureCodes)
        assertEquals(ReasoningOrchestrationFailure.POLICY_REJECTED, outcome.orchestrationFailure)
        assertNotNull(audit.records.single().runReceipt)
    }

    @Test
    fun adapterAttestationFailureUsesExistingModelUnavailableFallback() {
        val packet = reasoningPacket()
        val audit = RecordingAuditSink()
        val badAdapter = adapter(
            decoder = FixtureWireDecoder(
                tagResponses = listOf(
                    OllamaTagsResponseDto(listOf(descriptor(digest = "2".repeat(64)))),
                ),
            ),
        )
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = badAdapter,
            authority = ReasoningTestFixtures.authority(),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { 9_000L },
        )

        val outcome = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, outcome.state)
        assertEquals(ReasoningOrchestrationFailure.MODEL_UNAVAILABLE, outcome.orchestrationFailure)
        assertNull(outcome.runReceipt)
        assertEquals(ReasoningOrchestrationFailure.MODEL_UNAVAILABLE, audit.records.single().orchestrationFailure)
    }

    private fun adapter(
        config: OllamaServerConfig = config(),
        endpointPolicy: OllamaEndpointPolicy = OllamaEndpointPolicy(),
        transport: RecordingTransport = RecordingTransport(),
        decoder: OllamaWireResponseDecoder = FixtureWireDecoder(),
        parser: OllamaStructuredResponseParser = OllamaStructuredResponseParser { candidate() },
        headersProvider: OllamaHttpHeadersProvider = OllamaHttpHeadersProvider { _, _ -> emptyMap() },
    ) = OllamaServerAdapter(
        config = config,
        endpointPolicy = endpointPolicy,
        transport = transport,
        wireDecoder = decoder,
        structuredResponseParser = parser,
        nowEpochMillis = { 8_000L },
        headersProvider = headersProvider,
    )

    private fun config(
        baseUri: URI = URI("http://127.0.0.1:11434"),
        httpLimits: OllamaHttpLimits = limits(),
    ) = OllamaServerConfig(
        baseUri = baseUri,
        expectedOllamaVersion = EXPECTED_VERSION,
        model = PinnedOllamaModel(MODEL_NAME, MODEL_DIGEST, QUANTIZATION),
        seed = 73L,
        contextTokens = 4_096,
        maxOutputTokens = 512,
        httpLimits = httpLimits,
    )

    private fun limits(
        maxRequestBodyBytes: Int = 16_384,
        maxResponseBodyBytes: Int = 16_384,
    ) = OllamaHttpLimits(
        connectTimeoutMillis = 1_234,
        readTimeoutMillis = 5_678,
        maxRequestBodyBytes = maxRequestBodyBytes,
        maxResponseBodyBytes = maxResponseBodyBytes,
    )

    private open class FixtureWireDecoder(
        private val version: OllamaVersionResponseDto = OllamaVersionResponseDto(EXPECTED_VERSION),
        private val tagResponses: List<OllamaTagsResponseDto> = listOf(goodTags()),
        private val generate: OllamaGenerateResponseDto = generated(),
    ) : OllamaWireResponseDecoder {
        private var tagIndex = 0

        override fun decodeVersion(jsonUtf8: String) = version

        override fun decodeTags(jsonUtf8: String): OllamaTagsResponseDto {
            val result = tagResponses[tagIndex.coerceAtMost(tagResponses.lastIndex)]
            tagIndex += 1
            return result
        }

        override fun decodeGenerate(jsonUtf8: String) = generate
    }

    private class RecordingTransport(
        private val responder: (OllamaHttpRequest) -> OllamaHttpResponse = { request -> jsonResponse(request) },
    ) : OllamaHttpTransport {
        val requests = mutableListOf<OllamaHttpRequest>()

        override fun execute(request: OllamaHttpRequest): OllamaHttpResponse {
            requests += request
            return responder(request)
        }
    }

    private class RecordingAuditSink : ReasoningAuditSink {
        val records = mutableListOf<ReasoningAuditRecord>()

        override fun commit(record: ReasoningAuditRecord): Boolean {
            records += record
            return true
        }
    }

    private companion object {
        const val EXPECTED_VERSION = "0.12.3"
        const val MODEL_NAME = "fixture-model:q4"
        val MODEL_DIGEST = "1".repeat(64)
        const val QUANTIZATION = "Q4_K_M"

        fun descriptor(
            name: String = MODEL_NAME,
            digest: String = MODEL_DIGEST,
            quantization: String = QUANTIZATION,
        ) = OllamaModelDescriptorDto(name, digest, quantization)

        fun goodTags() = OllamaTagsResponseDto(listOf(descriptor()))

        fun generated(
            model: String = MODEL_NAME,
            done: Boolean = true,
        ) = OllamaGenerateResponseDto(
            model = model,
            structuredResponse = "{\"fixture\":true}",
            done = done,
            doneReason = if (done) "stop" else null,
            totalDurationNanos = 10L,
            loadDurationNanos = 2L,
            promptEvalCount = 20,
            evalCount = 10,
        )

        fun jsonResponse(
            request: OllamaHttpRequest,
            status: Int = 200,
            headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
            body: ByteArray = "{}".toByteArray(Charsets.UTF_8),
            effectiveUri: URI = request.uri,
        ) = OllamaHttpResponse(status, headers, body, effectiveUri)

        fun reasoningPacket(extraQualityGaps: List<String> = emptyList()) =
            ReasoningTestFixtures.packet(extraQualityGaps = extraQualityGaps)

        fun reasoningRequest(packet: SignedHealthStatePacket = reasoningPacket()) =
            ReasoningTestFixtures.authority().verify(packet)

        fun candidate(packet: SignedHealthStatePacket = reasoningPacket()) =
            ReasoningTestFixtures.candidate(packet)

        fun assertAdapterFailure(code: OllamaAdapterFailureCode, block: () -> Unit) {
            val error = assertThrows(OllamaAdapterException::class.java, block)
            assertEquals(code, error.failureCode)
        }
    }
}
