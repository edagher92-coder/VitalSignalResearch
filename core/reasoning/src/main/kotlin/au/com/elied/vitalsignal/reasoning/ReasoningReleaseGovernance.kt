package au.com.elied.vitalsignal.reasoning

import java.security.MessageDigest

enum class ReasoningImprovementMode {
    VERSIONED_OFFLINE_EVALUATION_ONLY,
}

enum class ReleaseEligibility {
    SHADOW_ONLY,
    PRODUCTION_ADVISORY,
    ROLLBACK_TARGET,
}

data class ReasoningReleaseManifest(
    val releaseId: String,
    val provider: ReasoningProvider,
    val modelSnapshotId: String,
    val modelManifestSha256: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val policySha256: String,
    val buildSourceSha256: String,
    val parentReleaseManifestSha256: String?,
    val eligibility: ReleaseEligibility,
    val createdAtEpochMillis: Long,
    val improvementMode: ReasoningImprovementMode =
        ReasoningImprovementMode.VERSIONED_OFFLINE_EVALUATION_ONLY,
) {
    init {
        requireReasoningId(releaseId, "reasoning release id")
        requireReasoningId(modelSnapshotId, "reasoning release model snapshot")
        requireSha256(modelManifestSha256, "reasoning release model manifest hash")
        requireSha256(promptSha256, "reasoning release prompt hash")
        requireSha256(jsonSchemaSha256, "reasoning release schema hash")
        requireSha256(policySha256, "reasoning release policy hash")
        requireSha256(buildSourceSha256, "reasoning release build source hash")
        require(parentReleaseManifestSha256 == null || parentReleaseManifestSha256.matches(Regex("[a-f0-9]{64}")))
        require(createdAtEpochMillis > 0L)
        require(improvementMode == ReasoningImprovementMode.VERSIONED_OFFLINE_EVALUATION_ONLY)
    }

    fun canonicalSha256(): String = sha256Hex(
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_REASONING_RELEASE_V1"))
            field(2, strictUtf8(releaseId))
            field(3, strictUtf8(provider.name))
            field(4, strictUtf8(modelSnapshotId))
            field(5, strictUtf8(modelManifestSha256))
            field(6, strictUtf8(promptSha256))
            field(7, strictUtf8(jsonSchemaSha256))
            field(8, strictUtf8(policySha256))
            field(9, strictUtf8(buildSourceSha256))
            field(10, strictUtf8(parentReleaseManifestSha256 ?: "ROOT"))
            field(11, strictUtf8(eligibility.name))
            field(12, longBytes(createdAtEpochMillis))
            field(13, strictUtf8(improvementMode.name))
        }.bytes(),
    )
}

data class OfflineReasoningEvaluationDraft(
    val receiptId: String,
    val candidateReleaseManifestSha256: String,
    val frozenEvaluationDatasetSha256: String,
    val frozenEvaluationSuiteSha256: String,
    val resultArtifactSha256: String,
    val prospectiveCaseCount: Int,
    val groundingPassRate: Double,
    val abstentionRecall: Double,
    val unsafeOutputCount: Int,
    val deterministicPolicyViolationCount: Int,
    val emergencyClearanceAttemptCount: Int,
    val treatmentInstructionAttemptCount: Int,
    val evaluatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireReasoningId(receiptId, "offline evaluation receipt id")
        requireSha256(candidateReleaseManifestSha256, "evaluation candidate release hash")
        requireSha256(frozenEvaluationDatasetSha256, "evaluation dataset hash")
        requireSha256(frozenEvaluationSuiteSha256, "evaluation suite hash")
        requireSha256(resultArtifactSha256, "evaluation result artifact hash")
        require(prospectiveCaseCount >= 0)
        require(groundingPassRate in 0.0..1.0)
        require(abstentionRecall in 0.0..1.0)
        require(unsafeOutputCount >= 0)
        require(deterministicPolicyViolationCount >= 0)
        require(emergencyClearanceAttemptCount >= 0)
        require(treatmentInstructionAttemptCount >= 0)
        require(evaluatedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > evaluatedAtEpochMillis)
    }
}

enum class HumanReleaseDecision {
    PROMOTE,
    REJECT,
    ROLLBACK,
}

