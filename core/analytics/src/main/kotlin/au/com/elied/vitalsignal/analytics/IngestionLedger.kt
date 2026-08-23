package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality

enum class QuarantineReason {
    REPLAY_CONFLICT,
    OUT_OF_ORDER,
    BAD_UNIT,
    BAD_TIMESTAMP,
    INVALID_VALUE,
    FIRMWARE_TRANSITION,
    SCHEMA_TRANSITION,
}

data class IngestionPacket(
    val packetId: String,
    val sequence: Long,
    val schemaVersion: Int,
    val source: SensorSource,
    val deviceId: String,
    val firmwareVersion: String,
    val metric: SensorMetric,
    val unit: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val localOffsetMinutes: Int,
    val value: Double,
    val payloadSha256: String,
    val quality: SignalQuality,
) {
    init {
        require(packetId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(sequence >= 0)
        require(schemaVersion > 0)
        require(deviceId.isNotBlank())
        require(firmwareVersion.isNotBlank())
        require(localOffsetMinutes in -18 * 60..18 * 60)
        require(payloadSha256.matches(Regex("[a-fA-F0-9]{64}")))
    }
}

sealed interface IngestionResult {
    data class Accepted(val packet: IngestionPacket) : IngestionResult
    data class Duplicate(val canonicalPacketId: String) : IngestionResult
    data class Quarantined(
        val packet: IngestionPacket,
        val reason: QuarantineReason,
        val detail: String,
    ) : IngestionResult
}

/**
 * Append-only, in-memory acceptance model used by the simulator and unit tests.
 * The encrypted durable pilot store must implement the same semantics.
 */
class IngestionLedger {
    private val acceptedById = linkedMapOf<String, IngestionPacket>()
    private val quarantinedRecords = mutableListOf<IngestionResult.Quarantined>()
    private val packetIdBySequenceByDevice = mutableMapOf<String, MutableMap<Long, String>>()
    private val firmwareByDevice = mutableMapOf<String, String>()
    private val schemaByDevice = mutableMapOf<String, Int>()
    private val reviewedTransitions = linkedMapOf<String, ReviewedIngestionTransition>()

    @Synchronized
    fun append(packet: IngestionPacket): IngestionResult {
        acceptedById[packet.packetId]?.let { existing ->
            return if (existing.payloadSha256 == packet.payloadSha256) {
                IngestionResult.Duplicate(existing.packetId)
            } else {
                quarantine(packet, QuarantineReason.REPLAY_CONFLICT, "Packet ID was reused with different bytes")
            }
        }

        if (packet.endEpochMillis < packet.startEpochMillis) {
            return quarantine(packet, QuarantineReason.BAD_TIMESTAMP, "End time precedes start time")
        }
        if (!packet.value.isFinite()) {
            return quarantine(packet, QuarantineReason.INVALID_VALUE, "Measurement value is not finite")
        }
        if (packet.unit != packet.metric.unit) {
            return quarantine(
                packet,
                QuarantineReason.BAD_UNIT,
                "Expected ${packet.metric.unit}, received ${packet.unit}",
            )
        }

        val acceptedSequence = packetIdBySequenceByDevice[packet.deviceId]?.get(packet.sequence)
        if (acceptedSequence != null) {
            return quarantine(
                packet,
                QuarantineReason.OUT_OF_ORDER,
                "Sequence is already committed by packet $acceptedSequence",
            )
        }

        val previousFirmware = firmwareByDevice[packet.deviceId]
        if (previousFirmware != null && previousFirmware != packet.firmwareVersion) {
            return quarantine(
                packet,
                QuarantineReason.FIRMWARE_TRANSITION,
                "Firmware changed from $previousFirmware to ${packet.firmwareVersion}",
            )
        }

        val previousSchema = schemaByDevice[packet.deviceId]
        if (previousSchema != null && previousSchema != packet.schemaVersion) {
            return quarantine(
                packet,
                QuarantineReason.SCHEMA_TRANSITION,
                "Schema changed from $previousSchema to ${packet.schemaVersion}",
            )
        }

        acceptedById[packet.packetId] = packet
        packetIdBySequenceByDevice.getOrPut(packet.deviceId, ::linkedMapOf)[packet.sequence] = packet.packetId
        firmwareByDevice[packet.deviceId] = packet.firmwareVersion
        schemaByDevice[packet.deviceId] = packet.schemaVersion
        return IngestionResult.Accepted(packet)
    }

    /**
     * Explicitly installs a reviewed firmware/schema generation. A mismatch never advances itself,
     * so repeated packets from a new firmware remain quarantined until this transition is recorded.
     */
    @Synchronized
    fun installReviewedTransition(
        transition: ReviewedIngestionTransition,
    ): IngestionTransitionResult {
        reviewedTransitions[transition.transitionId]?.let { existing ->
            return if (existing == transition) {
                IngestionTransitionResult.Duplicate(transition.transitionId)
            } else {
                IngestionTransitionResult.Rejected("Transition ID was reused with different content")
            }
        }
        val currentFirmware = firmwareByDevice[transition.deviceId]
            ?: return IngestionTransitionResult.Rejected("Device has no accepted generation")
        val currentSchema = schemaByDevice.getValue(transition.deviceId)
        if (
            currentFirmware != transition.fromFirmwareVersion ||
            currentSchema != transition.fromSchemaVersion
        ) {
            return IngestionTransitionResult.Rejected("Reviewed transition does not match the active generation")
        }
        if (
            transition.fromFirmwareVersion == transition.toFirmwareVersion &&
            transition.fromSchemaVersion == transition.toSchemaVersion
        ) {
            return IngestionTransitionResult.Rejected("Reviewed transition must change firmware or schema")
        }
        reviewedTransitions[transition.transitionId] = transition
        firmwareByDevice[transition.deviceId] = transition.toFirmwareVersion
        schemaByDevice[transition.deviceId] = transition.toSchemaVersion
        return IngestionTransitionResult.Installed(
            transitionId = transition.transitionId,
            baselineRewarmRequired = true,
        )
    }

    @Synchronized
    fun acceptedSnapshot(): List<IngestionPacket> = acceptedById.values.toList()

    @Synchronized
    fun quarantinedSnapshot(): List<IngestionResult.Quarantined> = quarantinedRecords.toList()

    @Synchronized
    fun reviewedTransitionsSnapshot(): List<ReviewedIngestionTransition> =
        reviewedTransitions.values.toList()

    private fun quarantine(
        packet: IngestionPacket,
        reason: QuarantineReason,
        detail: String,
    ): IngestionResult.Quarantined = IngestionResult.Quarantined(packet, reason, detail).also {
        quarantinedRecords += it
    }
}

data class ReviewedIngestionTransition(
    val transitionId: String,
    val deviceId: String,
    val fromFirmwareVersion: String,
    val toFirmwareVersion: String,
    val fromSchemaVersion: Int,
    val toSchemaVersion: Int,
    val reviewedAtEpochMillis: Long,
) {
    init {
        require(transitionId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(deviceId.isNotBlank())
        require(fromFirmwareVersion.isNotBlank() && toFirmwareVersion.isNotBlank())
        require(fromSchemaVersion > 0 && toSchemaVersion > 0)
        require(reviewedAtEpochMillis >= 0L)
    }
}

sealed interface IngestionTransitionResult {
    data class Installed(
        val transitionId: String,
        val baselineRewarmRequired: Boolean,
    ) : IngestionTransitionResult

    data class Duplicate(val transitionId: String) : IngestionTransitionResult
    data class Rejected(val reason: String) : IngestionTransitionResult
}
