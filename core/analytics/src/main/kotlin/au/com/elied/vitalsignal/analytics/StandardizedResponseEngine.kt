package au.com.elied.vitalsignal.analytics

import kotlin.math.abs
import kotlin.math.max

enum class ResponseFeatureFamily {
    CARDIAC_KINETICS,
    AUTONOMIC,
    PPG_MORPHOLOGY,
    RESPIRATORY,
    MOVEMENT,
    THERMAL,
    BIOIMPEDANCE,
}

data class ResponseFeature(
    val id: String,
    val family: ResponseFeatureFamily,
    val value: Double,
    val unit: String,
) {
    init {
        require(id.isNotBlank())
        require(value.isFinite())
        require(unit.isNotBlank())
    }
}

/**
 * One output vector from a separately reviewed, repeatable protocol. This type
 * does not decide who is eligible to stand, walk, exercise or change treatment.
 */
data class StandardizedResponseEpisode(
    val id: String,
    val protocolId: String,
    val protocolVersion: String,
    val deviceGeneration: String,
    val firmwareGeneration: String,
    val completedAtEpochMillis: Long,
    val localDateIso: String,
    val quality: Double,
    /** Non-blank only after a separate protocol-specific safety/consent gate. */
    val eligibilityReceiptId: String,
    /**
     * A configuration fingerprint for the physical protocol (for example, the
     * chair or route specification, instruction script and timing method). A
     * response is never compared across different fingerprints.
     */
    val standardizationFingerprint: String,
    /**
     * Captured outside the sensor pipeline. This is deliberately not inferred
     * from signal quality or physiological features, and is not a questionnaire.
     */
    val humanConcern: HumanConcernState,
    val features: List<ResponseFeature>,
    val provenanceIds: List<String>,
) {
    init {
        require(id.isNotBlank())
        require(protocolId.isNotBlank())
        require(protocolVersion.isNotBlank())
        require(deviceGeneration.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(completedAtEpochMillis > 0L)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(localDateIso))
        require(quality in 0.0..1.0)
        require(eligibilityReceiptId.isNotBlank())
        require(standardizationFingerprint.matches(Regex("[a-f0-9]{64}"))) {
            "standardizationFingerprint must be a lowercase SHA-256 digest"
        }
        require(features.isNotEmpty())
        require(features.map { it.id }.distinct().size == features.size)
        require(provenanceIds.isNotEmpty())
    }
}

/**
 * A single human/observer concern state supplied by a reviewed workflow. It
 * does not name, screen for, or diagnose any symptom or condition.
 */
enum class HumanConcernState {
    NO_CONCERN_REPORTED,
    CONCERN_REPORTED,
    NOT_CAPTURED,
}

enum class ResponseAssessmentState {
    LEARNING,
    ABSTAINED,
    HUMAN_CONCERN_REVIEW,
    WITHIN_PERSONAL_RANGE,
    POSSIBLE_RESPONSE_CHANGE,
}

data class ResponseFeatureDeviation(
    val featureId: String,
    val family: ResponseFeatureFamily,
    val observed: Double,
    val expectedMedian: Double,
    val robustZ: Double,
    val unit: String,
    val referenceEpisodeCount: Int,
)

data class StandardizedResponseAssessment(
    val state: ResponseAssessmentState,
    val deviations: List<ResponseFeatureDeviation>,
    val changedIndependentFamilies: Set<ResponseFeatureFamily>,
    val referenceEpisodeCount: Int,
    val effectiveReferenceDays: Int,
    val reason: String,
    val modelVersion: String = "standardized-response-v1",
)

/**
 * Verifies that a reviewed, protocol-specific eligibility authority issued the
 * receipt for this exact episode. A non-blank caller-supplied ID is never
 * authority by itself.
 */
fun interface StandardizedResponseEligibilityVerifier {
    fun verify(episode: StandardizedResponseEpisode): Boolean
}

/**
 * Compares the response to a repeatable input against same-protocol,
 * same-device-generation personal history. It intentionally requires change in
 * multiple independent families before returning a research-level signal.
 */