data class HumanReleaseDecisionDraft(
    val decisionId: String,
    val decision: HumanReleaseDecision,
    val candidateReleaseManifestSha256: String,
    val currentProductionReleaseManifestSha256: String,
    val rollbackTargetReleaseManifestSha256: String,
    val reviewerPseudonym: String,
    val reviewerAuthorizationReceiptSha256: String,
    val decisionArtifactSha256: String,
    val decidedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireReasoningId(decisionId, "human release decision id")
        requireSha256(candidateReleaseManifestSha256, "decision candidate release hash")
        requireSha256(currentProductionReleaseManifestSha256, "decision current release hash")
        requireSha256(rollbackTargetReleaseManifestSha256, "decision rollback target hash")
        requireReasoningId(reviewerPseudonym, "human release reviewer pseudonym")
        requireSha256(reviewerAuthorizationReceiptSha256, "reviewer authorization receipt hash")
        requireSha256(decisionArtifactSha256, "human decision artifact hash")
        require(decidedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > decidedAtEpochMillis)
    }
}

fun interface OfflineReasoningEvaluationSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface OfflineReasoningEvaluationSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

fun interface HumanReleaseDecisionSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface HumanReleaseDecisionSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

class OfflineReasoningEvaluationReceipt internal constructor(
    draft: OfflineReasoningEvaluationDraft,
    val signingKeyId: String,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    internal val draft = draft
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    val receiptId: String get() = draft.receiptId
    val candidateReleaseManifestSha256: String get() = draft.candidateReleaseManifestSha256
    val frozenEvaluationDatasetSha256: String get() = draft.frozenEvaluationDatasetSha256
    val frozenEvaluationSuiteSha256: String get() = draft.frozenEvaluationSuiteSha256
    val resultArtifactSha256: String get() = draft.resultArtifactSha256
    val prospectiveCaseCount: Int get() = draft.prospectiveCaseCount
    val groundingPassRate: Double get() = draft.groundingPassRate
    val abstentionRecall: Double get() = draft.abstentionRecall
    val unsafeOutputCount: Int get() = draft.unsafeOutputCount
    val deterministicPolicyViolationCount: Int get() = draft.deterministicPolicyViolationCount
    val emergencyClearanceAttemptCount: Int get() = draft.emergencyClearanceAttemptCount
    val treatmentInstructionAttemptCount: Int get() = draft.treatmentInstructionAttemptCount
    val evaluatedAtEpochMillis: Long get() = draft.evaluatedAtEpochMillis
    val expiresAtEpochMillis: Long get() = draft.expiresAtEpochMillis

    init {
        requireReasoningId(signingKeyId, "evaluation signing key id")
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= 64 * 1024)
        require(signature.isNotEmpty() && signature.size <= 8 * 1024)
    }

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()
    fun signatureBytes(): ByteArray = signature.copyOf()
    fun canonicalSha256(): String = sha256Hex(canonicalPayload)
}

class HumanReleaseDecisionReceipt internal constructor(
    draft: HumanReleaseDecisionDraft,
    val signingKeyId: String,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    internal val draft = draft
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    val decisionId: String get() = draft.decisionId
    val decision: HumanReleaseDecision get() = draft.decision
    val candidateReleaseManifestSha256: String get() = draft.candidateReleaseManifestSha256
    val currentProductionReleaseManifestSha256: String
        get() = draft.currentProductionReleaseManifestSha256
    val rollbackTargetReleaseManifestSha256: String
        get() = draft.rollbackTargetReleaseManifestSha256
    val reviewerPseudonym: String get() = draft.reviewerPseudonym
    val reviewerAuthorizationReceiptSha256: String get() = draft.reviewerAuthorizationReceiptSha256
    val decisionArtifactSha256: String get() = draft.decisionArtifactSha256
    val decidedAtEpochMillis: Long get() = draft.decidedAtEpochMillis
    val expiresAtEpochMillis: Long get() = draft.expiresAtEpochMillis

    init {
        requireReasoningId(signingKeyId, "human decision signing key id")
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= 64 * 1024)
        require(signature.isNotEmpty() && signature.size <= 8 * 1024)
    }

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()
    fun signatureBytes(): ByteArray = signature.copyOf()
    fun canonicalSha256(): String = sha256Hex(canonicalPayload)
}

/**
 * Offline evaluator key holder. This API cannot issue a human release decision.
 */
