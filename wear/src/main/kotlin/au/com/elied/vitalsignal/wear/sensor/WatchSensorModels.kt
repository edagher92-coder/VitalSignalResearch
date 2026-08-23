package au.com.elied.vitalsignal.wear.sensor

import au.com.elied.vitalsignal.model.SignalQuality
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

enum class CollectionMode {
    PASSIVE,
    CONTINUOUS,
    ON_DEMAND,
}

/**
 * Public app vocabulary for watch data. It deliberately does not expose a
 * Samsung SDK class, so the licensed implementation remains replaceable.
 */
enum class WatchDataChannel(
    val mode: CollectionMode,
    val nominalSampleRateHz: Int? = null,
    val maximumCaptureSeconds: Int? = null,
) {
    PASSIVE_HEART_RATE(CollectionMode.PASSIVE),
    PASSIVE_STEPS(CollectionMode.PASSIVE),
    ACCELEROMETER(CollectionMode.CONTINUOUS, nominalSampleRateHz = 25),
    HEART_RATE_AND_IBI(CollectionMode.CONTINUOUS, nominalSampleRateHz = 1),
    RAW_PPG_GREEN(CollectionMode.CONTINUOUS, nominalSampleRateHz = 25),
    RAW_PPG_RED(CollectionMode.CONTINUOUS, nominalSampleRateHz = 25),
    RAW_PPG_INFRARED(CollectionMode.CONTINUOUS, nominalSampleRateHz = 25),
    SKIN_AND_AMBIENT_TEMPERATURE(CollectionMode.CONTINUOUS),
    EDA(CollectionMode.CONTINUOUS, nominalSampleRateHz = 1),
    BIA(CollectionMode.ON_DEMAND, maximumCaptureSeconds = 30),
    ECG(CollectionMode.ON_DEMAND, nominalSampleRateHz = 500, maximumCaptureSeconds = 30),
    MULTI_FREQUENCY_BIA(CollectionMode.ON_DEMAND, maximumCaptureSeconds = 30),
    HIGH_RATE_PPG(CollectionMode.ON_DEMAND, nominalSampleRateHz = 100, maximumCaptureSeconds = 30),
    SPOT_TEMPERATURE(CollectionMode.ON_DEMAND, maximumCaptureSeconds = 30),
    BLOOD_OXYGEN(CollectionMode.ON_DEMAND, maximumCaptureSeconds = 30),
}

enum class CapabilityState {
    AVAILABLE,
    UNSUPPORTED_DEVICE,
    PERMISSION_REQUIRED,
    ADAPTER_NOT_INSTALLED,
    TEMPORARILY_UNAVAILABLE,
}

data class SensorCapability(
    val channel: WatchDataChannel,
    val state: CapabilityState,
    val detail: String? = null,
) {
    val canCollect: Boolean get() = state == CapabilityState.AVAILABLE
}

object SensorCatalog {
    val researchDefaults: Set<WatchDataChannel> = java.util.Set.copyOf(
        setOf(
            WatchDataChannel.ACCELEROMETER,
            WatchDataChannel.HEART_RATE_AND_IBI,
            WatchDataChannel.RAW_PPG_GREEN,
            WatchDataChannel.RAW_PPG_RED,
            WatchDataChannel.RAW_PPG_INFRARED,
            WatchDataChannel.SKIN_AND_AMBIENT_TEMPERATURE,
        ),
    )

    val all: List<WatchDataChannel> = WatchDataChannel.entries
}

data class RawQualitySignals(
    val expectedSamples: Int,
    val receivedSamples: Int,
    val contactConfidence: Double,
    val motionRmsG: Double,
    val clippedSampleFraction: Double,
    val trackerWarnings: Set<String> = emptySet(),
) {
    init {
        require(expectedSamples >= 0)
        require(receivedSamples >= 0)
        require(receivedSamples <= expectedSamples) {
            "Received samples cannot exceed the acquisition window expectation"
        }
        require(contactConfidence in 0.0..1.0)
        require(motionRmsG >= 0.0)
        require(clippedSampleFraction in 0.0..1.0)
    }
}

/** Deterministic first-pass quality gate; analytics can apply stricter rules. */
object WatchSignalQualityEvaluator {
    fun evaluate(signals: RawQualitySignals): SignalQuality {
        val coverage = if (signals.expectedSamples == 0) {
            0.0
        } else {
            (signals.receivedSamples.toDouble() / signals.expectedSamples).coerceIn(0.0, 1.0)
        }
        val motionContamination = (signals.motionRmsG / 1.2).coerceIn(0.0, 1.0)
        val trackerFactor = if (signals.trackerWarnings.isEmpty()) 1.0 else 0.0
        val clippingFactor = 1.0 - (0.75 * signals.clippedSampleFraction)
        val motionQuality = exp(-((signals.motionRmsG / MOTION_TOLERANCE_G).pow(2.0)))
        val hardPass = signals.expectedSamples > 0 &&
            coverage >= 0.80 && signals.contactConfidence >= 0.80 &&
            motionContamination <= 0.25 && signals.clippedSampleFraction <= 0.05 &&
            signals.trackerWarnings.isEmpty()
        val score = if (!hardPass) {
            0.0
        } else {
            exp(
                0.45 * ln(max(coverage, EPSILON)) +
                    0.35 * ln(max(signals.contactConfidence, EPSILON)) +
                    0.20 * ln(max(motionQuality, EPSILON)),
            ) * trackerFactor * clippingFactor
        }

        val reasons = buildList {
            if (coverage < 0.80) add("Only ${(coverage * 100).roundToInt()}% sample coverage")
            if (signals.contactConfidence < 0.70) add("Watch contact is weak")
            if (motionContamination > 0.45) add("Motion contamination is elevated")
            if (signals.clippedSampleFraction > 0.02) add("Waveform clipping detected")
            if (!hardPass) add("Measurement failed a required quality gate")
            addAll(signals.trackerWarnings.sorted())
        }

        return SignalQuality(
            score = score.coerceIn(0.0, 1.0),
            coverage = coverage,
            contact = signals.contactConfidence,
            motionContamination = motionContamination,
            validity = if (signals.trackerWarnings.isEmpty()) 1.0 else 0.0,
            clipping = signals.clippedSampleFraction,
            timestampContinuity = coverage,
            reasons = reasons,
            evaluatorVersion = "watch-quality-v2",
        )
    }

    private const val EPSILON = 1e-6
    private const val MOTION_TOLERANCE_G = 0.35
}
