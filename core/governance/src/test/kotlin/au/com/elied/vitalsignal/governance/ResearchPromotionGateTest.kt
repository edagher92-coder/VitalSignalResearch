package au.com.elied.vitalsignal.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPromotionGateTest {
    private val verifier = PromotionEvidenceVerifier { it.signature.contentEquals(byteArrayOf(1)) }
    private val gate = ResearchPromotionGate(verifier)

    @Test
    fun promotionDecisionEvidenceCollectionsAreImmutableSnapshots() {
        val missing = mutableSetOf(PromotionEvidenceType.AUTOMATED_TESTS_PASSED)
        val receipts = mutableListOf("receipt-one")
        val decision = PromotionDecision(
            allowed = false,
            surface = ProductSurface.PRIVATE_SHADOW,
            denialReason = PromotionDenialReason.MISSING_EVIDENCE,
            missingEvidence = missing,
            consideredReceiptIds = receipts,
        )

        missing += PromotionEvidenceType.CLINICAL_SAFETY_REVIEWED
        receipts += "receipt-two"
        assertEquals(setOf(PromotionEvidenceType.AUTOMATED_TESTS_PASSED), decision.missingEvidence)
        assertEquals(listOf("receipt-one"), decision.consideredReceiptIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (decision.missingEvidence as MutableSet<PromotionEvidenceType>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (decision.consideredReceiptIds as MutableList<String>).clear()
        }
    }

    @Test
    fun codeAloneCannotPromoteAFeatureToShadowResearch() {
        val result = evaluate(ProductSurface.PRIVATE_SHADOW, emptyList())

        assertFalse(result.allowed)
        assertEquals(PromotionDenialReason.MISSING_EVIDENCE, result.denialReason)
        assertTrue(PromotionEvidenceType.AUTOMATED_TESTS_PASSED in result.missingEvidence)
    }

    @Test
    fun minimumShadowEvidenceCanEnableOnlyShadowResearch() {
        val receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW)

        val shadow = evaluate(ProductSurface.PRIVATE_SHADOW, receipts)
        val visible = evaluate(ProductSurface.PRIVATE_VISIBLE_WELLNESS, receipts)

        assertTrue(shadow.allowed)
        assertFalse(visible.allowed)
        assertTrue(PromotionEvidenceType.PROSPECTIVE_CALIBRATION_PASSED in visible.missingEvidence)
    }

    @Test
    fun privateVisibleRequiresProspectiveAndHumanReviewEvidence() {
        val result = evaluate(
            ProductSurface.PRIVATE_VISIBLE_WELLNESS,
            receiptsFor(ProductSurface.PRIVATE_VISIBLE_WELLNESS),
        )

        assertTrue(result.allowed)
    }

    @Test
    fun privateEvidenceCannotAuthorizePublicRelease() {
        val result = evaluate(
            ProductSurface.PUBLIC_WELLNESS,
            receiptsFor(ProductSurface.PRIVATE_VISIBLE_WELLNESS),
        )

        assertFalse(result.allowed)
        assertTrue(PromotionEvidenceType.EXTERNAL_COHORT_REPLICATED in result.missingEvidence)
    }

    @Test
    fun publicEvidenceCannotCreateAMedicalIntendedUse() {
        val result = evaluate(
            ProductSurface.MEDICAL_INTENDED_USE,
            receiptsFor(ProductSurface.PUBLIC_WELLNESS),
        )

        assertFalse(result.allowed)
        assertTrue(PromotionEvidenceType.REGULATORY_AUTHORIZATION_GRANTED in result.missingEvidence)
    }

    @Test
    fun anyFailedReceiptFailsClosed() {
        val receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW).toMutableList()
        receipts += receipt(PromotionEvidenceType.REFERENCE_DEVICE_AGREEMENT).copy(result = EvidenceResult.FAIL)

        val result = evaluate(ProductSurface.PRIVATE_SHADOW, receipts)

        assertEquals(PromotionDenialReason.FAILED_EVIDENCE, result.denialReason)
    }

    @Test
    fun firmwareEnvironmentChangeInvalidatesPriorEvidence() {
        val result = gate.evaluate(
            featureId = FEATURE_ID,
            featureVersion = FEATURE_VERSION,
            surface = ProductSurface.PRIVATE_SHADOW,
            environmentFingerprintSha256 = "f".repeat(64),
            evaluatedAtEpochMillis = 2_000L,
            receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW),
        )

        assertEquals(PromotionDenialReason.ENVIRONMENT_MISMATCH, result.denialReason)
    }

    @Test
    fun featureVersionChangeInvalidatesPriorEvidence() {
        val result = gate.evaluate(
            featureId = FEATURE_ID,
            featureVersion = "response-v2",
            surface = ProductSurface.PRIVATE_SHADOW,
            environmentFingerprintSha256 = ENVIRONMENT,
            evaluatedAtEpochMillis = 2_000L,
            receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW),
        )

        assertEquals(PromotionDenialReason.FEATURE_VERSION_MISMATCH, result.denialReason)
    }

    @Test
    fun invalidEvidenceSignatureFailsClosed() {
        val receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW).toMutableList()
        receipts[0] = receipts[0].copy(signature = byteArrayOf(8))

        val result = evaluate(ProductSurface.PRIVATE_SHADOW, receipts)

        assertEquals(PromotionDenialReason.INVALID_SIGNATURE, result.denialReason)
    }

    @Test
    fun historicalEvidenceDoesNotBlockCurrentValidatedVersion() {
        val current = receiptsFor(ProductSurface.PRIVATE_SHADOW)
        val historical = receipt(PromotionEvidenceType.SPECIFICATION_FROZEN).copy(
            receiptId = "historical-version-receipt",
            featureVersion = "response-v0",
            environmentFingerprintSha256 = "f".repeat(64),
        )

        val result = evaluate(ProductSurface.PRIVATE_SHADOW, current + historical)

        assertTrue(result.allowed)
        assertFalse("historical-version-receipt" in result.consideredReceiptIds)
    }

    @Test
    fun futureDatedEvidenceFailsClosed() {
        val receipts = receiptsFor(ProductSurface.PRIVATE_SHADOW).toMutableList()
        receipts[0] = receipts[0].copy(completedAtEpochMillis = 2_001L)

        val result = evaluate(ProductSurface.PRIVATE_SHADOW, receipts)

        assertFalse(result.allowed)
        assertEquals(PromotionDenialReason.EVIDENCE_NOT_YET_ACTIVE, result.denialReason)
    }

    @Test
    fun medicalPermitIsOpaqueAndBoundToExactFeatureVersionEnvironmentAndExpiry() {
        val decision = gate.issueMedicalPermit(
            featureId = FEATURE_ID,
            featureVersion = FEATURE_VERSION,
            environmentFingerprintSha256 = ENVIRONMENT,
            evaluatedAtEpochMillis = 2_000L,
            receipts = receiptsFor(ProductSurface.MEDICAL_INTENDED_USE),
        )

        assertTrue(decision is MedicalPromotionPermitDecision.Allowed)
        val permit = (decision as MedicalPromotionPermitDecision.Allowed).permit
        assertEquals(FEATURE_ID, permit.featureId)
        assertEquals(FEATURE_VERSION, permit.featureVersion)
        assertEquals(ENVIRONMENT, permit.environmentFingerprintSha256)
        assertTrue(permit.isCurrentAt(9_999L))
        assertFalse(permit.isCurrentAt(10_000L))
    }

    @Test
    fun medicalPermitHasBoundedTtlEvenWhenAllEvidenceIsNonExpiring() {
        val shortGate = ResearchPromotionGate(
            verifier = verifier,
            maximumMedicalPermitLifetimeMillis = 500L,
        )
        val receipts = receiptsFor(ProductSurface.MEDICAL_INTENDED_USE).map {
            it.copy(expiresAtEpochMillis = null)
        }

        val permit = (shortGate.issueMedicalPermit(
            featureId = FEATURE_ID,
            featureVersion = FEATURE_VERSION,
            environmentFingerprintSha256 = ENVIRONMENT,
            evaluatedAtEpochMillis = 2_000L,
            receipts = receipts,
        ) as MedicalPromotionPermitDecision.Allowed).permit

        assertFalse(permit.isCurrentAt(1_999L))
        assertTrue(permit.isCurrentAt(2_000L))
        assertTrue(permit.isCurrentAt(2_499L))
        assertFalse(permit.isCurrentAt(2_500L))
    }

    @Test
    fun medicalPermitUsesEarlierEvidenceExpiryInsteadOfConfiguredTtl() {
        val receipts = receiptsFor(ProductSurface.MEDICAL_INTENDED_USE).toMutableList()
        receipts[0] = receipts[0].copy(expiresAtEpochMillis = 2_250L)

        val permit = (ResearchPromotionGate(verifier, 500L).issueMedicalPermit(
            featureId = FEATURE_ID,
            featureVersion = FEATURE_VERSION,
            environmentFingerprintSha256 = ENVIRONMENT,
            evaluatedAtEpochMillis = 2_000L,
            receipts = receipts,
        ) as MedicalPromotionPermitDecision.Allowed).permit

        assertEquals(2_250L, permit.validUntilEpochMillis)
        assertFalse(permit.isCurrentAt(2_250L))
    }

    private fun evaluate(
        surface: ProductSurface,
        receipts: List<PromotionEvidenceReceipt>,
    ) = gate.evaluate(
        featureId = FEATURE_ID,
        featureVersion = FEATURE_VERSION,
        surface = surface,
        environmentFingerprintSha256 = ENVIRONMENT,
        evaluatedAtEpochMillis = 2_000L,
        receipts = receipts,
    )

    private fun receiptsFor(surface: ProductSurface): List<PromotionEvidenceReceipt> {
        val types = when (surface) {
            ProductSurface.PRIVATE_SHADOW -> SHADOW
            ProductSurface.PRIVATE_VISIBLE_WELLNESS -> PRIVATE_VISIBLE
            ProductSurface.PUBLIC_WELLNESS -> PUBLIC
            ProductSurface.MEDICAL_INTENDED_USE -> MEDICAL
        }
        return types.map(::receipt)
    }

    private fun receipt(type: PromotionEvidenceType) = PromotionEvidenceReceipt(
        receiptId = "receipt-${type.name.lowercase()}",
        featureId = FEATURE_ID,
        featureVersion = FEATURE_VERSION,
        evidenceType = type,
        result = EvidenceResult.PASS,
        environmentFingerprintSha256 = ENVIRONMENT,
        protocolOrDatasetSha256 = "b".repeat(64),
        completedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 10_000L,
        issuerKeyId = "fixture-evidence-key",
        signature = byteArrayOf(1),
    )

    private companion object {
        const val FEATURE_ID = "standardized-response"
        const val FEATURE_VERSION = "response-v1"
        val ENVIRONMENT = "a".repeat(64)
        val SHADOW = setOf(
            PromotionEvidenceType.SPECIFICATION_FROZEN,
            PromotionEvidenceType.AUTOMATED_TESTS_PASSED,
            PromotionEvidenceType.DATA_QUALITY_AND_MISSINGNESS_VALIDATED,
        )
        val PRIVATE_VISIBLE = SHADOW + setOf(
            PromotionEvidenceType.EXACT_DEVICE_FIRMWARE_VALIDATED,
            PromotionEvidenceType.REFERENCE_DEVICE_AGREEMENT,
            PromotionEvidenceType.CHRONOLOGICAL_HOLDOUT_PASSED,
            PromotionEvidenceType.PROSPECTIVE_CALIBRATION_PASSED,
            PromotionEvidenceType.FALSE_ALERT_BUDGET_PASSED,
            PromotionEvidenceType.HUMAN_FACTORS_REVIEWED,
            PromotionEvidenceType.CLINICAL_SAFETY_REVIEWED,
        )
        val PUBLIC = PRIVATE_VISIBLE + setOf(
            PromotionEvidenceType.EXTERNAL_COHORT_REPLICATED,
            PromotionEvidenceType.SUBGROUP_FAIRNESS_REVIEWED,
            PromotionEvidenceType.PRIVACY_SECURITY_REVIEWED,
            PromotionEvidenceType.REGULATORY_CLASSIFICATION_REVIEWED,
        )
        val MEDICAL = PUBLIC + setOf(
            PromotionEvidenceType.CLINICAL_PERFORMANCE_VALIDATED,
            PromotionEvidenceType.QUALITY_SYSTEM_RELEASED,
            PromotionEvidenceType.REGULATORY_AUTHORIZATION_GRANTED,
        )
    }
}