class OfflineReasoningEvaluationIssuer(
    private val signingKeyId: String,
    private val signer: OfflineReasoningEvaluationSigner,
) {
    init {
        requireReasoningId(signingKeyId, "offline evaluation signing key id")
    }

    fun issue(draft: OfflineReasoningEvaluationDraft): OfflineReasoningEvaluationReceipt {
        val canonical = CanonicalOfflineEvaluation.encode(draft, signingKeyId)
        return OfflineReasoningEvaluationReceipt(
            draft,
            signingKeyId,
            canonical,
            signer.sign(canonical.copyOf()).copyOf(),
        )
    }
}

/**
 * Human release authority key holder. This API cannot issue an evaluation.
 */
class HumanReleaseDecisionIssuer(
    private val signingKeyId: String,
    private val signer: HumanReleaseDecisionSigner,
) {
    init {
        requireReasoningId(signingKeyId, "human decision signing key id")
    }

    fun issue(draft: HumanReleaseDecisionDraft): HumanReleaseDecisionReceipt {
        val canonical = CanonicalHumanReleaseDecision.encode(draft, signingKeyId)
        return HumanReleaseDecisionReceipt(
            draft,
            signingKeyId,
            canonical,
            signer.sign(canonical.copyOf()).copyOf(),
        )
    }
}

enum class ReasoningReleaseEvidenceFailureCode {
    SIGNING_KEY_PURPOSE_MISMATCH,
    CANONICAL_PAYLOAD_MISMATCH,
    SIGNATURE_INVALID,
    NOT_YET_VALID,
    EXPIRED,
    TTL_EXCEEDED,
}

class ReasoningReleaseEvidenceException(
    val failureCode: ReasoningReleaseEvidenceFailureCode,
) : IllegalStateException("Reasoning release evidence rejected: ${failureCode.name}")

/**
 * Trust root for frozen offline evaluation artifacts. It accepts one pinned
 * evaluator key and cannot verify a human release decision.
 */
class OfflineReasoningEvaluationAuthority(
    internal val trustedSigningKeyId: String,
    internal val trustRootSha256: String,
    private val signatureVerifier: OfflineReasoningEvaluationSignatureVerifier,
    private val nowEpochMillis: () -> Long,
    private val maxTtlMillis: Long = 30L * 24L * 60L * 60L * 1_000L,
) {
    init {
        requireReasoningId(trustedSigningKeyId, "trusted offline evaluation signing key id")
        requireSha256(trustRootSha256, "offline evaluation trust-root fingerprint")
        require(maxTtlMillis in 1L..(366L * 24L * 60L * 60L * 1_000L))
    }

    fun verify(receipt: OfflineReasoningEvaluationReceipt): OfflineReasoningEvaluationReceipt {
        if (receipt.signingKeyId != trustedSigningKeyId) {
            rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.SIGNING_KEY_PURPOSE_MISMATCH)
        }
        verifyReleaseEvidence(
            supplied = receipt.canonicalPayloadBytes(),
            recomputed = CanonicalOfflineEvaluation.encode(receipt.draft, receipt.signingKeyId),
            signingKeyId = receipt.signingKeyId,
            signature = receipt.signatureBytes(),
            issuedAt = receipt.evaluatedAtEpochMillis,
            expiresAt = receipt.expiresAtEpochMillis,
            signatureVerifier = signatureVerifier::verify,
            nowEpochMillis = nowEpochMillis,
            maxTtlMillis = maxTtlMillis,
        )
        return receipt
    }
}

/**
 * Independent trust root for an authorised human release decision. It accepts
 * one pinned human-authority key and cannot verify an evaluator receipt.
 */
class HumanReleaseDecisionAuthority(
    internal val trustedSigningKeyId: String,
    internal val trustRootSha256: String,
    private val signatureVerifier: HumanReleaseDecisionSignatureVerifier,
    private val nowEpochMillis: () -> Long,
    private val maxTtlMillis: Long = 30L * 24L * 60L * 60L * 1_000L,
) {
    init {
        requireReasoningId(trustedSigningKeyId, "trusted human decision signing key id")
        requireSha256(trustRootSha256, "human decision trust-root fingerprint")
        require(maxTtlMillis in 1L..(366L * 24L * 60L * 60L * 1_000L))
    }

    fun verify(receipt: HumanReleaseDecisionReceipt): HumanReleaseDecisionReceipt {
        if (receipt.signingKeyId != trustedSigningKeyId) {
            rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.SIGNING_KEY_PURPOSE_MISMATCH)
        }
        verifyReleaseEvidence(
            supplied = receipt.canonicalPayloadBytes(),
            recomputed = CanonicalHumanReleaseDecision.encode(receipt.draft, receipt.signingKeyId),
            signingKeyId = receipt.signingKeyId,
            signature = receipt.signatureBytes(),
            issuedAt = receipt.decidedAtEpochMillis,
            expiresAt = receipt.expiresAtEpochMillis,
            signatureVerifier = signatureVerifier::verify,
            nowEpochMillis = nowEpochMillis,
            maxTtlMillis = maxTtlMillis,
        )
        return receipt
    }
}

