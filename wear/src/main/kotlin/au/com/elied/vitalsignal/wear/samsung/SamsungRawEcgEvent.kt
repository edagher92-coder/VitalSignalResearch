package au.com.elied.vitalsignal.wear.samsung

import kotlin.math.abs

enum class EcgTimingValidationState {
    UNVALIDATED,
    REFERENCE_ALIGNED,
    REJECTED,
}

data class SamsungEcgSourceIdentity(
    val watchModel: String,
    val firmwareVersion: String,
    val sensorSdkVersion: String,
    val appVersion: String,
) {
    init {
        require(watchModel.isNotBlank())
        require(firmwareVersion.isNotBlank())
        require(sensorSdkVersion.isNotBlank())
        require(appVersion.isNotBlank())
    }
}

data class SamsungEcgCollectionProvenance(
    val participantPseudonym: String,
    val protocolId: String,
    val consentGeneration: Long,
    val validationReceiptId: String,
) {
    init {
        require(participantPseudonym.isNotBlank())
        require(protocolId.isNotBlank())
        require(consentGeneration > 0L)
        require(validationReceiptId.isNotBlank())
    }
}

/**
 * Lossless app-owned representation of one SDK ECG DataPoint.
 *
 * Optional fields remain optional because Samsung supplies sequence, lead state,
 * thresholds and embedded green PPG only on specific positions in a 5- or
 * 10-point callback. The raw lead flag is retained even though contact status can
 * be derived from it. Thresholds are retained rather than replacing the sample
 * with a precomputed clipping flag.
 */
data class SamsungRawEcgPoint(
    val sourceTimestampEpochMillis: Long,
    val ecgMillivolts: Double,
    val embeddedGreenPpgRaw: Int? = null,
    val rawSequence: Int? = null,
    val rawLeadOff: Int? = null,
    val minimumThresholdMillivolts: Double? = null,
    val maximumThresholdMillivolts: Double? = null,
) {
    init {
        require(sourceTimestampEpochMillis > 0L)
        require(ecgMillivolts.isFinite())
        rawSequence?.let { require(it in 0..255) }
        minimumThresholdMillivolts?.let { require(it.isFinite()) }
        maximumThresholdMillivolts?.let { require(it.isFinite()) }
        if (minimumThresholdMillivolts != null && maximumThresholdMillivolts != null) {
            require(minimumThresholdMillivolts < maximumThresholdMillivolts)
        }
    }

    val electrodeContact: Boolean?
        get() = rawLeadOff?.let { it == 0 }

    val saturated: Boolean?
        get() = if (minimumThresholdMillivolts == null || maximumThresholdMillivolts == null) {
            null
        } else {
            ecgMillivolts < minimumThresholdMillivolts ||
                ecgMillivolts > maximumThresholdMillivolts
        }
}

