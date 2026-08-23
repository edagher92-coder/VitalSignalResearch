package au.com.elied.vitalsignal.governance

import au.com.elied.vitalsignal.model.SensorMetric
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernanceReceiptAuthenticationTest {
    private val verifier = HmacGovernanceVerifier(
        GovernanceKeyResolver { purpose, keyId ->
            if (keyId == keyId(purpose)) key(purpose) else null
        },
    )

    @Test
    fun issuedConsentVerifiesButScopeMutationDoesNot() {
        val grant = consent()

        assertTrue(verifier.verify(grant))
        assertFalse(verifier.verify(grant.copy(scopes = grant.scopes + ConsentScope.MEDICAL_RECORDS)))
    }

    @Test
    fun consentGenerationIsAuthenticated() {
        val grant = consent()

        assertFalse(verifier.verify(grant.copy(generation = 8L)))
    }

    @Test
    fun validationEnvironmentAndEvidenceAreAuthenticated() {
        val receipt = validation()

        assertTrue(verifier.verify(receipt))
        assertFalse(verifier.verify(receipt.copy(firmwareGeneration = "different-firmware")))
        assertFalse(verifier.verify(receipt.copy(evidenceIds = listOf("different-evidence"))))
    }

    @Test
    fun promotionFailureCannotBeChangedToPass() {
        val receipt = authority(GovernanceReceiptPurpose.PROMOTION_EVIDENCE).issuePromotionEvidence(
            receiptId = "promotion-1",
            featureId = "physiological-response",
            featureVersion = "response-v1",
            evidenceType = PromotionEvidenceType.REFERENCE_DEVICE_AGREEMENT,
            result = EvidenceResult.FAIL,
            environmentFingerprintSha256 = "d".repeat(64),
            protocolOrDatasetSha256 = "e".repeat(64),
            completedAtEpochMillis = 3_000L,
            expiresAtEpochMillis = 9_000L,
        )

        assertTrue(verifier.verify(receipt))
        assertFalse(verifier.verify(receipt.copy(result = EvidenceResult.PASS)))
    }

    @Test
    fun unknownOrWrongKeyFailsClosed() {
        val receipt = validation()
        val unknown = receipt.copy(issuerKeyId = "unknown-authority")
        val wrongVerifier = HmacGovernanceVerifier(
            GovernanceKeyResolver { _, _ -> ByteArray(32) { 99 } },
        )

        assertFalse(verifier.verify(unknown))
        assertFalse(wrongVerifier.verify(receipt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortAuthorityKeyIsRejected() {
        HmacGovernanceAuthority("short", GovernanceReceiptPurpose.CONSENT, ByteArray(16))
    }

    @Test
    fun clinicianShareGrantAuthenticatesEveryPrivacyBinding() {
        val grant = authority(GovernanceReceiptPurpose.CLINICIAN_SHARE).issueClinicianShareGrant(
            grantId = "share-grant-1",
            subjectPseudonym = "participant-001",
            consentGeneration = 7L,
            sessionId = "session-1",
            observerPrincipalId = "observer-1",
            metric = SensorMetric.HEART_RATE,
            dataClass = ClinicalDataClass.DERIVED_SCALAR_SUMMARY,
            destinationId = "portal-1",
            issuedAtEpochMillis = 1_000L,
            startsAtEpochMillis = 2_000L,
            expiresAtEpochMillis = 10_000L,
            termsSha256 = "f".repeat(64),
        )

        assertTrue(verifier.verify(grant))
        assertFalse(verifier.verify(grant.copy(observerPrincipalId = "observer-2")))
        assertFalse(verifier.verify(grant.copy(metric = SensorMetric.RESPIRATORY_RATE)))
        assertFalse(verifier.verify(grant.copy(destinationId = "portal-2")))
        assertFalse(verifier.verify(grant.copy(expiresAtEpochMillis = 9_999L)))
        grant.signature.fill(0)
        assertTrue(verifier.verify(grant))
    }

    @Test
    fun heartbeatAndClinicalRuleReceiptsRejectMutation() {
        val heartbeat = authority(GovernanceReceiptPurpose.OBSERVER_HEARTBEAT).issueObserverHeartbeat(
            receiptId = "heartbeat-1",
            sessionId = "session-1",
            observerPrincipalId = "observer-1",
            destinationId = "portal-1",
            recordedAtEpochMillis = 2_000L,
            expiresAtEpochMillis = 3_000L,
        )
        val rule = authority(GovernanceReceiptPurpose.CLINICAL_RULE_APPROVAL)
            .issueClinicalRuleApproval(
            receiptId = "rule-receipt-1",
            ruleId = "heart-rule",
            ruleVersion = "1",
            medicalFeatureId = "clinical-live-monitor",
            medicalFeatureVersion = "clinical-live-monitor-v1",
            environmentFingerprintSha256 = "c".repeat(64),
            sessionId = "session-1",
            subjectPseudonym = "participant-001",
            approvedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 10_000L,
        )

        assertTrue(verifier.verify(heartbeat))
        assertFalse(verifier.verify(heartbeat.copy(observerPrincipalId = "observer-2")))
        assertTrue(verifier.verify(rule))
        assertFalse(verifier.verify(rule.copy(ruleVersion = "2")))
        assertFalse(verifier.verify(rule.copy(medicalFeatureVersion = "different-version")))
    }

    @Test
    fun alertActionReceiptAuthenticatesActorRoleActionAlertAndVersion() {
        val receipt = authority(GovernanceReceiptPurpose.CLINICAL_ALERT_ACTION)
            .issueClinicalAlertAction(
            receiptId = "action-1",
            alertId = "alert-1",
            sessionId = "session-1",
            subjectPseudonym = "participant-001",
            expectedAlertVersion = 2L,
            actorPrincipalId = "observer-1",
            actorRole = ClinicalAlertActorRole.CLINICIAN_OBSERVER,
            action = ClinicalAlertAction.RESOLVE,
            issuedAtEpochMillis = 2_000L,
            startsAtEpochMillis = 2_000L,
            expiresAtEpochMillis = 3_000L,
        )

        assertTrue(verifier.verify(receipt))
        assertFalse(verifier.verify(receipt.copy(alertId = "alert-2")))
        assertFalse(verifier.verify(receipt.copy(expectedAlertVersion = 3L)))
        assertFalse(verifier.verify(receipt.copy(actorPrincipalId = "observer-2")))
        assertFalse(verifier.verify(receipt.copy(actorRole = ClinicalAlertActorRole.ROUTING_SERVICE)))
        assertFalse(verifier.verify(receipt.copy(action = ClinicalAlertAction.ACKNOWLEDGE)))
        assertFalse(verifier.verify(receipt.copy(expiresAtEpochMillis = 3_001L)))
        receipt.signature.fill(0)
        assertTrue(verifier.verify(receipt))
    }

    @Test
    fun signedArtifactsSnapshotCallerCollectionsAndNeverExposeMutableSignatureState() {
        val mutableScopes = mutableSetOf(ConsentScope.PASSIVE_WATCH_DATA)
        val grant = authority(GovernanceReceiptPurpose.CONSENT).issueConsent(
            subjectPseudonym = "participant-001",
            generation = 7L,
            scopes = mutableScopes,
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 10_000L,
            consentTextSha256 = "a".repeat(64),
        )
        mutableScopes += ConsentScope.MEDICAL_RECORDS
        val exposedConsentSignature = grant.signature
        exposedConsentSignature[0] = (exposedConsentSignature[0].toInt() xor 0xff).toByte()

        val mutableEvidence = mutableListOf("device-test-1")
        val validation = authority(GovernanceReceiptPurpose.VALIDATION).issueValidation(
            receiptId = "validation-snapshot",
            capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
            appVersion = "0.5.0-research",
            deviceModel = "SM-L705F",
            firmwareGeneration = "firmware-a",
            dataSchemaVersion = "watch-envelope-v1",
            issuedAtEpochMillis = 2_000L,
            expiresAtEpochMillis = 9_000L,
            evidenceIds = mutableEvidence,
            evidenceBundleSha256 = "b".repeat(64),
        )
        mutableEvidence += "injected-after-signing"
        val exposedValidationSignature = validation.signature
        exposedValidationSignature.fill(0)

        assertEquals(setOf(ConsentScope.PASSIVE_WATCH_DATA), grant.scopes)
        assertEquals(listOf("device-test-1"), validation.evidenceIds)
        assertTrue(verifier.verify(grant))
        assertTrue(verifier.verify(validation))
        assertThrows(UnsupportedOperationException::class.java) {
            (grant.scopes as MutableSet<ConsentScope>).add(ConsentScope.DATA_EXPORT)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (validation.evidenceIds as MutableList<String>).add("injected")
        }
    }

    @Test
    fun consentAuthorityCannotMintClinicalOrOtherPurposeReceipts() {
        val consentAuthority = authority(GovernanceReceiptPurpose.CONSENT)

        assertThrows(IllegalArgumentException::class.java) {
            consentAuthority.issueClinicalRuleApproval(
                receiptId = "forged-rule",
                ruleId = "heart-rule",
                ruleVersion = "1",
                medicalFeatureId = "clinical-live-monitor",
                medicalFeatureVersion = "clinical-live-monitor-v1",
                environmentFingerprintSha256 = "c".repeat(64),
                sessionId = "session-1",
                subjectPseudonym = "participant-001",
                approvedAtEpochMillis = 1_000L,
                expiresAtEpochMillis = 10_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            consentAuthority.issueValidation(
                receiptId = "forged-validation",
                capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
                appVersion = "0.5.0-research",
                deviceModel = "fixture",
                firmwareGeneration = "fixture-fw",
                dataSchemaVersion = "fixture-v1",
                issuedAtEpochMillis = 1_000L,
                expiresAtEpochMillis = 10_000L,
                evidenceIds = listOf("forged"),
                evidenceBundleSha256 = "b".repeat(64),
            )
        }
    }

    private fun consent() = authority(GovernanceReceiptPurpose.CONSENT).issueConsent(
        subjectPseudonym = "participant-001",
        generation = 7L,
        scopes = setOf(ConsentScope.PASSIVE_WATCH_DATA, ConsentScope.PERSONAL_INSIGHTS),
        issuedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 10_000L,
        consentTextSha256 = "a".repeat(64),
    )

    private fun validation() = authority(GovernanceReceiptPurpose.VALIDATION).issueValidation(
        receiptId = "validation-1",
        capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
        appVersion = "0.5.0-research",
        deviceModel = "SM-L705F",
        firmwareGeneration = "firmware-a",
        dataSchemaVersion = "watch-envelope-v1",
        issuedAtEpochMillis = 2_000L,
        expiresAtEpochMillis = 9_000L,
        evidenceIds = listOf("device-test-1", "packet-loss-test-1"),
        evidenceBundleSha256 = "b".repeat(64),
    )

    private fun authority(purpose: GovernanceReceiptPurpose) = HmacGovernanceAuthority(
        keyId(purpose),
        purpose,
        key(purpose),
    )

    private fun keyId(purpose: GovernanceReceiptPurpose) =
        "pilot-${purpose.name.lowercase()}-authority-v1"

    private fun key(purpose: GovernanceReceiptPurpose) =
        ByteArray(32) { index -> (index + 1 + purpose.ordinal * 7).toByte() }
}