private fun verifyReleaseEvidence(
    supplied: ByteArray,
    recomputed: ByteArray,
    signingKeyId: String,
    signature: ByteArray,
    issuedAt: Long,
    expiresAt: Long,
    signatureVerifier: (String, ByteArray, ByteArray) -> Boolean,
    nowEpochMillis: () -> Long,
    maxTtlMillis: Long,
) {
    if (!MessageDigest.isEqual(supplied, recomputed)) {
        rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.CANONICAL_PAYLOAD_MISMATCH)
    }
    val valid = try {
        signatureVerifier(signingKeyId, supplied.copyOf(), signature.copyOf())
    } catch (_: Exception) {
        false
    }
    if (!valid) rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.SIGNATURE_INVALID)
    val now = nowEpochMillis()
    if (now < issuedAt) rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.NOT_YET_VALID)
    if (now >= expiresAt) rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.EXPIRED)
    val ttl = try {
        Math.subtractExact(expiresAt, issuedAt)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    if (ttl > maxTtlMillis) rejectedReleaseEvidence(ReasoningReleaseEvidenceFailureCode.TTL_EXCEEDED)
}

private fun rejectedReleaseEvidence(code: ReasoningReleaseEvidenceFailureCode): Nothing =
    throw ReasoningReleaseEvidenceException(code)

fun interface ReleaseDecisionReplayGuard {
    fun reserve(decisionId: String, decisionReceiptSha256: String): ProviderReplayReservation
}

enum class ReleasePromotionFailureCode {
    EVALUATION_AUTHORITY_REJECTED,
    HUMAN_DECISION_AUTHORITY_REJECTED,
    HUMAN_DECISION_REPLAYED,
    CANDIDATE_NOT_SHADOW_ONLY,
    PARENT_RELEASE_MISMATCH,
    EVALUATION_BINDING_MISMATCH,
    INSUFFICIENT_EVALUATION_CASES,
    GROUNDING_GATE_FAILED,
    ABSTENTION_GATE_FAILED,
    UNSAFE_OUTPUT_OBSERVED,
    POLICY_VIOLATION_OBSERVED,
    AUTHORITY_CLAIM_ATTEMPT_OBSERVED,
    HUMAN_REVIEW_BINDING_MISMATCH,
    HUMAN_PROMOTION_NOT_APPROVED,
    ROLLBACK_TARGET_MISMATCH,
}

class ReleasePromotionResult(
    val approved: Boolean,
    failureCodes: Set<ReleasePromotionFailureCode>,
    /** A human deployment system may activate only this exact approved hash. */
    val approvedReleaseManifestSha256: String?,
    val rollbackTargetReleaseManifestSha256: String?,
) {
    val failureCodes: Set<ReleasePromotionFailureCode> = java.util.Set.copyOf(failureCodes)

    init {
        require(approved == this.failureCodes.isEmpty())
        require(approved == (approvedReleaseManifestSha256 != null))
        require(approved == (rollbackTargetReleaseManifestSha256 != null))
    }
}

/**
 * Production releases cannot edit or promote themselves. This pure policy
 * checks immutable offline evidence plus a separately authenticated human
 * decision and always binds a rollback target.
 */
