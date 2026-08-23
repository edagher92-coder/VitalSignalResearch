package au.com.elied.vitalsignal.reasoning

internal object CanonicalReasoningCandidate {
    fun sha256(candidate: LocalReasoningCandidate): String = sha256Hex(bytes(candidate))

    fun bytes(candidate: LocalReasoningCandidate): ByteArray = CanonicalRecord().apply {
        field(1, strictUtf8("VITALSIGNAL_LOCAL_REASONING_CANDIDATE"))
        field(2, strictUtf8(candidate.schemaVersion))
        field(3, strictUtf8(candidate.inputSnapshotSha256))
        field(4, listBytes(candidate.claims.sortedBy { it.id }) { claim ->
            CanonicalRecord().apply {
                field(1, strictUtf8(claim.id))
                field(2, strictUtf8(claim.kind.name))
                field(3, strictUtf8(claim.templateId))
                field(4, stringListBytes(claim.metricReferenceIds.sorted()))
                field(5, stringListBytes(claim.evidenceReferenceIds.sorted()))
                field(6, stringListBytes(claim.disconfirmingEvidenceReferenceIds.sorted()))
                field(7, strictUtf8(claim.certainty.name))
            }.bytes()
        })
        field(5, stringListBytes(candidate.nextMeasurementIds.sorted()))
        field(6, stringListBytes(candidate.questionIdsForUser.sorted()))
        field(7, byteArrayOf(if (candidate.abstain) 1 else 0))
        field(8, strictUtf8(candidate.abstainReason?.name ?: ""))
    }.bytes()
}
