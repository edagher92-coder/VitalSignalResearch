package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedProviderReasoningOrchestratorTest {
    @Test
    fun providerOutcomeAndAuditFailureSetsAreImmutableSnapshots() {
        val packet = ReasoningTestFixtures.packet()
        val unsafe = ReasoningTestFixtures.candidate(
            packet,
            templateId = ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            kind = NarrativeClaimKind.TREND,
        )
        val audit = RecordingProviderAuditSink()
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.successResponse(request, packet, unsafe)
        }
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport, audit = audit)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))
        val record = audit.records.single()

        assertThrows(UnsupportedOperationException::class.java) {
            (outcome.deterministicPolicyFailures as MutableSet<ReasoningFailureCode>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (outcome.providerPolicyFailures as MutableSet<ProviderPolicyFailureCode>) +=
                ProviderPolicyFailureCode.REQUEST_TOO_LARGE
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (record.deterministicPolicyFailures as MutableSet<ReasoningFailureCode>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (record.providerPolicyFailures as MutableSet<ProviderPolicyFailureCode>) +=
                ProviderPolicyFailureCode.REQUEST_TOO_LARGE
        }
        assertTrue(
            ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in
                outcome.deterministicPolicyFailures,
        )
    }

    @Test
    fun allThreeProvidersUseOneVerifiedAdvisoryContractForSyntheticFixtures() {
        ReasoningProvider.entries.forEach { provider ->
            val packet = ReasoningTestFixtures.packet()
            val invocation = ProviderReasoningTestFixtures.invocation(packet, provider)
            val outcome = ProviderReasoningTestFixtures.orchestrator(packet).run(packet, invocation)

            assertEquals(provider.name, ProviderReasoningDeliveryState.VERIFIED, outcome.state)
            assertNotNull(provider.name, outcome.candidate)
            assertEquals(provider, outcome.runReceipt!!.provider)
        }
    }

    @Test
    fun personalCloudPacketRejectsStandardRetentionBeforeTransport() {
        listOf(
            ReasoningProvider.OPENAI_RESPONSES,
            ReasoningProvider.ANTHROPIC_MESSAGES,
        ).forEach { provider ->
            val packet = ReasoningTestFixtures.packet()
            var transportCalls = 0
            val transport = ProviderReasoningTransport {
                transportCalls += 1
                ProviderReasoningTestFixtures.successResponse(it, packet)
            }
            val invocation = ProviderReasoningTestFixtures.invocation(
                packet = packet,
                provider = provider,
                retentionMode = ProviderRetentionMode.PROVIDER_STANDARD,
                payloadClass = ProviderPayloadClass.PERSONAL_HEALTH_MINIMIZED,
            )

            val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport).run(packet, invocation)

            assertEquals(0, transportCalls)
            assertEquals(ProviderReasoningDeliveryState.SAFE_FALLBACK, outcome.state)
            assertTrue(ProviderPolicyFailureCode.RETENTION_MISMATCH in outcome.providerPolicyFailures)
        }
    }

    @Test
    fun personalCloudPacketRequiresExternallyAttestedZdr() {
        listOf(
            ReasoningProvider.OPENAI_RESPONSES,
            ReasoningProvider.ANTHROPIC_MESSAGES,
        ).forEach { provider ->
            val packet = ReasoningTestFixtures.packet()
            val invocation = ProviderReasoningTestFixtures.invocation(
                packet = packet,
                provider = provider,
                retentionMode = ProviderRetentionMode.ZERO_DATA_RETENTION,
                payloadClass = ProviderPayloadClass.PERSONAL_HEALTH_MINIMIZED,
            )

            val outcome = ProviderReasoningTestFixtures.orchestrator(packet).run(packet, invocation)

            assertEquals(provider.name, ProviderReasoningDeliveryState.VERIFIED, outcome.state)
            assertEquals(ProviderRetentionMode.ZERO_DATA_RETENTION, invocation.privacyReceipt.retentionMode)
        }
    }

    @Test
    fun personalLocalPacketRequiresLocalOnlyRetention() {
        val packet = ReasoningTestFixtures.packet()
        val invocation = ProviderReasoningTestFixtures.invocation(
            packet = packet,
            provider = ReasoningProvider.OLLAMA_LOCAL,
            payloadClass = ProviderPayloadClass.PERSONAL_HEALTH_MINIMIZED,
        )

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet).run(packet, invocation)

        assertEquals(ProviderReasoningDeliveryState.VERIFIED, outcome.state)
        assertEquals(ProviderRetentionMode.LOCAL_ONLY, invocation.privacyReceipt.retentionMode)
    }

    @Test
    fun minimizedCloudPromptOmitsIdentityAndFreeTextWhileCredentialsAreUnrepresentable() {
        val packet = ReasoningTestFixtures.packet(extraQualityGaps = listOf("secret-quality-gap-text"))
        lateinit var captured: ProviderWireRequest
        val transport = ProviderReasoningTransport { request ->
            captured = request
            ProviderReasoningTestFixtures.successResponse(request, packet)
        }
        val invocation = ProviderReasoningTestFixtures.invocation(packet)

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport).run(packet, invocation)
        val prompt = captured.minimizedPromptBytes().toString(Charsets.UTF_8)

        assertEquals(ProviderReasoningDeliveryState.VERIFIED, outcome.state)
        assertFalse(prompt.contains("subject-1"))
        assertFalse(prompt.contains("fixture-engine"))
        assertFalse(prompt.contains("Qualified personal episode"))
        assertFalse(prompt.contains("personal://episode-1"))
        assertFalse(prompt.contains("Research-only fixture"))
        assertFalse(prompt.contains("secret-quality-gap-text"))
        assertTrue(prompt.contains("\"qualityGapCount\":1"))
        assertFalse(captured.storeResponse)
        assertFalse(captured.backgroundMode)
        assertFalse(captured.providerBrowsingEnabled)
        assertFalse(captured.providerToolsEnabled)
        assertFalse(captured.credentialsIncluded)
        assertTrue(captured.strictStructuredOutput)
    }

    @Test
    fun unauthenticatedPrivacyReceiptIsRejectedBeforeTransport() {
        val packet = ReasoningTestFixtures.packet()
        var calls = 0
        val transport = ProviderReasoningTransport {
            calls += 1
            ProviderReasoningTestFixtures.successResponse(it, packet)
        }
        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            transport = transport,
            privacyReceiptValid = false,
        ).run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(0, calls)
        assertTrue(
            ProviderPolicyFailureCode.PRIVACY_RECEIPT_AUTHENTICATION_FAILED in
                outcome.providerPolicyFailures,
        )
    }

    @Test
    fun forgedProviderAttestationIsRejectedBeforeTransportAndAudited() {
        val packet = ReasoningTestFixtures.packet()
        val original = ProviderReasoningTestFixtures.invocation(packet)
        val forged = ProviderPolicyAttestationIssuer(
            ProviderReasoningTestFixtures.SIGNING_KEY_ID,
            ProviderReasoningTestFixtures.attestationSigner("forged".toByteArray()),
        ).issue(original.providerPolicyAttestation.draft)
        val audit = RecordingProviderAuditSink()
        var calls = 0
        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            transport = ProviderReasoningTransport {
                calls += 1
                ProviderReasoningTestFixtures.successResponse(it, packet)
            },
            audit = audit,
        ).run(packet, original.copy(providerPolicyAttestation = forged))

        assertEquals(0, calls)
        assertEquals(ProviderPolicyAttestationFailureCode.SIGNATURE_INVALID, outcome.providerAttestationFailureCode)
        assertEquals(ProviderPolicyAttestationFailureCode.SIGNATURE_INVALID, audit.records.single().providerAttestationFailureCode)
    }

    @Test
    fun forgedPrivacyReceiptIsRejectedBeforeTransport() {
        val packet = ReasoningTestFixtures.packet()
        val original = ProviderReasoningTestFixtures.invocation(packet)
        val forged = ReasoningPrivacyReceiptIssuer(
            ProviderReasoningTestFixtures.PRIVACY_SIGNING_KEY_ID,
            ReasoningPrivacyReceiptSigner { ByteArray(32) { 0x5a } },
        ).issue(original.privacyReceipt.draft)
        var calls = 0

        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            transport = ProviderReasoningTransport {
                calls += 1
                ProviderReasoningTestFixtures.successResponse(it, packet)
            },
        ).run(packet, original.copy(privacyReceipt = forged))

        assertEquals(0, calls)
        assertEquals(ReasoningPrivacyReceiptFailureCode.SIGNATURE_INVALID, outcome.privacyReceiptFailureCode)
        assertTrue(
            ProviderPolicyFailureCode.PRIVACY_RECEIPT_AUTHENTICATION_FAILED in
                outcome.providerPolicyFailures,
        )
    }

    @Test
    fun privacyReceiptCannotBeReplayedAcrossSignedInputPackets() {
        val firstPacket = ReasoningTestFixtures.packet(packetId = "packet-first")
        val secondPacket = ReasoningTestFixtures.packet(packetId = "packet-second")
        val invocationBoundToFirst = ProviderReasoningTestFixtures.invocation(firstPacket)
        var calls = 0

        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = secondPacket,
            transport = ProviderReasoningTransport {
                calls += 1
                ProviderReasoningTestFixtures.successResponse(it, secondPacket)
            },
        ).run(secondPacket, invocationBoundToFirst)

        assertEquals(0, calls)
        assertTrue(ProviderPolicyFailureCode.INPUT_SNAPSHOT_MISMATCH in outcome.providerPolicyFailures)
    }

    @Test
    fun forgedGatewayResponseIsRejectedBeforeCandidateUse() {
        val packet = ReasoningTestFixtures.packet()
        val transport = ProviderReasoningTransport { request ->
            ProviderWireResponseIssuer(
                ProviderReasoningTestFixtures.GATEWAY_SIGNING_KEY_ID,
                ProviderGatewayResponseSigner { ByteArray(32) { 0x33 } },
            ).issue(ProviderReasoningTestFixtures.successResponseDraft(request, packet))
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(
            ProviderOrchestrationFailure.GATEWAY_RESPONSE_AUTHENTICATION_FAILED,
            outcome.orchestrationFailure,
        )
        assertEquals(ProviderGatewayResponseFailureCode.SIGNATURE_INVALID, outcome.gatewayResponseFailureCode)
        assertNull(outcome.candidate)
    }

    @Test
    fun signedGatewayResponseCannotReplayAcrossDifferentRequestHash() {
        val packet = ReasoningTestFixtures.packet()
        var cached: ProviderWireResponse? = null
        val transport = ProviderReasoningTransport { request ->
            cached ?: ProviderReasoningTestFixtures.successResponse(request, packet).also { cached = it }
        }
        val first = ProviderReasoningTestFixtures.invocation(packet)
        val second = first.copy(idempotencyKey = "invocation-key-v2")

        val firstOutcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, first)
        val replayOutcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, second)

        assertEquals(ProviderReasoningDeliveryState.VERIFIED, firstOutcome.state)
        assertEquals(ProviderOrchestrationFailure.RESPONSE_BINDING_MISMATCH, replayOutcome.orchestrationFailure)
        assertNull(replayOutcome.candidate)
    }

    @Test
    fun consentRevokedDuringInferenceIsRecheckedBeforeDelivery() {
        val packet = ReasoningTestFixtures.packet()
        var consentCurrent = true
        val transport = ProviderReasoningTransport { request ->
            consentCurrent = false
            ProviderReasoningTestFixtures.successResponse(request, packet)
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            transport = transport,
            currentConsentAuthorized = { consentCurrent },
        ).run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderReasoningDeliveryState.SAFE_FALLBACK, outcome.state)
        assertTrue(ProviderPolicyFailureCode.CURRENT_CONSENT_REJECTED in outcome.providerPolicyFailures)
        assertNull(outcome.candidate)
    }

    @Test
    fun mismatchedResponseBindingFailsClosed() {
        val packet = ReasoningTestFixtures.packet()
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.signedResponse(
                ProviderReasoningTestFixtures.successResponseDraft(request, packet).copy(
                    promptSha256 = "0".repeat(64),
                ),
            )
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.RESPONSE_BINDING_MISMATCH, outcome.orchestrationFailure)
        assertNull(outcome.candidate)
    }

    @Test
    fun nonSchemaValidatedResponseFailsClosed() {
        val packet = ReasoningTestFixtures.packet()
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.signedResponse(
                ProviderReasoningTestFixtures.successResponseDraft(request, packet).copy(
                    strictStructuredOutputValidated = false,
                ),
            )
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.STRUCTURED_OUTPUT_INVALID, outcome.orchestrationFailure)
    }

    @Test
    fun oversizedResponseFailsClosed() {
        val packet = ReasoningTestFixtures.packet()
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.signedResponse(
                ProviderReasoningTestFixtures.successResponseDraft(request, packet).copy(
                    responseBodySizeBytes = 8_193,
                ),
            )
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.RESPONSE_TOO_LARGE, outcome.orchestrationFailure)
    }

    @Test
    fun timeoutFailsClosedAndTripsCircuitFailure() {
        val packet = ReasoningTestFixtures.packet()
        var now = ProviderReasoningTestFixtures.NOW
        val circuit = RecordingCircuitBreaker()
        val transport = ProviderReasoningTransport { request ->
            now += 5_001L
            ProviderReasoningTestFixtures.successResponse(request, packet, completedAtEpochMillis = now)
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            transport = transport,
            now = { now },
            circuit = circuit,
        ).run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.TIMEOUT, outcome.orchestrationFailure)
        assertEquals(1, circuit.failures)
    }

    @Test
    fun rateCircuitAndReplayGatesEachStopProviderCall() {
        val packet = ReasoningTestFixtures.packet()
        val invocation = ProviderReasoningTestFixtures.invocation(packet)
        var calls = 0
        val transport = ProviderReasoningTransport {
            calls += 1
            ProviderReasoningTestFixtures.successResponse(it, packet)
        }

        val rate = ProviderReasoningTestFixtures.orchestrator(
            packet,
            transport,
            rateAllowed = false,
        ).run(packet, invocation)
        assertEquals(ProviderOrchestrationFailure.RATE_LIMITED, rate.orchestrationFailure)

        val circuit = ProviderReasoningTestFixtures.orchestrator(
            packet,
            transport,
            circuit = RecordingCircuitBreaker(allowed = false),
        ).run(packet, invocation)
        assertEquals(ProviderOrchestrationFailure.CIRCUIT_OPEN, circuit.orchestrationFailure)

        val replay = ProviderReasoningTestFixtures.orchestrator(
            packet,
            transport,
            replayGuard = RecordingReplayGuard(ProviderReplayReservation.DUPLICATE),
        ).run(packet, invocation)
        assertEquals(ProviderOrchestrationFailure.REPLAY_REJECTED, replay.orchestrationFailure)
        assertEquals(0, calls)
    }

    @Test
    fun providerRefusalBecomesAuditedAbstentionNotReassurance() {
        val packet = ReasoningTestFixtures.packet()
        val audit = RecordingProviderAuditSink()
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.signedResponse(ProviderWireResponseDraft(
                requestSha256 = request.requestSha256,
                providerRequestIdSha256 = "8".repeat(64),
                provider = request.provider,
                modelSnapshotId = request.modelSnapshotId,
                modelManifestSha256 = request.modelManifestSha256,
                promptSha256 = request.promptSha256,
                jsonSchemaSha256 = request.jsonSchemaSha256,
                policySha256 = request.policySha256,
                strictStructuredOutputValidated = true,
                responseBodySizeBytes = 128,
                candidate = null,
                refusalCode = ProviderRefusalCode.SAFETY_REFUSAL,
                completedAtEpochMillis = ProviderReasoningTestFixtures.NOW,
            ))
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport, audit = audit)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderReasoningDeliveryState.ABSTAINED, outcome.state)
        assertEquals(ProviderRefusalCode.SAFETY_REFUSAL, audit.records.single().providerRefusalCode)
        assertNull(outcome.candidate)
    }

    @Test
    fun deterministicPolicyStillRejectsCloudCandidate() {
        val packet = ReasoningTestFixtures.packet()
        val unsafe = ReasoningTestFixtures.candidate(
            packet,
            templateId = ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            kind = NarrativeClaimKind.TREND,
        )
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.successResponse(request, packet, unsafe)
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.DETERMINISTIC_POLICY_REJECTED, outcome.orchestrationFailure)
        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in outcome.deterministicPolicyFailures)
        assertNull(outcome.candidate)
    }

    @Test
    fun auditFailureSuppressesOtherwiseVerifiedCloudCandidate() {
        val packet = ReasoningTestFixtures.packet()
        val outcome = ProviderReasoningTestFixtures.orchestrator(
            packet = packet,
            audit = RecordingProviderAuditSink(commitSucceeds = false),
        ).run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderReasoningDeliveryState.SAFE_FALLBACK, outcome.state)
        assertEquals(ProviderOrchestrationFailure.AUDIT_COMMIT_FAILED, outcome.orchestrationFailure)
        assertNull(outcome.candidate)
    }

    @Test
    fun shadowChallengerIsAuditedButNeverDelivered() {
        val packet = ReasoningTestFixtures.packet()
        val invocation = ProviderReasoningTestFixtures.invocation(
            packet = packet,
            provider = ReasoningProvider.ANTHROPIC_MESSAGES,
            executionMode = ProviderExecutionMode.SHADOW_CHALLENGER,
        )

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet).run(packet, invocation)

        assertEquals(ProviderReasoningDeliveryState.SHADOW_RECORDED, outcome.state)
        assertNull(outcome.candidate)
        assertTrue(outcome.shadowCandidateSha256!!.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun packetThatExpiresDuringProviderCallCannotBeDelivered() {
        var now = 10_000L
        val packet = ReasoningTestFixtures.packet(expiresAt = 10_001L)
        val transport = ProviderReasoningTransport { request ->
            now = 10_001L
            ProviderReasoningTestFixtures.successResponse(request, packet, completedAtEpochMillis = now)
        }

        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport, now = { now })
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        assertEquals(ProviderOrchestrationFailure.HEALTH_STATE_AUTHORITY_REJECTED, outcome.orchestrationFailure)
        assertEquals(HealthStateAuthorityFailureCode.EXPIRED, outcome.authorityFailureCode)
        assertNull(outcome.candidate)
    }
}
