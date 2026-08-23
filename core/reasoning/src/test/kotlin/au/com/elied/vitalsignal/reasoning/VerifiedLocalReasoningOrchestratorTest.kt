package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedLocalReasoningOrchestratorTest {
    @Test
    fun validCandidateIsDeliveredOnlyAfterAuditCommit() {
        val packet = ReasoningTestFixtures.packet()
        val audit = RecordingAuditSink()
        val orchestrator = orchestrator(packet, auditSink = audit)

        val result = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.DELIVERABLE, result.state)
        assertNotNull(result.candidate)
        assertEquals(1, audit.records.size)
        assertNotNull(audit.records.single().candidateSha256)
    }

    @Test
    fun unapprovedSemanticSelectionIsNeverDelivered() {
        val packet = ReasoningTestFixtures.packet()
        val unsafe = ReasoningTestFixtures.candidate(
            packet,
            ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            NarrativeClaimKind.TREND,
        )
        val result = orchestrator(packet, candidate = unsafe).run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, result.state)
        assertNull(result.candidate)
        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in result.policyFailureCodes)
    }

    @Test
    fun cleanModelAbstentionRemainsAbstention() {
        val packet = ReasoningTestFixtures.packet()
        val abstention = ReasoningTestFixtures.candidate(packet).copy(
            claims = emptyList(),
            nextMeasurementIds = emptyList(),
            abstain = true,
            abstainReason = AbstainReasonCode.INSUFFICIENT_EVIDENCE,
        )

        val result = orchestrator(packet, candidate = abstention).run(packet)

        assertEquals(ReasoningDeliveryState.ABSTAINED, result.state)
        assertNull(result.candidate)
        assertNull(result.safeTemplateId)
    }

    @Test
    fun auditFailureSuppressesOtherwiseValidCandidate() {
        val packet = ReasoningTestFixtures.packet()
        val orchestrator = orchestrator(
            packet,
            auditSink = RecordingAuditSink(commitSucceeds = false),
        )

        val result = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, result.state)
        assertEquals(ReasoningOrchestrationFailure.AUDIT_COMMIT_FAILED, result.orchestrationFailure)
        assertNull(result.candidate)
    }

    @Test
    fun unavailableModelReturnsReviewedStaticFallback() {
        val packet = ReasoningTestFixtures.packet()
        val audit = RecordingAuditSink()
        val gateway = LocalReasoningGateway { throw IllegalStateException("fixture unavailable") }
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = gateway,
            authority = ReasoningTestFixtures.authority(),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { ReasoningTestFixtures.NOW },
        )

        val result = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, result.state)
        assertEquals("local-reasoning-unavailable-v1", result.safeTemplateId)
        assertEquals(ReasoningOrchestrationFailure.MODEL_UNAVAILABLE, audit.records.single().orchestrationFailure)
    }

    @Test
    fun forgedAuthorityIsRejectedBeforeGatewayAndAudited() {
        val packet = ReasoningTestFixtures.packet(
            signer = ReasoningTestFixtures.signer("wrong-key".toByteArray()),
        )
        var gatewayCalls = 0
        val audit = RecordingAuditSink()
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = LocalReasoningGateway {
                gatewayCalls += 1
                ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt()
            },
            authority = ReasoningTestFixtures.authority(),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { ReasoningTestFixtures.NOW },
        )

        val result = orchestrator.run(packet)

        assertEquals(0, gatewayCalls)
        assertEquals(ReasoningOrchestrationFailure.HEALTH_STATE_AUTHORITY_REJECTED, result.orchestrationFailure)
        assertEquals(HealthStateAuthorityFailureCode.SIGNATURE_INVALID, result.authorityFailureCode)
        assertEquals(HealthStateAuthorityFailureCode.SIGNATURE_INVALID, audit.records.single().authorityFailureCode)
    }

    @Test
    fun authorityThatExpiresDuringGenerationCannotBeDelivered() {
        var now = 10_000L
        val packet = ReasoningTestFixtures.packet(expiresAt = 10_001L)
        val audit = RecordingAuditSink()
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = LocalReasoningGateway {
                now = 10_001L
                ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt()
            },
            authority = ReasoningTestFixtures.authority(now = { now }),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { now },
        )

        val result = orchestrator.run(packet)

        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, result.state)
        assertEquals(HealthStateAuthorityFailureCode.EXPIRED, result.authorityFailureCode)
        assertNull(result.candidate)
        assertNotNull(audit.records.single().runReceipt)
    }

    @Test
    fun auditStoresCanonicalCandidateDigestRatherThanCandidateContent() {
        val packet = ReasoningTestFixtures.packet()
        val audit = RecordingAuditSink()
        orchestrator(packet, auditSink = audit).run(packet)

        val record = audit.records.single()

        assertTrue(record.candidateSha256!!.matches(Regex("[a-f0-9]{64}")))
        assertEquals("local-reasoning-orchestrator-v2", record.policyVersion)
    }

    @Test
    fun gatewayCandidateMutationDuringAuditCannotChangeHashedDeliveredSnapshot() {
        val packet = ReasoningTestFixtures.packet()
        val fixture = ReasoningTestFixtures.candidate(packet)
        val mutableMetricReferences = fixture.claims.single().metricReferenceIds.toMutableList()
        val mutableClaims = mutableListOf(
            fixture.claims.single().copy(metricReferenceIds = mutableMetricReferences),
        )
        val mutableMeasurements = fixture.nextMeasurementIds.toMutableList()
        val mutableCandidate = fixture.copy(
            claims = mutableClaims,
            nextMeasurementIds = mutableMeasurements,
        )
        val audit = object : ReasoningAuditSink {
            lateinit var record: ReasoningAuditRecord

            override fun commit(record: ReasoningAuditRecord): Boolean {
                this.record = record
                mutableMetricReferences.clear()
                mutableClaims.clear()
                mutableMeasurements.clear()
                return true
            }
        }
        val orchestrator = VerifiedLocalReasoningOrchestrator(
            gateway = LocalReasoningGateway {
                mutableCandidate to ReasoningTestFixtures.receipt()
            },
            authority = ReasoningTestFixtures.authority(),
            policy = LocalReasoningPolicy(),
            auditSink = audit,
            nowEpochMillis = { ReasoningTestFixtures.NOW },
        )

        val result = orchestrator.run(packet)
        val delivered = requireNotNull(result.candidate)

        assertEquals(ReasoningDeliveryState.DELIVERABLE, result.state)
        assertEquals(1, delivered.claims.size)
        assertEquals(listOf("sleeping-hr"), delivered.claims.single().metricReferenceIds)
        assertEquals(CanonicalReasoningCandidate.sha256(delivered), audit.record.candidateSha256)
    }

    @Test
    fun localPolicyAuditAndOutcomeFailureSetsRejectDowncastMutation() {
        val packet = ReasoningTestFixtures.packet()
        val audit = RecordingAuditSink()
        val unsafe = ReasoningTestFixtures.candidate(
            packet,
            ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            NarrativeClaimKind.TREND,
        )

        val result = orchestrator(packet, unsafe, audit).run(packet)
        val record = audit.records.single()

        assertThrows(UnsupportedOperationException::class.java) {
            (record.policyFailureCodes as MutableSet<ReasoningFailureCode>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.policyFailureCodes as MutableSet<ReasoningFailureCode>).clear()
        }
        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in record.policyFailureCodes)
        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in result.policyFailureCodes)
    }

    private fun orchestrator(
        packet: SignedHealthStatePacket,
        candidate: LocalReasoningCandidate = ReasoningTestFixtures.candidate(packet),
        auditSink: RecordingAuditSink = RecordingAuditSink(),
    ): VerifiedLocalReasoningOrchestrator = VerifiedLocalReasoningOrchestrator(
        gateway = LocalReasoningGateway { candidate to ReasoningTestFixtures.receipt() },
        authority = ReasoningTestFixtures.authority(),
        policy = LocalReasoningPolicy(),
        auditSink = auditSink,
        nowEpochMillis = { ReasoningTestFixtures.NOW },
    )

    private class RecordingAuditSink(
        private val commitSucceeds: Boolean = true,
    ) : ReasoningAuditSink {
        val records = mutableListOf<ReasoningAuditRecord>()

        override fun commit(record: ReasoningAuditRecord): Boolean {
            records += record
            return commitSucceeds
        }
    }
}
