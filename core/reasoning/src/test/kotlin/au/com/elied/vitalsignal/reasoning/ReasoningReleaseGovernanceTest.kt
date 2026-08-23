package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ReasoningReleaseGovernanceTest {
    @Test
    fun releaseAndShadowComparisonCollectionsAreImmutableSnapshots() {
        val failures = mutableSetOf(ReleasePromotionFailureCode.HUMAN_PROMOTION_NOT_APPROVED)
        val challengers = mutableSetOf("a".repeat(64))
        val result = ReleasePromotionResult(
            approved = false,
            failureCodes = failures,
            approvedReleaseManifestSha256 = null,
            rollbackTargetReleaseManifestSha256 = null,
        )
        val comparison = ShadowProviderComparator.compare(
            primaryCandidateSha256 = "b".repeat(64),
            challengerCandidateSha256s = challengers,
            comparedAtEpochMillis = 1L,
        )

        failures += ReleasePromotionFailureCode.UNSAFE_OUTPUT_OBSERVED
        challengers += "c".repeat(64)
        assertEquals(
            setOf(ReleasePromotionFailureCode.HUMAN_PROMOTION_NOT_APPROVED),
            result.failureCodes,
        )
        assertEquals(setOf("a".repeat(64)), comparison.challengerCandidateSha256s)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.failureCodes as MutableSet<ReleasePromotionFailureCode>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (comparison.challengerCandidateSha256s as MutableSet<String>).clear()
        }
    }

    @Test
    fun frozenOfflineEvaluationAndHumanDecisionCanApproveExactShadowRelease() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val evaluation = passingEvaluation(candidate)
        val human = approvingHumanDecision(current, candidate)

        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            evaluation,
            human,
        )

        assertTrue(result.approved)
        assertEquals(candidate.canonicalSha256(), result.approvedReleaseManifestSha256)
        assertEquals(current.canonicalSha256(), result.rollbackTargetReleaseManifestSha256)
    }

    @Test
    fun productionCannotSelfPromoteWithoutHumanApproval() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val rejected = issueDecision(
            approvingHumanDecision(current, candidate).draft.copy(
                decision = HumanReleaseDecision.REJECT,
            ),
        )

        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            passingEvaluation(candidate),
            rejected,
        )

        assertFalse(result.approved)
        assertTrue(ReleasePromotionFailureCode.HUMAN_PROMOTION_NOT_APPROVED in result.failureCodes)
        assertNull(result.approvedReleaseManifestSha256)
    }

    @Test
    fun unsafeOrAuthoritySeekingCandidateCannotPromote() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val unsafe = issueEvaluation(
            passingEvaluation(candidate).draft.copy(
                unsafeOutputCount = 1,
                deterministicPolicyViolationCount = 2,
                emergencyClearanceAttemptCount = 1,
                treatmentInstructionAttemptCount = 1,
            ),
        )

        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            unsafe,
            approvingHumanDecision(current, candidate),
        )

        assertFalse(result.approved)
        assertTrue(ReleasePromotionFailureCode.UNSAFE_OUTPUT_OBSERVED in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.POLICY_VIOLATION_OBSERVED in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.AUTHORITY_CLAIM_ATTEMPT_OBSERVED in result.failureCodes)
    }

    @Test
    fun mutableProductionCandidateAndWrongParentFailClosed() {
        val current = currentRelease()
        val candidate = candidateRelease(current).copy(
            eligibility = ReleaseEligibility.PRODUCTION_ADVISORY,
            parentReleaseManifestSha256 = "0".repeat(64),
        )
        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            passingEvaluation(candidate),
            approvingHumanDecision(current, candidate),
        )

        assertTrue(ReleasePromotionFailureCode.CANDIDATE_NOT_SHADOW_ONLY in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.PARENT_RELEASE_MISMATCH in result.failureCodes)
    }

    @Test
    fun evaluationAndHumanReceiptsAreContentAddressBound() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            issueEvaluation(
                passingEvaluation(candidate).draft.copy(
                    candidateReleaseManifestSha256 = "1".repeat(64),
                ),
            ),
            issueDecision(
                approvingHumanDecision(current, candidate).draft.copy(
                    candidateReleaseManifestSha256 = "2".repeat(64),
                    rollbackTargetReleaseManifestSha256 = "3".repeat(64),
                ),
            ),
        )

        assertTrue(ReleasePromotionFailureCode.EVALUATION_BINDING_MISMATCH in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.HUMAN_REVIEW_BINDING_MISMATCH in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.ROLLBACK_TARGET_MISMATCH in result.failureCodes)
    }

    @Test
    fun providerConsensusIsBenchmarkMetadataAndNeverTruth() {
        val primary = "a".repeat(64)
        val report = ShadowProviderComparator.compare(
            primaryCandidateSha256 = primary,
            challengerCandidateSha256s = setOf(primary, "b".repeat(64), "c".repeat(64)),
            comparedAtEpochMillis = 10_000L,
        )

        assertEquals(1, report.exactAgreementCount)
        assertFalse(report.consensusConfersClinicalAuthority)
        assertNull(report.userVisibleCandidateSha256)
    }

    private fun currentRelease() = ReasoningReleaseManifest(
        releaseId = "release-current-v1",
        provider = ReasoningProvider.OLLAMA_LOCAL,
        modelSnapshotId = "model-current:q4",
        modelManifestSha256 = "1".repeat(64),
        promptSha256 = "2".repeat(64),
        jsonSchemaSha256 = "3".repeat(64),
        policySha256 = "4".repeat(64),
        buildSourceSha256 = "5".repeat(64),
        parentReleaseManifestSha256 = null,
        eligibility = ReleaseEligibility.PRODUCTION_ADVISORY,
        createdAtEpochMillis = 1_000L,
    )

    private fun candidateRelease(current: ReasoningReleaseManifest) = ReasoningReleaseManifest(
        releaseId = "release-candidate-v2",
        provider = ReasoningProvider.OPENAI_RESPONSES,
        modelSnapshotId = "model-candidate-2026-08-01",
        modelManifestSha256 = "6".repeat(64),
        promptSha256 = "7".repeat(64),
        jsonSchemaSha256 = "8".repeat(64),
        policySha256 = "9".repeat(64),
        buildSourceSha256 = "a".repeat(64),
        parentReleaseManifestSha256 = current.canonicalSha256(),
        eligibility = ReleaseEligibility.SHADOW_ONLY,
        createdAtEpochMillis = 2_000L,
    )

    @Test
    fun forgedEvaluationAndHumanDecisionCannotPromote() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val forgedEvaluation = evaluationIssuer(
            key = "wrong-evaluation-key".toByteArray(),
        ).issue(passingEvaluation(candidate).draft)
        val forgedHuman = humanDecisionIssuer(
            key = "wrong-human-decision-key".toByteArray(),
        ).issue(approvingHumanDecision(current, candidate).draft)
        val result = promotionPolicy().evaluatePromotion(current, candidate, forgedEvaluation, forgedHuman)

        assertTrue(ReleasePromotionFailureCode.EVALUATION_AUTHORITY_REJECTED in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.HUMAN_DECISION_AUTHORITY_REJECTED in result.failureCodes)
        assertFalse(result.approved)
    }

    @Test
    fun evaluatorAndHumanKeysCannotCrossSignForEachOthersPurpose() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val evaluationSignedByHumanKey = evaluationIssuer(
            keyId = HUMAN_DECISION_KEY_ID,
            key = HUMAN_DECISION_KEY,
        ).issue(passingEvaluation(candidate).draft)
        val humanDecisionSignedByEvaluatorKey = humanDecisionIssuer(
            keyId = EVALUATION_KEY_ID,
            key = EVALUATION_KEY,
        ).issue(approvingHumanDecision(current, candidate).draft)

        var evaluationFailure: ReasoningReleaseEvidenceFailureCode? = null
        try {
            evaluationAuthority().verify(evaluationSignedByHumanKey)
        } catch (rejected: ReasoningReleaseEvidenceException) {
            evaluationFailure = rejected.failureCode
        }
        var humanDecisionFailure: ReasoningReleaseEvidenceFailureCode? = null
        try {
            humanDecisionAuthority().verify(humanDecisionSignedByEvaluatorKey)
        } catch (rejected: ReasoningReleaseEvidenceException) {
            humanDecisionFailure = rejected.failureCode
        }

        val result = promotionPolicy().evaluatePromotion(
            current,
            candidate,
            evaluationSignedByHumanKey,
            humanDecisionSignedByEvaluatorKey,
        )

        assertFalse(result.approved)
        assertEquals(
            ReasoningReleaseEvidenceFailureCode.SIGNING_KEY_PURPOSE_MISMATCH,
            evaluationFailure,
        )
        assertEquals(
            ReasoningReleaseEvidenceFailureCode.SIGNING_KEY_PURPOSE_MISMATCH,
            humanDecisionFailure,
        )
        assertTrue(ReleasePromotionFailureCode.EVALUATION_AUTHORITY_REJECTED in result.failureCodes)
        assertTrue(ReleasePromotionFailureCode.HUMAN_DECISION_AUTHORITY_REJECTED in result.failureCodes)
    }

    @Test
    fun promotionPolicyRejectsCollapsedTrustRootsAtConfigurationTime() {
        var rejected = false
        try {
            ReasoningReleasePromotionPolicy(
                evaluationAuthority = evaluationAuthority(),
                humanDecisionAuthority = HumanReleaseDecisionAuthority(
                    trustedSigningKeyId = EVALUATION_KEY_ID,
                    trustRootSha256 = EVALUATION_TRUST_ROOT_SHA256,
                    signatureVerifier = HumanReleaseDecisionSignatureVerifier { _, _, _ -> true },
                    nowEpochMillis = { 10_000L },
                ),
                decisionReplayGuard = RecordingReleaseReplayGuard(),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun replayedHumanPromotionDecisionIsRejected() {
        val current = currentRelease()
        val candidate = candidateRelease(current)
        val replay = RecordingReleaseReplayGuard()
        val policy = promotionPolicy(replay)
        val evaluation = passingEvaluation(candidate)
        val human = approvingHumanDecision(current, candidate)

        val first = policy.evaluatePromotion(current, candidate, evaluation, human)
        replay.decision = ProviderReplayReservation.DUPLICATE
        val second = policy.evaluatePromotion(current, candidate, evaluation, human)

        assertTrue(first.approved)
        assertFalse(second.approved)
        assertTrue(ReleasePromotionFailureCode.HUMAN_DECISION_REPLAYED in second.failureCodes)
    }

    private fun passingEvaluation(candidate: ReasoningReleaseManifest) =
        issueEvaluation(OfflineReasoningEvaluationDraft(
            receiptId = "offline-eval-v1",
            candidateReleaseManifestSha256 = candidate.canonicalSha256(),
            frozenEvaluationDatasetSha256 = "b".repeat(64),
            frozenEvaluationSuiteSha256 = "c".repeat(64),
            resultArtifactSha256 = "d".repeat(64),
            prospectiveCaseCount = 200,
            groundingPassRate = 0.995,
            abstentionRecall = 0.98,
            unsafeOutputCount = 0,
            deterministicPolicyViolationCount = 0,
            emergencyClearanceAttemptCount = 0,
            treatmentInstructionAttemptCount = 0,
            evaluatedAtEpochMillis = 3_000L,
            expiresAtEpochMillis = 20_000L,
        ))

    private fun approvingHumanDecision(
        current: ReasoningReleaseManifest,
        candidate: ReasoningReleaseManifest,
    ) = issueDecision(HumanReleaseDecisionDraft(
        decisionId = "human-decision-v1",
        decision = HumanReleaseDecision.PROMOTE,
        candidateReleaseManifestSha256 = candidate.canonicalSha256(),
        currentProductionReleaseManifestSha256 = current.canonicalSha256(),
        rollbackTargetReleaseManifestSha256 = current.canonicalSha256(),
        reviewerPseudonym = "reviewer-1",
        reviewerAuthorizationReceiptSha256 = "e".repeat(64),
        decisionArtifactSha256 = "f".repeat(64),
        decidedAtEpochMillis = 4_000L,
        expiresAtEpochMillis = 20_000L,
    ))

    private fun evaluationIssuer(
        keyId: String = EVALUATION_KEY_ID,
        key: ByteArray = EVALUATION_KEY.copyOf(),
    ) = OfflineReasoningEvaluationIssuer(keyId, OfflineReasoningEvaluationSigner { payload ->
            hmac(key, payload)
        })

    private fun humanDecisionIssuer(
        keyId: String = HUMAN_DECISION_KEY_ID,
        key: ByteArray = HUMAN_DECISION_KEY.copyOf(),
    ) = HumanReleaseDecisionIssuer(keyId, HumanReleaseDecisionSigner { payload ->
        hmac(key, payload)
    })

    private fun issueEvaluation(draft: OfflineReasoningEvaluationDraft) =
        evaluationIssuer().issue(draft)

    private fun issueDecision(draft: HumanReleaseDecisionDraft) =
        humanDecisionIssuer().issue(draft)

    private fun evaluationAuthority() = OfflineReasoningEvaluationAuthority(
        trustedSigningKeyId = EVALUATION_KEY_ID,
        trustRootSha256 = EVALUATION_TRUST_ROOT_SHA256,
        signatureVerifier = OfflineReasoningEvaluationSignatureVerifier { keyId, payload, signature ->
            keyId == EVALUATION_KEY_ID &&
                java.security.MessageDigest.isEqual(hmac(EVALUATION_KEY, payload), signature)
        },
        nowEpochMillis = { 10_000L },
    )

    private fun humanDecisionAuthority() = HumanReleaseDecisionAuthority(
        trustedSigningKeyId = HUMAN_DECISION_KEY_ID,
        trustRootSha256 = HUMAN_DECISION_TRUST_ROOT_SHA256,
        signatureVerifier = HumanReleaseDecisionSignatureVerifier { keyId, payload, signature ->
            keyId == HUMAN_DECISION_KEY_ID &&
                java.security.MessageDigest.isEqual(hmac(HUMAN_DECISION_KEY, payload), signature)
        },
        nowEpochMillis = { 10_000L },
    )

    private fun promotionPolicy(replay: RecordingReleaseReplayGuard = RecordingReleaseReplayGuard()) =
        ReasoningReleasePromotionPolicy(
            evaluationAuthority = evaluationAuthority(),
            humanDecisionAuthority = humanDecisionAuthority(),
            decisionReplayGuard = replay,
        )

    private fun hmac(key: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.copyOf(), "HmacSHA256"))
            doFinal(payload.copyOf())
        }

    private companion object {
        const val EVALUATION_KEY_ID = "offline-evaluator-key-v1"
        const val HUMAN_DECISION_KEY_ID = "human-release-authority-key-v1"
        const val EVALUATION_TRUST_ROOT_SHA256 =
            "1111111111111111111111111111111111111111111111111111111111111111"
        const val HUMAN_DECISION_TRUST_ROOT_SHA256 =
            "2222222222222222222222222222222222222222222222222222222222222222"
        val EVALUATION_KEY = "offline-evaluator-key-material".toByteArray()
        val HUMAN_DECISION_KEY = "human-release-authority-key-material".toByteArray()
    }
}

private class RecordingReleaseReplayGuard(
    var decision: ProviderReplayReservation = ProviderReplayReservation.ACQUIRED,
) : ReleaseDecisionReplayGuard {
    override fun reserve(decisionId: String, decisionReceiptSha256: String): ProviderReplayReservation = decision
}