class StandardizedResponseEngine(
    private val eligibilityVerifier: StandardizedResponseEligibilityVerifier,
    private val minimumReferenceEpisodes: Int = 12,
    private val minimumReferenceDays: Int = 28,
    private val minimumCurrentQuality: Double = 0.85,
    private val minimumReferenceQuality: Double = 0.75,
    private val deviationThreshold: Double = 2.5,
    private val minimumIndependentFamilies: Int = 2,
    private val minimumScaleByFeature: Map<String, Double> = emptyMap(),
) {
    init {
        require(minimumReferenceEpisodes >= 12)
        require(minimumReferenceDays >= 28)
        require(minimumCurrentQuality in 0.0..1.0)
        require(minimumReferenceQuality in 0.0..1.0)
        require(deviationThreshold >= 2.0)
        require(minimumIndependentFamilies >= 2)
        require(minimumScaleByFeature.values.all { it > 0.0 })
    }

    fun assess(
        history: List<StandardizedResponseEpisode>,
        current: StandardizedResponseEpisode,
    ): StandardizedResponseAssessment {
        if (current.humanConcern != HumanConcernState.NO_CONCERN_REPORTED) {
            return StandardizedResponseAssessment(
                state = ResponseAssessmentState.HUMAN_CONCERN_REVIEW,
                deviations = emptyList(),
                changedIndependentFamilies = emptySet(),
                referenceEpisodeCount = 0,
                effectiveReferenceDays = 0,
                reason = "A human concern is present or was not captured; sensor scores cannot override it",
            )
        }
        if (!eligibilityVerified(current)) {
            return abstained(
                "The current response episode lacks verified protocol-specific eligibility authority",
            )
        }
        if (current.quality < minimumCurrentQuality) {
            return abstained("Current challenge signal quality is below the research gate")
        }

        val eligible = history.filter { reference ->
            reference.completedAtEpochMillis < current.completedAtEpochMillis &&
                eligibilityVerified(reference) &&
                reference.protocolId == current.protocolId &&
                reference.protocolVersion == current.protocolVersion &&
                reference.deviceGeneration == current.deviceGeneration &&
                reference.firmwareGeneration == current.firmwareGeneration &&
                reference.standardizationFingerprint == current.standardizationFingerprint &&
                reference.humanConcern == HumanConcernState.NO_CONCERN_REPORTED &&
                reference.quality >= minimumReferenceQuality
        }
        val effectiveDays = eligible.map { it.localDateIso }.distinct().size
        if (eligible.size < minimumReferenceEpisodes || effectiveDays < minimumReferenceDays) {
            return StandardizedResponseAssessment(
                state = ResponseAssessmentState.LEARNING,
                deviations = emptyList(),
                changedIndependentFamilies = emptySet(),
                referenceEpisodeCount = eligible.size,
                effectiveReferenceDays = effectiveDays,
                reason = "Learning a same-protocol personal response reference",
            )
        }

        val currentById = current.features.associateBy { it.id }
        val deviations = currentById.values.mapNotNull { observed ->
            val referenceFeatures = eligible.mapNotNull { episode ->
                episode.features.firstOrNull {
                    it.id == observed.id && it.family == observed.family && it.unit == observed.unit
                }
            }
            if (referenceFeatures.size < minimumReferenceEpisodes) return@mapNotNull null

            val values = referenceFeatures.map { it.value }.sorted()
            val median = median(values)
            val rawMad = median(values.map { abs(it - median) }.sorted())
            val configuredFloor = minimumScaleByFeature[observed.id]
            val relativeFloor = max(abs(median) * DEFAULT_RELATIVE_FLOOR, ABSOLUTE_MIN_SCALE)
            val scale = max(rawMad * MAD_TO_SIGMA, configuredFloor ?: relativeFloor)
            ResponseFeatureDeviation(
                featureId = observed.id,
                family = observed.family,
                observed = observed.value,
                expectedMedian = median,
                robustZ = (observed.value - median) / scale,
                unit = observed.unit,
                referenceEpisodeCount = referenceFeatures.size,
            )
        }.sortedBy { it.featureId }

        if (deviations.size < MINIMUM_FEATURES) {
            return StandardizedResponseAssessment(
                state = ResponseAssessmentState.ABSTAINED,
                deviations = deviations,
                changedIndependentFamilies = emptySet(),
                referenceEpisodeCount = eligible.size,
                effectiveReferenceDays = effectiveDays,
                reason = "Too few comparable response features survived provenance and unit matching",
            )
        }

        val changedFamilies = deviations
            .filter { abs(it.robustZ) >= deviationThreshold }
            .mapTo(linkedSetOf()) { it.family }
        val changed = changedFamilies.size >= minimumIndependentFamilies
        return StandardizedResponseAssessment(
            state = if (changed) {
                ResponseAssessmentState.POSSIBLE_RESPONSE_CHANGE
            } else {
                ResponseAssessmentState.WITHIN_PERSONAL_RANGE
            },
            deviations = deviations,
            changedIndependentFamilies = changedFamilies,
            referenceEpisodeCount = eligible.size,
            effectiveReferenceDays = effectiveDays,
            reason = if (changed) {
                "The standardized response changed across independent signal families; cause is unknown"
            } else {
                "The qualified standardized response did not meet the cross-family change rule"
            },
        )
    }

    private fun abstained(reason: String) = StandardizedResponseAssessment(
        state = ResponseAssessmentState.ABSTAINED,
        deviations = emptyList(),
        changedIndependentFamilies = emptySet(),
        referenceEpisodeCount = 0,
        effectiveReferenceDays = 0,
        reason = reason,
    )

    private fun eligibilityVerified(episode: StandardizedResponseEpisode): Boolean = try {
        eligibilityVerifier.verify(episode)
    } catch (_: RuntimeException) {
        false
    }

    private fun median(sorted: List<Double>): Double {
        require(sorted.isNotEmpty())
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private companion object {
        const val MAD_TO_SIGMA = 1.4826
        const val DEFAULT_RELATIVE_FLOOR = 0.02
        const val ABSOLUTE_MIN_SCALE = 1e-6
        const val MINIMUM_FEATURES = 3
    }
}