class SamsungRawEcgEvent(
    val captureSessionId: String,
    val callbackOrdinal: Long,
    val receivedAtEpochMillis: Long,
    val receivedAtElapsedRealtimeNanos: Long,
    val source: SamsungEcgSourceIdentity,
    val provenance: SamsungEcgCollectionProvenance,
    points: List<SamsungRawEcgPoint>,
    val timingValidationState: EcgTimingValidationState = EcgTimingValidationState.UNVALIDATED,
    val referenceSessionId: String? = null,
    val clockAlignmentResidualMillis: Double? = null,
) {
    /** Snapshot the callback because licensed SDK adapters may reuse mutable list instances. */
    val points: List<SamsungRawEcgPoint> = java.util.List.copyOf(points)

    init {
        require(captureSessionId.isNotBlank())
        require(callbackOrdinal >= 0L)
        require(receivedAtEpochMillis > 0L)
        require(receivedAtElapsedRealtimeNanos >= 0L)
        require(this.points.size == 5 || this.points.size == 10) {
            "Samsung ECG callbacks must preserve the documented 5- or 10-point layout"
        }
        require(points.zipWithNext().all { (left, right) ->
            right.sourceTimestampEpochMillis >= left.sourceTimestampEpochMillis
        })
        require(points.first().rawSequence != null)
        require(points.first().rawLeadOff != null)
        require(points.first().embeddedGreenPpgRaw != null)
        require(points.first().minimumThresholdMillivolts != null)
        require(points.first().maximumThresholdMillivolts != null)
        if (points.size == 10) require(points[5].embeddedGreenPpgRaw != null)
        clockAlignmentResidualMillis?.let {
            require(it.isFinite() && it >= 0.0)
        }
        if (timingValidationState == EcgTimingValidationState.REFERENCE_ALIGNED) {
            require(!referenceSessionId.isNullOrBlank())
            require(clockAlignmentResidualMillis != null)
        }
    }

    val sequence: Int get() = requireNotNull(points.first().rawSequence)
    val hasLeadOff: Boolean get() = points.first().rawLeadOff != 0
    val hasSaturation: Boolean get() = points.any { it.saturated == true }
    val embeddedGreenPpgSamples: List<Pair<Long, Int>>
        get() = points.mapNotNull { point ->
            point.embeddedGreenPpgRaw?.let { point.sourceTimestampEpochMillis to it }
        }

    fun copy(
        captureSessionId: String = this.captureSessionId,
        callbackOrdinal: Long = this.callbackOrdinal,
        receivedAtEpochMillis: Long = this.receivedAtEpochMillis,
        receivedAtElapsedRealtimeNanos: Long = this.receivedAtElapsedRealtimeNanos,
        source: SamsungEcgSourceIdentity = this.source,
        provenance: SamsungEcgCollectionProvenance = this.provenance,
        points: List<SamsungRawEcgPoint> = this.points,
        timingValidationState: EcgTimingValidationState = this.timingValidationState,
        referenceSessionId: String? = this.referenceSessionId,
        clockAlignmentResidualMillis: Double? = this.clockAlignmentResidualMillis,
    ) = SamsungRawEcgEvent(
        captureSessionId,
        callbackOrdinal,
        receivedAtEpochMillis,
        receivedAtElapsedRealtimeNanos,
        source,
        provenance,
        points,
        timingValidationState,
        referenceSessionId,
        clockAlignmentResidualMillis,
    )

    override fun equals(other: Any?): Boolean = other is SamsungRawEcgEvent &&
        captureSessionId == other.captureSessionId && callbackOrdinal == other.callbackOrdinal &&
        receivedAtEpochMillis == other.receivedAtEpochMillis &&
        receivedAtElapsedRealtimeNanos == other.receivedAtElapsedRealtimeNanos &&
        source == other.source && provenance == other.provenance && points == other.points &&
        timingValidationState == other.timingValidationState &&
        referenceSessionId == other.referenceSessionId &&
        clockAlignmentResidualMillis == other.clockAlignmentResidualMillis

    override fun hashCode(): Int = listOf(
        captureSessionId,
        callbackOrdinal,
        receivedAtEpochMillis,
        receivedAtElapsedRealtimeNanos,
        source,
        provenance,
        points,
        timingValidationState,
        referenceSessionId,
        clockAlignmentResidualMillis,
    ).hashCode()
}

data class EcgSequenceInspection(
    val callbackCount: Int,
    val discontinuities: Int,
    val duplicateCallbacks: Int,
    val timestampRegressions: Int,
) {
    val continuous: Boolean
        get() = callbackCount > 0 && discontinuities == 0 &&
            duplicateCallbacks == 0 && timestampRegressions == 0
}

object SamsungEcgSequenceInspector {
    fun inspect(events: List<SamsungRawEcgEvent>): EcgSequenceInspection {
        if (events.isEmpty()) return EcgSequenceInspection(0, 0, 0, 0)
        var discontinuities = 0
        var duplicates = 0
        var timestampRegressions = 0
        events.zipWithNext().forEach { (previous, current) ->
            val expected = (previous.sequence + 1) and 0xFF
            when {
                current.sequence == previous.sequence -> duplicates++
                current.sequence != expected -> discontinuities++
            }
            if (current.points.first().sourceTimestampEpochMillis <
                previous.points.last().sourceTimestampEpochMillis
            ) {
                timestampRegressions++
            }
        }
        return EcgSequenceInspection(
            callbackCount = events.size,
            discontinuities = discontinuities,
            duplicateCallbacks = duplicates,
            timestampRegressions = timestampRegressions,
        )
    }
}

/**
 * Cross-modal timing is experimental and stays locked until a reference device
 * validates physical time alignment. This does not authorize blood-pressure,
 * pulse-transit-time, rhythm, diagnosis, treatment, or emergency claims.
 */
object SamsungEcgTimingUsePolicy {
    private const val maximumAlignmentResidualMillis = 4.0

    fun canUseForExperimentalCrossModalTiming(
        events: List<SamsungRawEcgEvent>,
    ): Boolean {
        if (events.size < 2) return false
        if (!SamsungEcgSequenceInspector.inspect(events).continuous) return false
        return events.all { event ->
            event.timingValidationState == EcgTimingValidationState.REFERENCE_ALIGNED &&
                event.clockAlignmentResidualMillis != null &&
                event.clockAlignmentResidualMillis <= maximumAlignmentResidualMillis &&
                !event.hasLeadOff &&
                !event.hasSaturation &&
                event.embeddedGreenPpgSamples.isNotEmpty() &&
                abs(event.receivedAtEpochMillis - event.points.last().sourceTimestampEpochMillis) < 5_000L
        }
    }
}
