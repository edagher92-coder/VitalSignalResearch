package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.SignalQuality
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class QualityInputs(
    val expectedSamples: Int,
    val receivedSamples: Int,
    val validSamples: Int,
    val onBodyFraction: Double,
    val motionFraction: Double,
    val clippingFraction: Double,
    val timestampContinuity: Double,
)

class SignalQualityEngine {
    fun score(inputs: QualityInputs): SignalQuality {
        require(inputs.expectedSamples >= 0)
        require(inputs.receivedSamples >= 0)
        require(inputs.validSamples >= 0)
        require(inputs.validSamples <= inputs.receivedSamples)
        require(inputs.receivedSamples <= inputs.expectedSamples) {
            "receivedSamples cannot exceed expectedSamples; quarantine duplicate or clock-corrupt input"
        }
        require(inputs.onBodyFraction in 0.0..1.0)
        require(inputs.motionFraction in 0.0..1.0)
        require(inputs.clippingFraction in 0.0..1.0)
        require(inputs.timestampContinuity in 0.0..1.0)

        val coverage = if (inputs.expectedSamples <= 0) 0.0 else {
            min(1.0, inputs.receivedSamples.toDouble() / inputs.expectedSamples.toDouble())
        }
        val validity = if (inputs.receivedSamples <= 0) 0.0 else {
            min(1.0, inputs.validSamples.toDouble() / inputs.receivedSamples.toDouble())
        }
        val contact = inputs.onBodyFraction
        val motion = inputs.motionFraction
        val clipping = inputs.clippingFraction
        val continuity = inputs.timestampContinuity
        val motionQuality = exp(-((motion / MOTION_TOLERANCE).pow(2.0)))
        val clippingQuality = 1.0 - clipping
        val hardPass = inputs.expectedSamples > 0 &&
            coverage >= 0.65 && validity >= 0.80 && contact >= 0.75 &&
            continuity >= 0.85 && clipping <= 0.05 && motion <= 0.50

        // Weighted geometric fusion prevents one excellent component from
        // compensating for failed contact, continuity or sample validity.
        val score = if (!hardPass) {
            0.0
        } else {
            exp(
                weightedLog(coverage, 0.25) +
                    weightedLog(validity, 0.25) +
                    weightedLog(contact, 0.20) +
                    weightedLog(continuity, 0.15) +
                    weightedLog(motionQuality, 0.10) +
                    weightedLog(clippingQuality, 0.05),
            ).coerceIn(0.0, 1.0)
        }

        val reasons = buildList {
            if (coverage < 0.65) add("measurement coverage was incomplete")
            if (validity < 0.80) add("many samples failed validity checks")
            if (contact < 0.85) add("watch contact was inconsistent")
            if (motion > 0.25) add("movement may have contaminated the signal")
            if (clipping > 0.05) add("the optical signal clipped")
            if (continuity < 0.85) add("timestamps contained gaps")
            if (!hardPass) add("the measurement failed a required quality gate")
        }

        return SignalQuality(
            score = max(0.0, score),
            coverage = coverage,
            contact = contact,
            motionContamination = motion,
            validity = validity,
            clipping = clipping,
            timestampContinuity = continuity,
            reasons = reasons,
            evaluatorVersion = "window-quality-v2",
        )
    }

    private fun weightedLog(value: Double, weight: Double): Double =
        weight * ln(max(value, EPSILON))

    private companion object {
        const val EPSILON = 1e-6
        const val MOTION_TOLERANCE = 0.35
    }
}
