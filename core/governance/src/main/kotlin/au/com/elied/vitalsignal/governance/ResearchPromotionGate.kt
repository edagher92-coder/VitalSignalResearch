package au.com.elied.vitalsignal.governance

enum class ProductSurface {
    PRIVATE_SHADOW,
    PRIVATE_VISIBLE_WELLNESS,
    PUBLIC_WELLNESS,
    MEDICAL_INTENDED_USE,
}

enum class PromotionEvidenceType {
    SPECIFICATION_FROZEN,
    AUTOMATED_TESTS_PASSED,
    DATA_QUALITY_AND_MISSINGNESS_VALIDATED,
    EXACT_DEVICE_FIRMWARE_VALIDATED,
    REFERENCE_DEVICE_AGREEMENT,
    CHRONOLOGICAL_HOLDOUT_PASSED,
    PROSPECTIVE_CALIBRATION_PASSED,
    FALSE_ALERT_BUDGET_PASSED,
    HUMAN_FACTORS_REVIEWED,
    CLINICAL_SAFETY_REVIEWED,
    EXTERNAL_COHORT_REPLICATED,
    SUBGROUP_FAIRNESS_REVIEWED,
    PRIVACY_SECURITY_REVIEWED,
    REGULATORY_CLASSIFICATION_REVIEWED,
    CLINICAL_PERFORMANCE_VALIDATED,
    QUALITY_SYSTEM_RELEASED,
    REGULATORY_AUTHORIZATION_GRANTED,
}

enum class EvidenceResult { PASS, FAIL }