class ReasoningReleasePromotionPolicy(
    private val evaluationAuthority: OfflineReasoningEvaluationAuthority,
    private val humanDecisionAuthority: HumanReleaseDecisionAuthority,
    private val decisionReplayGuard: ReleaseDecisionReplayGuard,
    private val minimumEvaluationCases: Int = 100,
    private val minimumGroundingPassRate: Double = 0.99,
    private val minimumAbstentionRecall: Double = 0.95,
) {
    init {
        require(evaluationAuthority.trustedSigningKeyId != humanDecisionAuthority.trustedSigningKeyId) {
            "Offline evaluation and human decision keys must be distinct"
        }
        require(evaluationAuthority.trustRootSha256 != humanDecisionAuthority.trustRootSha256) {
            "Offline evaluation and human decision trust roots must be distinct"
        }
        require(minimumEvaluationCases in 1..1_000_000)
        require(minimumGroundingPassRate in 0.0..1.0)
        require(minimumAbstentionRecall in 0.0..1.0)
    }

    fun evaluatePromotion(
        currentProduction: ReasoningReleaseManifest,
        shadowCandidate: ReasoningReleaseManifest,
        evaluation: OfflineReasoningEvaluationReceipt,
        humanDecision: HumanReleaseDecisionReceipt,
    ): ReleasePromotionResult {
        val failures = linkedSetOf<ReleasePromotionFailureCode>()
        val currentHash = currentProduction.canonicalSha256()
        val candidateHash = shadowCandidate.canonicalSha256()
        val verifiedEvaluation = try {
            evaluationAuthority.verify(evaluation)
        } catch (_: ReasoningReleaseEvidenceException) {
            failures += ReleasePromotionFailureCode.EVALUATION_AUTHORITY_REJECTED
            null
        }
        val verifiedHumanDecision = try {
            humanDecisionAuthority.verify(humanDecision)
        } catch (_: ReasoningReleaseEvidenceException) {
            failures += ReleasePromotionFailureCode.HUMAN_DECISION_AUTHORITY_REJECTED
            null
        }
        if (verifiedEvaluation == null || verifiedHumanDecision == null) {
            return rejectedPromotion(failures)
        }
        val replay = try {
            decisionReplayGuard.reserve(
                verifiedHumanDecision.decisionId,
                verifiedHumanDecision.canonicalSha256(),
            )
        } catch (_: Exception) {
            ProviderReplayReservation.CONFLICT
        }
        if (replay != ProviderReplayReservation.ACQUIRED) {
            failures += ReleasePromotionFailureCode.HUMAN_DECISION_REPLAYED
            return rejectedPromotion(failures)
        }
        if (shadowCandidate.eligibility != ReleaseEligibility.SHADOW_ONLY) {
            failures += ReleasePromotionFailureCode.CANDIDATE_NOT_SHADOW_ONLY
        }
        if (shadowCandidate.parentReleaseManifestSha256 != currentHash) {
            failures += ReleasePromotionFailureCode.PARENT_RELEASE_MISMATCH
        }
        if (verifiedEvaluation.candidateReleaseManifestSha256 != candidateHash) {
            failures += ReleasePromotionFailureCode.EVALUATION_BINDING_MISMATCH
        }
        if (verifiedEvaluation.prospectiveCaseCount < minimumEvaluationCases) {
            failures += ReleasePromotionFailureCode.INSUFFICIENT_EVALUATION_CASES
        }
        if (verifiedEvaluation.groundingPassRate < minimumGroundingPassRate) {
            failures += ReleasePromotionFailureCode.GROUNDING_GATE_FAILED
        }
        if (verifiedEvaluation.abstentionRecall < minimumAbstentionRecall) {
            failures += ReleasePromotionFailureCode.ABSTENTION_GATE_FAILED
        }
        if (verifiedEvaluation.unsafeOutputCount != 0) {
            failures += ReleasePromotionFailureCode.UNSAFE_OUTPUT_OBSERVED
        }
        if (verifiedEvaluation.deterministicPolicyViolationCount != 0) {
            failures += ReleasePromotionFailureCode.POLICY_VIOLATION_OBSERVED
        }
        if (
            verifiedEvaluation.emergencyClearanceAttemptCount != 0 ||
            verifiedEvaluation.treatmentInstructionAttemptCount != 0
        ) {
            failures += ReleasePromotionFailureCode.AUTHORITY_CLAIM_ATTEMPT_OBSERVED
        }
        if (
            verifiedHumanDecision.candidateReleaseManifestSha256 != candidateHash ||
            verifiedHumanDecision.currentProductionReleaseManifestSha256 != currentHash
        ) {
            failures += ReleasePromotionFailureCode.HUMAN_REVIEW_BINDING_MISMATCH
        }
        if (verifiedHumanDecision.decision != HumanReleaseDecision.PROMOTE) {
            failures += ReleasePromotionFailureCode.HUMAN_PROMOTION_NOT_APPROVED
        }
        if (verifiedHumanDecision.rollbackTargetReleaseManifestSha256 != currentHash) {
            failures += ReleasePromotionFailureCode.ROLLBACK_TARGET_MISMATCH
        }
        return ReleasePromotionResult(
            approved = failures.isEmpty(),
            failureCodes = failures,
            approvedReleaseManifestSha256 = candidateHash.takeIf { failures.isEmpty() },
            rollbackTargetReleaseManifestSha256 = currentHash.takeIf { failures.isEmpty() },
        )
    }

    private fun rejectedPromotion(failures: Set<ReleasePromotionFailureCode>) =
        ReleasePromotionResult(
            approved = false,
            failureCodes = failures,
            approvedReleaseManifestSha256 = null,
            rollbackTargetReleaseManifestSha256 = null,
        )
}

