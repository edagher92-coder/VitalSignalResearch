package au.com.elied.vitalsignal.governance

import au.com.elied.vitalsignal.model.SensorMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalAuthorizationTest {
    private val pilotGate = PilotAccessGate(
        consentVerifier = ConsentGrantVerifier { it.signature.contentEquals(byteArrayOf(1)) },
        validationVerifier = ValidationReceiptVerifier { it.signature.contentEquals(byteArrayOf(2)) },
    )
    private val issuer = ClinicianSharePermitIssuer(
        pilotAccessGate = pilotGate,
        grantVerifier = ClinicianShareGrantVerifier { it.signature.contentEquals(byteArrayOf(3)) },
    )

    @Test
    fun exactSignedGrantAndPilotArtifactsIssueShortLivedBoundPermit() {
        val decision = issuer.issue(request(), consent(), listOf(validation()), grant())

        assertTrue(decision is ClinicianSharePermitDecision.Allowed)
        val permit = (decision as ClinicianSharePermitDecision.Allowed).permit
        assertEquals(PilotCapability.CLINICIAN_LIVE_SHARE, permit.capability)
        assertEquals("session-1", permit.sessionId)
        assertEquals("observer-1", permit.observerPrincipalId)
        assertEquals(SensorMetric.HEART_RATE, permit.metric)
        assertEquals(ClinicalDataClass.DERIVED_SCALAR_SUMMARY, permit.dataClass)
        assertEquals("clinician-portal-1", permit.destinationId)
        assertEquals(61_500L, permit.validUntilEpochMillis)
        assertTrue(permit.isCurrentAt(61_499L))
        assertFalse(permit.isCurrentAt(61_500L))
    }

    @Test
    fun wrongCapabilityCannotIssueClinicianPermit() {
        val denied = issuer.issue(
            request().copy(capability = PilotCapability.PERSONAL_INTERPRETATION),
            consent(),
            listOf(validation()),
            grant(),
        ) as ClinicianSharePermitDecision.Denied

        assertEquals(ClinicianSharePermitDenialReason.CAPABILITY_MISMATCH, denied.reason)
    }

    @Test
    fun everySignedGrantBindingIsExact() {
        val alteredRequests = listOf(
            request().copy(subjectPseudonym = "subject-2"),
            request().copy(consentGeneration = 2L),
            request().copy(sessionId = "session-2"),
            request().copy(observerPrincipalId = "observer-2"),
            request().copy(metric = SensorMetric.RESPIRATORY_RATE),
            request().copy(dataClass = ClinicalDataClass.FHIR_OBSERVATION_DRAFT),
            request().copy(destinationId = "destination-2"),
            request().copy(sessionStartsAtEpochMillis = 1_001L),
            request().copy(sessionEndsAtEpochMillis = 99_999L),
        )

        alteredRequests.forEach { altered ->
            assertTrue(
                issuer.issue(altered, consent(), listOf(validation()), grant())
                    is ClinicianSharePermitDecision.Denied,
            )
        }
    }

    @Test
    fun invalidGrantSignatureAndNonCurrentGrantFailClosed() {
        val invalid = issuer.issue(
            request(),
            consent(),
            listOf(validation()),
            grant().copy(signature = byteArrayOf(9)),
        ) as ClinicianSharePermitDecision.Denied
        val notYetActive = issuer.issue(
            request().copy(evaluatedAtEpochMillis = 999L),
            consent(),
            listOf(validation().copy(issuedAtEpochMillis = 950L)),
            grant(),
        ) as ClinicianSharePermitDecision.Denied
        val expired = issuer.issue(
            request().copy(evaluatedAtEpochMillis = 100_000L),
            consent().copy(expiresAtEpochMillis = 100_001L),
            listOf(validation().copy(expiresAtEpochMillis = 100_001L)),
            grant(),
        ) as ClinicianSharePermitDecision.Denied

        assertEquals(ClinicianSharePermitDenialReason.GRANT_SIGNATURE_INVALID, invalid.reason)
        assertEquals(ClinicianSharePermitDenialReason.GRANT_NOT_YET_ACTIVE, notYetActive.reason)
        assertEquals(ClinicianSharePermitDenialReason.GRANT_EXPIRED, expired.reason)
    }

    @Test
    fun missingClinicianScopeFailsAtCentralPilotGate() {
        val decision = issuer.issue(
            request(),
            consent().copy(scopes = setOf(ConsentScope.PERSONAL_INSIGHTS)),
            listOf(validation()),
            grant(),
        ) as ClinicianSharePermitDecision.Denied

        assertEquals(ClinicianSharePermitDenialReason.PILOT_GATE_DENIED, decision.reason)
        assertEquals(PilotGateReason.CONSENT_SCOPE_MISSING, decision.pilotDecision?.reason)
    }

    @Test
    fun observerHeartbeatLeaseRequiresValidSignatureAndCurrentTime() {
        val gate = ObserverHeartbeatGate(
            ObserverHeartbeatReceiptVerifier { it.signature.contentEquals(byteArrayOf(4)) },
        )

        val allowed = gate.issue(heartbeat(), 2_000L)
        val future = gate.issue(heartbeat(), 999L)
        val expired = gate.issue(heartbeat(), 3_000L)
        val invalid = gate.issue(heartbeat().copy(signature = byteArrayOf(8)), 2_000L)

        assertTrue(allowed is ObserverHeartbeatDecision.Allowed)
        assertEquals(
            ObserverHeartbeatDenialReason.NOT_YET_ACTIVE,
            (future as ObserverHeartbeatDecision.Denied).reason,
        )
        assertEquals(
            ObserverHeartbeatDenialReason.EXPIRED,
            (expired as ObserverHeartbeatDecision.Denied).reason,
        )
        assertEquals(
            ObserverHeartbeatDenialReason.SIGNATURE_INVALID,
            (invalid as ObserverHeartbeatDecision.Denied).reason,
        )

        val longReceipt = heartbeat().copy(expiresAtEpochMillis = 100_000L)
        val bounded = gate.issue(longReceipt, 2_000L) as ObserverHeartbeatDecision.Allowed
        assertEquals(31_000L, bounded.lease.validUntilEpochMillis)
        assertFalse(bounded.lease.isCurrentAt(31_000L))
        assertEquals(
            ObserverHeartbeatDenialReason.STALE,
            (gate.issue(longReceipt, 31_000L) as ObserverHeartbeatDecision.Denied).reason,
        )
    }

    @Test
    fun clinicalRulePermitRequiresSignedCurrentExactReceipt() {
        val issuer = ClinicalRulePermitIssuer(
            ClinicalRuleApprovalVerifier { it.signature.contentEquals(byteArrayOf(5)) },
        )
        val medicalPermit = medicalPermit()
        val allowed = issuer.issue(ruleReceipt(), medicalPermit, 2_000L)
        val future = issuer.issue(ruleReceipt(), medicalPermit, 999L)
        val expired = issuer.issue(ruleReceipt(), medicalPermit, 5_000L)
        val invalid = issuer.issue(
            ruleReceipt().copy(signature = byteArrayOf(9)),
            medicalPermit,
            2_000L,
        )

        assertTrue(allowed is ClinicalRulePermitDecision.Allowed)
        val permit = (allowed as ClinicalRulePermitDecision.Allowed).permit
        assertEquals("clinical-heart-rule", permit.ruleId)
        assertEquals("clinical-live-monitor", permit.medicalFeatureId)
        assertEquals(ProductSurface.MEDICAL_INTENDED_USE, permit.medicalSurface)
        assertEquals("session-1", permit.sessionId)
        assertFalse(permit.isCurrentAt(5_000L))
        assertEquals(
            ClinicalRulePermitDenialReason.NOT_YET_ACTIVE,
            (future as ClinicalRulePermitDecision.Denied).reason,
        )
        assertEquals(
            ClinicalRulePermitDenialReason.EXPIRED,
            (expired as ClinicalRulePermitDecision.Denied).reason,
        )
        assertEquals(
            ClinicalRulePermitDenialReason.SIGNATURE_INVALID,
            (invalid as ClinicalRulePermitDecision.Denied).reason,
        )
    }

    @Test
    fun clinicalRulePermitRequiresExactCurrentMedicalPromotionAndUsesShortestTtl() {
        val ruleIssuer = ClinicalRulePermitIssuer(
            verifier = ClinicalRuleApprovalVerifier { true },
            maximumPermitLifetimeMillis = 500L,
        )
        val allowed = ruleIssuer.issue(ruleReceipt(), medicalPermit(expiresAtEpochMillis = 2_300L), 2_000L)
            as ClinicalRulePermitDecision.Allowed
        val ttlBound = ruleIssuer.issue(ruleReceipt(), medicalPermit(), 2_000L)
            as ClinicalRulePermitDecision.Allowed
        val wrongFeature = ruleIssuer.issue(
            ruleReceipt(),
            medicalPermit(featureId = "different-feature"),
            2_000L,
        ) as ClinicalRulePermitDecision.Denied
        val expiredPromotion = ruleIssuer.issue(
            ruleReceipt(),
            medicalPermit(expiresAtEpochMillis = 2_000L),
            2_000L,
        ) as ClinicalRulePermitDecision.Denied

        assertEquals(2_300L, allowed.permit.validUntilEpochMillis)
        assertEquals(2_500L, ttlBound.permit.validUntilEpochMillis)
        assertEquals(
            ClinicalRulePermitDenialReason.MEDICAL_PROMOTION_MISMATCH,
            wrongFeature.reason,
        )
        assertEquals(
            ClinicalRulePermitDenialReason.MEDICAL_PROMOTION_NOT_CURRENT,
            expiredPromotion.reason,
        )
    }

    @Test
    fun alertActionPermitAuthenticatesActorActionVersionAndCurrentClinicianShare() {
        val sharePermit = (issuer.issue(request(), consent(), listOf(validation()), grant())
            as ClinicianSharePermitDecision.Allowed).permit
        val actionIssuer = ClinicalAlertActionPermitIssuer(
            ClinicalAlertActionReceiptVerifier { it.signature.contentEquals(byteArrayOf(6)) },
        )
        val receipt = alertActionReceipt()

        val allowed = actionIssuer.issue(receipt, 2_000L, sharePermit)
            as ClinicalAlertActionPermitDecision.Allowed
        val missingShare = actionIssuer.issue(receipt, 2_000L)
            as ClinicalAlertActionPermitDecision.Denied
        val forged = actionIssuer.issue(receipt.copy(signature = byteArrayOf(9)), 2_000L, sharePermit)
            as ClinicalAlertActionPermitDecision.Denied
        val wrongActor = actionIssuer.issue(
            receipt.copy(actorPrincipalId = "observer-2"),
            2_000L,
            sharePermit,
        ) as ClinicalAlertActionPermitDecision.Denied
        val wrongRole = actionIssuer.issue(
            receipt.copy(actorRole = ClinicalAlertActorRole.ROUTING_SERVICE),
            2_000L,
        ) as ClinicalAlertActionPermitDecision.Denied
        val expired = actionIssuer.issue(receipt, 3_000L, sharePermit)
            as ClinicalAlertActionPermitDecision.Denied

        assertEquals("observer-1", allowed.permit.actorPrincipalId)
        assertEquals(1L, allowed.permit.expectedAlertVersion)
        assertEquals("share-grant-1", allowed.permit.clinicianShareGrantId)
        assertEquals(
            ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_REQUIRED,
            missingShare.reason,
        )
        assertEquals(ClinicalAlertActionPermitDenialReason.SIGNATURE_INVALID, forged.reason)
        assertEquals(
            ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_BINDING_MISMATCH,
            wrongActor.reason,
        )
        assertEquals(ClinicalAlertActionPermitDenialReason.ROLE_ACTION_MISMATCH, wrongRole.reason)
        assertEquals(ClinicalAlertActionPermitDenialReason.EXPIRED, expired.reason)
    }

    @Test
    fun everyAlertActionPermitHasBoundedShortLifetime() {
        val receipt = alertActionReceipt().copy(
            expectedAlertVersion = null,
            actorPrincipalId = "alert-engine",
            actorRole = ClinicalAlertActorRole.ALERT_ENGINE,
            action = ClinicalAlertAction.CREATE,
            expiresAtEpochMillis = 100_000L,
        )
        val decision = ClinicalAlertActionPermitIssuer(
            verifier = ClinicalAlertActionReceiptVerifier { true },
            maximumPermitLifetimeMillis = 500L,
        ).issue(receipt, 2_000L) as ClinicalAlertActionPermitDecision.Allowed

        assertEquals(2_500L, decision.permit.validUntilEpochMillis)
        assertTrue(decision.permit.isCurrentAt(2_499L))
        assertFalse(decision.permit.isCurrentAt(2_500L))
    }

    private fun request() = ClinicianSharePermitRequest(
        capability = PilotCapability.CLINICIAN_LIVE_SHARE,
        subjectPseudonym = "subject-1",
        consentGeneration = 1L,
        sessionId = "session-1",
        observerPrincipalId = "observer-1",
        metric = SensorMetric.HEART_RATE,
        dataClass = ClinicalDataClass.DERIVED_SCALAR_SUMMARY,
        destinationId = "clinician-portal-1",
        sessionStartsAtEpochMillis = 1_000L,
        sessionEndsAtEpochMillis = 100_000L,
            appVersion = "0.6.0-research",
        deviceModel = "fixture-ultra2",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "live-scalar-v1",
        evaluatedAtEpochMillis = 1_500L,
        collectionPaused = false,
        recoveryRequired = false,
    )

    private fun consent() = ConsentGrant(
        subjectPseudonym = "subject-1",
        generation = 1L,
        scopes = setOf(ConsentScope.CLINICIAN_LIVE_DATA_SHARE),
        issuedAtEpochMillis = 900L,
        expiresAtEpochMillis = 100_000L,
        consentTextSha256 = "a".repeat(64),
        signerKeyId = "consent-key",
        signature = byteArrayOf(1),
    )

    private fun validation() = ValidationReceipt(
        receiptId = "validation-clinician-1",
        capability = PilotCapability.CLINICIAN_LIVE_SHARE,
                appVersion = "0.6.0-research",
        deviceModel = "fixture-ultra2",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "live-scalar-v1",
        issuedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 100_000L,
        evidenceIds = listOf("observer-security-test", "stream-failure-test"),
        evidenceBundleSha256 = "b".repeat(64),
        issuerKeyId = "validation-key",
        signature = byteArrayOf(2),
    )

    private fun grant() = ClinicianShareGrant(
        grantId = "share-grant-1",
        subjectPseudonym = "subject-1",
        consentGeneration = 1L,
        sessionId = "session-1",
        observerPrincipalId = "observer-1",
        metric = SensorMetric.HEART_RATE,
        dataClass = ClinicalDataClass.DERIVED_SCALAR_SUMMARY,
        destinationId = "clinician-portal-1",
        issuedAtEpochMillis = 900L,
        startsAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 100_000L,
        termsSha256 = "c".repeat(64),
        signerKeyId = "share-key",
        signature = byteArrayOf(3),
    )

    private fun heartbeat() = ObserverHeartbeatReceipt(
        receiptId = "heartbeat-1",
        sessionId = "session-1",
        observerPrincipalId = "observer-1",
        destinationId = "clinician-portal-1",
        recordedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 3_000L,
        issuerKeyId = "heartbeat-key",
        signature = byteArrayOf(4),
    )

    private fun ruleReceipt() = ClinicalRuleApprovalReceipt(
        receiptId = "clinical-rule-receipt-1",
        ruleId = "clinical-heart-rule",
        ruleVersion = "1",
        medicalFeatureId = "clinical-live-monitor",
        medicalFeatureVersion = "clinical-live-monitor-v1",
        environmentFingerprintSha256 = "d".repeat(64),
        sessionId = "session-1",
        subjectPseudonym = "subject-1",
        approvedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 5_000L,
        issuerKeyId = "rule-key",
        signature = byteArrayOf(5),
    )

    private fun medicalPermit(
        featureId: String = "clinical-live-monitor",
        expiresAtEpochMillis: Long = 10_000L,
    ) = MedicalPromotionPermit.issue(
        featureId = featureId,
        featureVersion = "clinical-live-monitor-v1",
        environmentFingerprintSha256 = "d".repeat(64),
        surface = ProductSurface.MEDICAL_INTENDED_USE,
        issuedAtEpochMillis = 900L,
        validUntilEpochMillis = expiresAtEpochMillis,
        evidenceReceiptIds = listOf("medical-evidence-1"),
    )

    private fun alertActionReceipt() = ClinicalAlertActionReceipt(
        receiptId = "alert-action-1",
        alertId = "alert-1",
        sessionId = "session-1",
        subjectPseudonym = "subject-1",
        expectedAlertVersion = 1L,
        actorPrincipalId = "observer-1",
        actorRole = ClinicalAlertActorRole.CLINICIAN_OBSERVER,
        action = ClinicalAlertAction.ACKNOWLEDGE,
        issuedAtEpochMillis = 1_500L,
        startsAtEpochMillis = 1_500L,
        expiresAtEpochMillis = 3_000L,
        issuerKeyId = "action-key",
        signature = byteArrayOf(6),
    )
}
