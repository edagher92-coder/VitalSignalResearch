package au.com.elied.vitalsignal.reasoning

/**
 * Deterministic authority after local-model generation. JSON conformance alone
 * is not evidence of truth, so unknown provenance and unsafe scope fail closed.
 */
class LocalReasoningPolicy {
    fun validate(
        request: LocalReasoningRequest,
        candidate: LocalReasoningCandidate,
    ): ReasoningValidationResult {
        val failures = linkedSetOf<ReasoningFailureCode>()
        val metricIds = request.metricReferences.mapTo(mutableSetOf()) { it.id }
        val evidenceIds = request.evidenceReferences.mapTo(mutableSetOf()) { it.id }

        if (candidate.inputSnapshotSha256 != request.inputSnapshotSha256) {
            failures += ReasoningFailureCode.SNAPSHOT_MISMATCH
        }
        if (!candidate.abstain && candidate.claims.isEmpty()) {
            failures += ReasoningFailureCode.EMPTY_NARRATIVE
        }
        if (candidate.abstain && candidate.claims.isNotEmpty()) {
            failures += ReasoningFailureCode.INVALID_ABSTENTION
        }
        if (candidate.nextMeasurementIds.any { it !in request.approvedNextMeasurementIds }) {
            failures += ReasoningFailureCode.UNAPPROVED_MEASUREMENT
        }
        if (candidate.questionIdsForUser.any { it !in request.approvedQuestionIds }) {
            failures += ReasoningFailureCode.UNAPPROVED_QUESTION
        }

        candidate.claims.forEach { claim ->
            if (claim.metricReferenceIds.isEmpty()) {
                failures += ReasoningFailureCode.UNGROUNDED_CLAIM
            }
            if (claim.metricReferenceIds.any { it !in metricIds }) {
                failures += ReasoningFailureCode.UNKNOWN_METRIC_REFERENCE
            }
            val allEvidence = claim.evidenceReferenceIds + claim.disconfirmingEvidenceReferenceIds
            if (allEvidence.any { it !in evidenceIds }) {
                failures += ReasoningFailureCode.UNKNOWN_EVIDENCE_REFERENCE
            }
            val reviewedKind = ReviewedNarrativeTemplates.kindFor(claim.templateId)
            if (reviewedKind == null) {
                failures += ReasoningFailureCode.UNKNOWN_NARRATIVE_TEMPLATE
            }
            if (claim.templateId !in request.approvedNarrativeTemplateIds) {
                failures += ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE
            }
            if (reviewedKind != null && reviewedKind != claim.kind) {
                failures += ReasoningFailureCode.NARRATIVE_TEMPLATE_KIND_MISMATCH
            }
            if (claim.kind == NarrativeClaimKind.ENGINE_FORECAST && claim.metricReferenceIds.isEmpty()) {
                failures += ReasoningFailureCode.UNSUPPORTED_FORECAST
            }
            if (
                claim.kind == NarrativeClaimKind.HYPOTHESIS &&
                claim.disconfirmingEvidenceReferenceIds.isEmpty()
            ) {
                failures += ReasoningFailureCode.MISSING_HYPOTHESIS_COUNTEREVIDENCE
            }
            // The language layer may not elevate research certainty. Higher
            // grades belong to the authoritative numerical/OEM result packet.
            if (claim.certainty == NarrativeCertainty.HIGH) {
                failures += ReasoningFailureCode.OVERSTATED_CERTAINTY
            }
        }

        val disposition = when {
            candidate.abstain && failures.isEmpty() -> ReasoningDisposition.ABSTAIN
            failures.isEmpty() -> ReasoningDisposition.PASS
            else -> ReasoningDisposition.REWRITE
        }
        return ReasoningValidationResult(disposition, java.util.Set.copyOf(failures))
    }
}