class PromotionEvidenceReceipt(
    val receiptId: String,
    val featureId: String,
    val featureVersion: String,
    val evidenceType: PromotionEvidenceType,
    val result: EvidenceResult,
    val environmentFingerprintSha256: String,
    val protocolOrDatasetSha256: String,
    val completedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    private val signatureSnapshot = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        require(receiptId.isNotBlank())
        require(featureId.isNotBlank())
        require(featureVersion.isNotBlank())
        require(environmentFingerprintSha256.matches(Regex("[a-f0-9]{64}")))
        require(protocolOrDatasetSha256.matches(Regex("[a-f0-9]{64}")))
        require(completedAtEpochMillis > 0L)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > completedAtEpochMillis)
        require(issuerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is PromotionEvidenceReceipt &&
            receiptId == other.receiptId &&
            featureId == other.featureId &&
            featureVersion == other.featureVersion &&
            evidenceType == other.evidenceType &&
            result == other.result &&
            environmentFingerprintSha256 == other.environmentFingerprintSha256 &&
            protocolOrDatasetSha256 == other.protocolOrDatasetSha256 &&
            completedAtEpochMillis == other.completedAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            issuerKeyId == other.issuerKeyId &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * listOf(
        receiptId,
        featureId,
        featureVersion,
        evidenceType,
        result,
        environmentFingerprintSha256,
        protocolOrDatasetSha256,
        completedAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()

    fun copy(
        receiptId: String = this.receiptId,
        featureId: String = this.featureId,
        featureVersion: String = this.featureVersion,
        evidenceType: PromotionEvidenceType = this.evidenceType,
        result: EvidenceResult = this.result,
        environmentFingerprintSha256: String = this.environmentFingerprintSha256,
        protocolOrDatasetSha256: String = this.protocolOrDatasetSha256,
        completedAtEpochMillis: Long = this.completedAtEpochMillis,
        expiresAtEpochMillis: Long? = this.expiresAtEpochMillis,
        issuerKeyId: String = this.issuerKeyId,
        signature: ByteArray = this.signature,
    ) = PromotionEvidenceReceipt(
        receiptId,
        featureId,
        featureVersion,
        evidenceType,
        result,
        environmentFingerprintSha256,
        protocolOrDatasetSha256,
        completedAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
        signature,
    )
}

fun interface PromotionEvidenceVerifier {
    fun verify(receipt: PromotionEvidenceReceipt): Boolean
}

enum class PromotionDenialReason {
    ALLOWED,
    MISSING_EVIDENCE,
    FAILED_EVIDENCE,
    EVIDENCE_NOT_YET_ACTIVE,
    EXPIRED_EVIDENCE,
    INVALID_SIGNATURE,
    FEATURE_VERSION_MISMATCH,
    ENVIRONMENT_MISMATCH,
}

class PromotionDecision(
    val allowed: Boolean,
    val surface: ProductSurface,
    val denialReason: PromotionDenialReason,
    missingEvidence: Set<PromotionEvidenceType>,
    consideredReceiptIds: List<String>,
) {
    val missingEvidence: Set<PromotionEvidenceType> = java.util.Set.copyOf(missingEvidence)
    val consideredReceiptIds: List<String> = java.util.List.copyOf(consideredReceiptIds)
}

/** Exact, non-copyable authority for one evaluated medical feature surface. */
class MedicalPromotionPermit private constructor(
    val featureId: String,
    val featureVersion: String,
    val environmentFingerprintSha256: String,
    val surface: ProductSurface,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    evidenceReceiptIds: List<String>,
) {
    val evidenceReceiptIds: List<String> = java.util.List.copyOf(evidenceReceiptIds)
    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    companion object {
        internal fun issue(
            featureId: String,
            featureVersion: String,
            environmentFingerprintSha256: String,
            surface: ProductSurface,
            issuedAtEpochMillis: Long,
            validUntilEpochMillis: Long,
            evidenceReceiptIds: List<String>,
        ) = MedicalPromotionPermit(
            featureId,
            featureVersion,
            environmentFingerprintSha256,
            surface,
            issuedAtEpochMillis,
            validUntilEpochMillis,
            evidenceReceiptIds,
        )
    }
}

sealed interface MedicalPromotionPermitDecision {
    class Allowed internal constructor(val permit: MedicalPromotionPermit) : MedicalPromotionPermitDecision
    data class Denied(val decision: PromotionDecision) : MedicalPromotionPermitDecision
}

/**
 * Prevents code completion, model enthusiasm or one successful retrospective
 * result from becoming a user-facing or medical claim.
 */
class ResearchPromotionGate(
    private val verifier: PromotionEvidenceVerifier,
    private val maximumMedicalPermitLifetimeMillis: Long = DEFAULT_MEDICAL_PERMIT_LIFETIME_MILLIS,
) {
    init {
        require(maximumMedicalPermitLifetimeMillis in 1L..MAXIMUM_MEDICAL_PERMIT_LIFETIME_MILLIS)
    }

    fun evaluate(
        featureId: String,
        featureVersion: String,
        surface: ProductSurface,
        environmentFingerprintSha256: String,
        evaluatedAtEpochMillis: Long,
        receipts: List<PromotionEvidenceReceipt>,
    ): PromotionDecision {
        require(featureId.isNotBlank())
        require(featureVersion.isNotBlank())
        require(environmentFingerprintSha256.matches(Regex("[a-f0-9]{64}")))
        require(evaluatedAtEpochMillis > 0L)

        val historicalFeatureReceipts = receipts.filter { it.featureId == featureId }
        val versionReceipts = historicalFeatureReceipts.filter { it.featureVersion == featureVersion }
        if (versionReceipts.isEmpty() && historicalFeatureReceipts.isNotEmpty()) {
            return deny(
                surface,
                PromotionDenialReason.FEATURE_VERSION_MISMATCH,
                emptySet(),
                historicalFeatureReceipts,
            )
        }
        val environmentReceipts = versionReceipts.filter {
            it.environmentFingerprintSha256 == environmentFingerprintSha256
        }
        if (environmentReceipts.isEmpty() && versionReceipts.isNotEmpty()) {
            return deny(surface, PromotionDenialReason.ENVIRONMENT_MISMATCH, emptySet(), versionReceipts)
        }
        if (environmentReceipts.any { it.completedAtEpochMillis > evaluatedAtEpochMillis }) {
            return deny(surface, PromotionDenialReason.EVIDENCE_NOT_YET_ACTIVE, emptySet(), environmentReceipts)
        }
        if (environmentReceipts.any { !verifier.verify(it) }) {
            return deny(surface, PromotionDenialReason.INVALID_SIGNATURE, emptySet(), environmentReceipts)
        }
        if (environmentReceipts.any {
                it.expiresAtEpochMillis != null && evaluatedAtEpochMillis >= it.expiresAtEpochMillis
            }
        ) {
            return deny(surface, PromotionDenialReason.EXPIRED_EVIDENCE, emptySet(), environmentReceipts)
        }
        if (environmentReceipts.any { it.result == EvidenceResult.FAIL }) {
            return deny(surface, PromotionDenialReason.FAILED_EVIDENCE, emptySet(), environmentReceipts)
        }

        val required = requirements(surface)
        val passed = environmentReceipts
            .filter { it.result == EvidenceResult.PASS }
            .mapTo(mutableSetOf()) { it.evidenceType }
        val missing = required - passed
        if (missing.isNotEmpty()) {
            return deny(surface, PromotionDenialReason.MISSING_EVIDENCE, missing, environmentReceipts)
        }
        return PromotionDecision(
            allowed = true,
            surface = surface,
            denialReason = PromotionDenialReason.ALLOWED,
            missingEvidence = emptySet(),
            consideredReceiptIds = environmentReceipts.map { it.receiptId }.sorted(),
        )
    }

    fun issueMedicalPermit(
        featureId: String,
        featureVersion: String,
        environmentFingerprintSha256: String,
        evaluatedAtEpochMillis: Long,
        receipts: List<PromotionEvidenceReceipt>,
    ): MedicalPromotionPermitDecision {
        val decision = evaluate(
            featureId = featureId,
            featureVersion = featureVersion,
            surface = ProductSurface.MEDICAL_INTENDED_USE,
            environmentFingerprintSha256 = environmentFingerprintSha256,
            evaluatedAtEpochMillis = evaluatedAtEpochMillis,
            receipts = receipts,
        )
        if (!decision.allowed || decision.denialReason != PromotionDenialReason.ALLOWED) {
            return MedicalPromotionPermitDecision.Denied(decision)
        }
        val considered = receipts.filter { it.receiptId in decision.consideredReceiptIds }
        val evidenceValidUntil = considered.minOfOrNull { it.expiresAtEpochMillis ?: Long.MAX_VALUE }
            ?: return MedicalPromotionPermitDecision.Denied(
                deny(
                    surface = ProductSurface.MEDICAL_INTENDED_USE,
                    reason = PromotionDenialReason.MISSING_EVIDENCE,
                    missing = requirements(ProductSurface.MEDICAL_INTENDED_USE),
                    receipts = emptyList(),
                ),
            )
        val validUntil = minOf(
            evidenceValidUntil,
            saturatedPromotionAdd(evaluatedAtEpochMillis, maximumMedicalPermitLifetimeMillis),
        )
        return MedicalPromotionPermitDecision.Allowed(
            MedicalPromotionPermit.issue(
                featureId = featureId,
                featureVersion = featureVersion,
                environmentFingerprintSha256 = environmentFingerprintSha256,
                surface = ProductSurface.MEDICAL_INTENDED_USE,
                issuedAtEpochMillis = evaluatedAtEpochMillis,
                validUntilEpochMillis = validUntil,
                evidenceReceiptIds = decision.consideredReceiptIds,
            ),
        )
    }

    private fun deny(
        surface: ProductSurface,
        reason: PromotionDenialReason,
        missing: Set<PromotionEvidenceType>,
        receipts: List<PromotionEvidenceReceipt>,
    ) = PromotionDecision(
        allowed = false,
        surface = surface,
        denialReason = reason,
        missingEvidence = missing,
        consideredReceiptIds = receipts.map { it.receiptId }.sorted(),
    )

    private fun requirements(surface: ProductSurface): Set<PromotionEvidenceType> = when (surface) {
        ProductSurface.PRIVATE_SHADOW -> setOf(
            PromotionEvidenceType.SPECIFICATION_FROZEN,
            PromotionEvidenceType.AUTOMATED_TESTS_PASSED,
            PromotionEvidenceType.DATA_QUALITY_AND_MISSINGNESS_VALIDATED,
        )

        ProductSurface.PRIVATE_VISIBLE_WELLNESS -> requirements(ProductSurface.PRIVATE_SHADOW) + setOf(
            PromotionEvidenceType.EXACT_DEVICE_FIRMWARE_VALIDATED,
            PromotionEvidenceType.REFERENCE_DEVICE_AGREEMENT,
            PromotionEvidenceType.CHRONOLOGICAL_HOLDOUT_PASSED,
            PromotionEvidenceType.PROSPECTIVE_CALIBRATION_PASSED,
            PromotionEvidenceType.FALSE_ALERT_BUDGET_PASSED,
            PromotionEvidenceType.HUMAN_FACTORS_REVIEWED,
            PromotionEvidenceType.CLINICAL_SAFETY_REVIEWED,
        )

        ProductSurface.PUBLIC_WELLNESS -> requirements(ProductSurface.PRIVATE_VISIBLE_WELLNESS) + setOf(
            PromotionEvidenceType.EXTERNAL_COHORT_REPLICATED,
            PromotionEvidenceType.SUBGROUP_FAIRNESS_REVIEWED,
            PromotionEvidenceType.PRIVACY_SECURITY_REVIEWED,
            PromotionEvidenceType.REGULATORY_CLASSIFICATION_REVIEWED,
        )

        ProductSurface.MEDICAL_INTENDED_USE -> requirements(ProductSurface.PUBLIC_WELLNESS) + setOf(
            PromotionEvidenceType.CLINICAL_PERFORMANCE_VALIDATED,
            PromotionEvidenceType.QUALITY_SYSTEM_RELEASED,
            PromotionEvidenceType.REGULATORY_AUTHORIZATION_GRANTED,
        )
    }
}

private fun saturatedPromotionAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private const val DEFAULT_MEDICAL_PERMIT_LIFETIME_MILLIS = 60_000L
private const val MAXIMUM_MEDICAL_PERMIT_LIFETIME_MILLIS = 5 * 60_000L
