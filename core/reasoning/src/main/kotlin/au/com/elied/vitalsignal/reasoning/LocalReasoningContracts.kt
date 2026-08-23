package au.com.elied.vitalsignal.reasoning

import java.util.Collections

enum class EvidenceKind {
    PERSONAL_EPISODE,
    CLINICIAN_RECORD,
    PRIMARY_RESEARCH,
    VALIDATED_OEM_RESULT,
}

data class CuratedEvidenceReference(
    val id: String,
    val kind: EvidenceKind,
    val contentSha256: String,
    val title: String,
    val sourceUri: String,
    val populationAndDevice: String,
    val limitations: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        requireReasoningId(id, "evidence id")
        requireSha256(contentSha256, "evidence content hash")
        require(title.isNotBlank() && strictUtf8(title).size <= 4_096)
        require(sourceUri.isNotBlank() && strictUtf8(sourceUri).size <= 4_096)
        require(populationAndDevice.isNotBlank() && strictUtf8(populationAndDevice).size <= 4_096)
        require(limitations.isNotBlank() && strictUtf8(limitations).size <= 4_096)
        require(verifiedAtEpochMillis > 0L)
    }
}

/**
 * Verified, immutable model input. Its constructor is internal and the only
 * production construction path is [HealthStatePacketAuthority.verify]. The
 * snapshot hash is recomputed from the signed canonical payload.
 */
class LocalReasoningRequest private constructor(
    snapshot: HealthStatePacketSnapshot,
    val inputSnapshotSha256: String,
) {
    private val snapshot = snapshot.deepCopy()

    val schemaVersion: String get() = "local-reasoning-v2"
    val packetId: String get() = snapshot.packetId
    val issuerId: String get() = snapshot.issuerId
    val subjectPseudonym: String get() = snapshot.subjectPseudonym
    val issuedAtEpochMillis: Long get() = snapshot.issuedAtEpochMillis
    val notBeforeEpochMillis: Long get() = snapshot.notBeforeEpochMillis
    val expiresAtEpochMillis: Long get() = snapshot.expiresAtEpochMillis
    val metricReferences: List<HealthMetricReference> get() = immutableList(snapshot.metricReferences)
    val evidenceReferences: List<CuratedEvidenceReference> get() = immutableList(snapshot.evidenceReferences)
    val approvedNextMeasurementIds: Set<String> get() = immutableSet(snapshot.approvedNextMeasurementIds)
    val approvedQuestionIds: Set<String> get() = immutableSet(snapshot.approvedQuestionIds)
    val approvedNarrativeTemplateIds: Set<String> get() = immutableSet(snapshot.approvedNarrativeTemplateIds)
    val qualityGaps: List<String> get() = immutableList(snapshot.qualityGaps)
    val policyHashSha256: String get() = snapshot.policyHashSha256

    init {
        requireSha256(inputSnapshotSha256, "input snapshot hash")
    }

    internal companion object {
        fun fromVerifiedPacket(snapshot: HealthStatePacketSnapshot, inputSnapshotSha256: String) =
            LocalReasoningRequest(snapshot, inputSnapshotSha256)
    }
}

enum class NarrativeClaimKind {
    OBSERVATION,
    TREND,
    ENGINE_FORECAST,
    HYPOTHESIS,
}

/** Reviewed semantic IDs; visible copy is resolved by a separate deterministic UI catalog. */
object ReviewedNarrativeTemplates {
    const val PERSONAL_BASELINE_OBSERVATION_V1 = "observation.personal-baseline-deviation.v1"
    const val DIRECTIONAL_TREND_V1 = "trend.directional-change.v1"
    const val AUTHORITATIVE_ENGINE_FORECAST_V1 = "forecast.authoritative-engine-output.v1"
    const val CONTEXTUAL_HYPOTHESIS_V1 = "hypothesis.contextual-association.v1"

    private val bindings = mapOf(
        PERSONAL_BASELINE_OBSERVATION_V1 to NarrativeClaimKind.OBSERVATION,
        DIRECTIONAL_TREND_V1 to NarrativeClaimKind.TREND,
        AUTHORITATIVE_ENGINE_FORECAST_V1 to NarrativeClaimKind.ENGINE_FORECAST,
        CONTEXTUAL_HYPOTHESIS_V1 to NarrativeClaimKind.HYPOTHESIS,
    )

    val ids: Set<String> = Collections.unmodifiableSet(bindings.keys)

    fun kindFor(templateId: String): NarrativeClaimKind? = bindings[templateId]
}

/** Implemented by the UI layer with reviewed, localized, deterministic copy. */
fun interface ReviewedNarrativeTemplateResolver {
    fun resolve(templateId: String): String?
}

