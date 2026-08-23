package au.com.elied.vitalsignal.analytics

enum class SafetyDisposition {
    ROUTE_REVIEWED_SYMPTOMS,
    USER_CONCERN_REVIEW,
    LEARNING,
    MEASUREMENT_UNAVAILABLE,
    ABSTAINED,
    TYPICAL,
    SINGLE_SIGNAL_REMEASURE,
    PATTERN_ELIGIBLE,
}

data class SafetyGateInput(
    val dataQuality: Double,
    val baselineMaturity: Double,
    val baselineSampleCount: Int,
    val independentCoherentFamilies: Int,
    /** Connected components after shared acquisition paths are collapsed. */
    val independentCoherentAcquisitionGroups: Int,
    /** Families required by this exact interpretation contract, not every device capability. */
    val expectedQualifiedFamilies: Set<IndependentEvidenceFamily>,
    /** Required-family windows that passed quality and provenance gates for this assessment. */
    val availableQualifiedFamilies: Set<IndependentEvidenceFamily>,
    /** Qualified families containing opposing normalized directions. */
    val conflictingFamilies: Set<IndependentEvidenceFamily> = emptySet(),
    val intervalWidth: Double? = null,
    val firmwareOrDeviceChanged: Boolean = false,
    val outOfDistribution: Boolean = false,
    /** Set only by a separately reviewed, deterministic symptom questionnaire. */
    val reviewedUrgentSymptomFlag: Boolean = false,
    /**
     * Direct human input, never inferred from a wearable or model. A concern is
     * not an emergency classification, but it must prevent sensor reassurance.
     */
    val userConcernReported: Boolean = false,
) {
    init {
        require(dataQuality in 0.0..1.0)
        require(baselineMaturity in 0.0..1.0)
        require(baselineSampleCount >= 0)
        require(independentCoherentFamilies >= 0)
        require(independentCoherentAcquisitionGroups >= 0)
        require(independentCoherentAcquisitionGroups <= independentCoherentFamilies)
        require(
            independentCoherentFamilies == 0 || independentCoherentAcquisitionGroups > 0,
        )
        require(expectedQualifiedFamilies.isNotEmpty())
        require(conflictingFamilies.all { it in availableQualifiedFamilies })
        require(independentCoherentFamilies <= availableQualifiedFamilies.size - conflictingFamilies.size)
        require(intervalWidth == null || intervalWidth in 0.0..1.0)
    }
}

data class SafetyDecision(
    val disposition: SafetyDisposition,
    val reasonCodes: Set<String>,
    val userMessage: String,
)

/**
 * Deterministic policy gate between statistical output and user-visible copy.
 * It can withhold an interpretation; it cannot diagnose an emergency.
 */
class SafetyPolicyEngine {
    fun evaluate(input: SafetyGateInput): SafetyDecision {
        if (input.reviewedUrgentSymptomFlag) {
            return SafetyDecision(
                disposition = SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS,
                reasonCodes = setOf("reviewed-symptom-route"),
                userMessage = "Follow the reviewed local symptom guidance now; do not wait for a sensor score.",
            )
        }

        if (input.userConcernReported) {
            return SafetyDecision(
                disposition = SafetyDisposition.USER_CONCERN_REVIEW,
                reasonCodes = setOf("user-concern-overrides-sensors"),
                userMessage = "Your concern takes priority. Do not rely on a wearable score for reassurance; follow your care plan or contact an appropriate clinician.",
            )
        }

        if (input.dataQuality < MIN_INTERPRETATION_QUALITY) {
            return SafetyDecision(
                disposition = SafetyDisposition.MEASUREMENT_UNAVAILABLE,
                reasonCodes = setOf("quality-below-threshold"),
                userMessage = "Unable to interpret reliably. Improve watch contact, reduce motion, and repeat the measurement.",
            )
        }

        val missingRequiredFamilies = input.expectedQualifiedFamilies - input.availableQualifiedFamilies
        if (missingRequiredFamilies.isNotEmpty()) {
            return SafetyDecision(
                disposition = SafetyDisposition.MEASUREMENT_UNAVAILABLE,
                reasonCodes = setOf("required-qualified-family-unavailable"),
                userMessage = "One or more required sensor families were unavailable or did not pass quality checks. No typical or pattern result is shown; restore the measurement and repeat.",
            )
        }

        if (input.baselineMaturity < 1.0 || input.baselineSampleCount < MIN_MATCHED_SAMPLES) {
            return SafetyDecision(
                disposition = SafetyDisposition.LEARNING,
                reasonCodes = setOf("baseline-immature"),
                userMessage = "Personal baseline learning is still active; no physiological interpretation is available.",
            )
        }


        if (input.conflictingFamilies.isNotEmpty()) {
            return SafetyDecision(
                disposition = SafetyDisposition.ABSTAINED,
                reasonCodes = setOf("opposing-qualified-evidence-within-family"),
                userMessage = "Qualified measurements within one correlated sensor family point in opposite directions. No typical or pattern result is shown; repeat a high-quality resting measurement.",
            )
        }

        val abstentionReasons = buildSet {
            if (input.firmwareOrDeviceChanged) add("device-or-firmware-transition")
            if (input.outOfDistribution) add("context-out-of-distribution")
            if (input.intervalWidth != null && input.intervalWidth > MAX_USEFUL_INTERVAL_WIDTH) {
                add("uncertainty-too-wide")
            }
        }
        if (abstentionReasons.isNotEmpty()) {
            return SafetyDecision(
                disposition = SafetyDisposition.ABSTAINED,
                reasonCodes = abstentionReasons,
                userMessage = "The model withheld this result because its uncertainty or context is outside the validated range.",
            )
        }

        if (input.independentCoherentFamilies >= 2 &&
            input.independentCoherentAcquisitionGroups < 2
        ) {
            return SafetyDecision(
                disposition = SafetyDisposition.ABSTAINED,
                reasonCodes = setOf("shared-acquisition-dependency"),
                userMessage = "Several derived measurements changed, but they share one sensor acquisition path and cannot independently corroborate a pattern. No pattern result is shown; repeat a qualified measurement or obtain an independent modality.",
            )
        }

        if (input.independentCoherentFamilies == 0) {
            return SafetyDecision(
                disposition = SafetyDisposition.TYPICAL,
                reasonCodes = setOf("no-qualified-deviation"),
                userMessage = "Qualified measurements are within expected personal variation. This does not rule out a medical condition.",
            )
        }

        if (input.independentCoherentFamilies == 1) {
            return SafetyDecision(
                disposition = SafetyDisposition.SINGLE_SIGNAL_REMEASURE,
                reasonCodes = setOf("insufficient-independent-corroboration"),
                userMessage = "One signal differs from baseline. Repeat a qualified measurement; do not infer a cause from it alone.",
            )
        }

        return SafetyDecision(
            disposition = SafetyDisposition.PATTERN_ELIGIBLE,
            reasonCodes = setOf("quality-baseline-corroboration-passed"),
            userMessage = "A qualified multi-domain pattern can be shown with its uncertainty and evidence.",
        )
    }

    private companion object {
        const val MIN_INTERPRETATION_QUALITY = 0.80
        const val MIN_MATCHED_SAMPLES = 20
        const val MAX_USEFUL_INTERVAL_WIDTH = 0.65
    }
}
