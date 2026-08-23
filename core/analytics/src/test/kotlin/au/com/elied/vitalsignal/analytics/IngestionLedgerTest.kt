package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionLedgerTest {
    @Test
    fun identicalRetryIsIdempotentButChangedBytesAreQuarantined() {
        val ledger = IngestionLedger()
        val packet = packet(id = "packet-1", sequence = 1)

        assertTrue(ledger.append(packet) is IngestionResult.Accepted)
        assertTrue(ledger.append(packet) is IngestionResult.Duplicate)
        val conflict = ledger.append(packet.copy(payloadSha256 = "b".repeat(64)))

        assertEquals(QuarantineReason.REPLAY_CONFLICT, (conflict as IngestionResult.Quarantined).reason)
        assertEquals(1, ledger.acceptedSnapshot().size)
    }

    @Test
    fun delayedSequenceIsAcceptedButOverlapBadUnitAndUnreviewedTransitionsAreQuarantined() {
        val ledger = IngestionLedger()
        ledger.append(packet(id = "packet-1", sequence = 10))

        val delayed = ledger.append(packet(id = "packet-2", sequence = 9))
        val overlap = ledger.append(packet(id = "packet-overlap", sequence = 9))
        val badUnit = ledger.append(packet(id = "packet-3", sequence = 11).copy(unit = "kg"))
        val firmware = ledger.append(
            packet(id = "packet-4", sequence = 11).copy(firmwareVersion = "2.0"),
        )
        val schema = ledger.append(packet(id = "packet-5", sequence = 11).copy(schemaVersion = 2))

        assertTrue(delayed is IngestionResult.Accepted)
        assertEquals(QuarantineReason.OUT_OF_ORDER, (overlap as IngestionResult.Quarantined).reason)
        assertEquals(QuarantineReason.BAD_UNIT, (badUnit as IngestionResult.Quarantined).reason)
        assertEquals(QuarantineReason.FIRMWARE_TRANSITION, (firmware as IngestionResult.Quarantined).reason)
        assertEquals(QuarantineReason.SCHEMA_TRANSITION, (schema as IngestionResult.Quarantined).reason)
    }

    @Test
    fun reviewedGenerationTransitionAllowsNewFirmwareAndRequiresBaselineRewarm() {
        val ledger = IngestionLedger()
        assertTrue(ledger.append(packet(id = "packet-1", sequence = 1)) is IngestionResult.Accepted)
        val newGeneration = packet(id = "packet-2", sequence = 2).copy(
            firmwareVersion = "2.0",
            schemaVersion = 2,
        )
        assertEquals(
            QuarantineReason.FIRMWARE_TRANSITION,
            (ledger.append(newGeneration) as IngestionResult.Quarantined).reason,
        )

        val installed = ledger.installReviewedTransition(
            ReviewedIngestionTransition(
                transitionId = "transition-1",
                deviceId = "sim-watch",
                fromFirmwareVersion = "1.0",
                toFirmwareVersion = "2.0",
                fromSchemaVersion = 1,
                toSchemaVersion = 2,
                reviewedAtEpochMillis = 2_000L,
            ),
        ) as IngestionTransitionResult.Installed

        assertTrue(installed.baselineRewarmRequired)
        assertTrue(ledger.append(newGeneration) is IngestionResult.Accepted)
        val oldGeneration = ledger.append(packet(id = "packet-3", sequence = 3))
        assertEquals(
            QuarantineReason.FIRMWARE_TRANSITION,
            (oldGeneration as IngestionResult.Quarantined).reason,
        )
    }

    private fun packet(id: String, sequence: Long) = IngestionPacket(
        packetId = id,
        sequence = sequence,
        schemaVersion = 1,
        source = SensorSource.SIMULATOR,
        deviceId = "sim-watch",
        firmwareVersion = "1.0",
        metric = SensorMetric.HEART_RATE,
        unit = "bpm",
        startEpochMillis = sequence * 1_000L,
        endEpochMillis = sequence * 1_000L + 999L,
        localOffsetMinutes = 600,
        value = 60.0,
        payloadSha256 = "a".repeat(64),
        quality = SignalQuality(score = 0.95),
    )
}