enum class NarrativeCertainty { LOW, MODERATE, HIGH }

/**
 * The model can select a reviewed semantic ID and references. There is no
 * model-authored prose field, so diagnoses and treatment instructions are not
 * representable in a candidate.
 */
data class NarrativeClaim(
    val id: String,
    val kind: NarrativeClaimKind,
    val templateId: String,
    val metricReferenceIds: List<String>,
    val evidenceReferenceIds: List<String>,
    val disconfirmingEvidenceReferenceIds: List<String> = emptyList(),
    val certainty: NarrativeCertainty,
) {
    init {
        requireReasoningId(id, "claim id")
        requireNarrativeTemplateId(templateId)
        metricReferenceIds.forEach { requireReasoningId(it, "metric reference id") }
        evidenceReferenceIds.forEach { requireReasoningId(it, "evidence reference id") }
        disconfirmingEvidenceReferenceIds.forEach { requireReasoningId(it, "disconfirming evidence id") }
        require(metricReferenceIds.distinct().size == metricReferenceIds.size)
        require(evidenceReferenceIds.distinct().size == evidenceReferenceIds.size)
        require(disconfirmingEvidenceReferenceIds.distinct().size == disconfirmingEvidenceReferenceIds.size)
    }
}

data class LocalReasoningCandidate(
    val schemaVersion: String = "local-reasoning-v2",
    val inputSnapshotSha256: String,
    val claims: List<NarrativeClaim>,
    val nextMeasurementIds: List<String>,
    /** IDs resolved to separately reviewed UI copy; never model-authored questions. */
    val questionIdsForUser: List<String>,
    val abstain: Boolean,
    val abstainReason: AbstainReasonCode?,
) {
    init {
        require(schemaVersion == "local-reasoning-v2")
        requireSha256(inputSnapshotSha256, "candidate snapshot hash")
        require(claims.map { it.id }.distinct().size == claims.size)
        nextMeasurementIds.forEach { requireReasoningId(it, "measurement id") }
        questionIdsForUser.forEach { requireReasoningId(it, "question id") }
        require(nextMeasurementIds.distinct().size == nextMeasurementIds.size)
        require(questionIdsForUser.distinct().size == questionIdsForUser.size)
        require(abstain == (abstainReason != null))
    }
}

enum class AbstainReasonCode {
    INSUFFICIENT_EVIDENCE,
    LOW_SIGNAL_QUALITY,
    CONFLICTING_SIGNALS,
    STALE_EVIDENCE,
    MODEL_OR_SCHEMA_FAILURE,
}

data class OllamaRunReceipt(
    val ollamaVersion: String,
    val modelName: String,
    val modelDigest: String,
    val quantization: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val seed: Long,
    val temperature: Double,
    val contextTokens: Int,
    val completedAtEpochMillis: Long,
) {
    init {
        require(ollamaVersion.isNotBlank())
        require(modelName.isNotBlank())
        require(modelDigest.isNotBlank())
        require(quantization.isNotBlank())
        requireSha256(promptSha256, "prompt hash")
        requireSha256(jsonSchemaSha256, "schema hash")
        require(temperature == 0.0) { "Health explanation runs must use temperature zero" }
        require(contextTokens in 1..65_536)
        require(completedAtEpochMillis > 0L)
    }
}

/** The HTTP/JSON adapter owns schema parsing; the domain layer receives typed output only. */
fun interface LocalReasoningGateway {
    fun generate(request: LocalReasoningRequest): Pair<LocalReasoningCandidate, OllamaRunReceipt>
}

enum class ReasoningDisposition { PASS, REWRITE, ABSTAIN }

enum class ReasoningFailureCode {
    SNAPSHOT_MISMATCH,
    EMPTY_NARRATIVE,
    UNKNOWN_METRIC_REFERENCE,
    UNKNOWN_EVIDENCE_REFERENCE,
    UNKNOWN_NARRATIVE_TEMPLATE,
    UNAPPROVED_NARRATIVE_TEMPLATE,
    NARRATIVE_TEMPLATE_KIND_MISMATCH,
    UNAPPROVED_MEASUREMENT,
    UNAPPROVED_QUESTION,
    UNGROUNDED_CLAIM,
    UNSUPPORTED_FORECAST,
    MISSING_HYPOTHESIS_COUNTEREVIDENCE,
    OVERSTATED_CERTAINTY,
    INVALID_ABSTENTION,
}

data class ReasoningValidationResult(
    val disposition: ReasoningDisposition,
    val failureCodes: Set<ReasoningFailureCode>,
)

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