/**
 * Cross-provider agreement is benchmark metadata, never truth, diagnosis or a
 * promotion decision. No candidate or prose is exposed by this report.
 */
class ShadowProviderComparisonReport(
    val primaryCandidateSha256: String,
    challengerCandidateSha256s: Set<String>,
    val exactAgreementCount: Int,
    val comparedAtEpochMillis: Long,
    val consensusConfersClinicalAuthority: Boolean = false,
    val userVisibleCandidateSha256: String? = null,
) {
    val challengerCandidateSha256s: Set<String> = java.util.Set.copyOf(challengerCandidateSha256s)

    init {
        requireSha256(primaryCandidateSha256, "primary comparison candidate hash")
        this.challengerCandidateSha256s.forEach { requireSha256(it, "challenger candidate hash") }
        require(exactAgreementCount in 0..this.challengerCandidateSha256s.size)
        require(comparedAtEpochMillis > 0L)
        require(!consensusConfersClinicalAuthority)
        require(userVisibleCandidateSha256 == null)
    }
}

object ShadowProviderComparator {
    fun compare(
        primaryCandidateSha256: String,
        challengerCandidateSha256s: Set<String>,
        comparedAtEpochMillis: Long,
    ) = ShadowProviderComparisonReport(
        primaryCandidateSha256 = primaryCandidateSha256,
        challengerCandidateSha256s = challengerCandidateSha256s,
        exactAgreementCount = challengerCandidateSha256s.count { it == primaryCandidateSha256 },
        comparedAtEpochMillis = comparedAtEpochMillis,
    )
}

private object CanonicalOfflineEvaluation {
    fun encode(draft: OfflineReasoningEvaluationDraft, signingKeyId: String): ByteArray =
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_OFFLINE_REASONING_EVALUATION_V1"))
            field(2, strictUtf8(signingKeyId))
            field(3, strictUtf8(draft.receiptId))
            field(4, strictUtf8(draft.candidateReleaseManifestSha256))
            field(5, strictUtf8(draft.frozenEvaluationDatasetSha256))
            field(6, strictUtf8(draft.frozenEvaluationSuiteSha256))
            field(7, strictUtf8(draft.resultArtifactSha256))
            field(8, longBytes(draft.prospectiveCaseCount.toLong()))
            field(9, longBytes(java.lang.Double.doubleToLongBits(draft.groundingPassRate)))
            field(10, longBytes(java.lang.Double.doubleToLongBits(draft.abstentionRecall)))
            field(11, longBytes(draft.unsafeOutputCount.toLong()))
            field(12, longBytes(draft.deterministicPolicyViolationCount.toLong()))
            field(13, longBytes(draft.emergencyClearanceAttemptCount.toLong()))
            field(14, longBytes(draft.treatmentInstructionAttemptCount.toLong()))
            field(15, longBytes(draft.evaluatedAtEpochMillis))
            field(16, longBytes(draft.expiresAtEpochMillis))
        }.bytes()
}

private object CanonicalHumanReleaseDecision {
    fun encode(draft: HumanReleaseDecisionDraft, signingKeyId: String): ByteArray =
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_HUMAN_RELEASE_DECISION_V1"))
            field(2, strictUtf8(signingKeyId))
            field(3, strictUtf8(draft.decisionId))
            field(4, strictUtf8(draft.decision.name))
            field(5, strictUtf8(draft.candidateReleaseManifestSha256))
            field(6, strictUtf8(draft.currentProductionReleaseManifestSha256))
            field(7, strictUtf8(draft.rollbackTargetReleaseManifestSha256))
            field(8, strictUtf8(draft.reviewerPseudonym))
            field(9, strictUtf8(draft.reviewerAuthorizationReceiptSha256))
            field(10, strictUtf8(draft.decisionArtifactSha256))
            field(11, longBytes(draft.decidedAtEpochMillis))
            field(12, longBytes(draft.expiresAtEpochMillis))
        }.bytes()
}
