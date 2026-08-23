package au.com.elied.vitalsignal.phone.data.integration

import au.com.elied.vitalsignal.governance.PilotCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReconcilerTest {
    @Test
    fun retryIsIdempotentAndNewerRevisionUpdatesExactSourceRecord() {
        val first = record(revision = 1L, digest = "a".repeat(64))
        val newer = record(revision = 2L, digest = "b".repeat(64))

        val result = HistoryReconciler.apply(
            HistoryMergeState(),
            listOf(
                HistorySourceChange.Upsert(first),
                HistorySourceChange.Upsert(first),
                HistorySourceChange.Upsert(newer),
            ),
        )

        assertEquals(
            listOf(
                HistoryMergeAction.INSERTED,
                HistoryMergeAction.DUPLICATE_IGNORED,
                HistoryMergeAction.UPDATED,
            ),
            result.results.map(HistoryMergeResult::action),
        )
        assertEquals(2L, result.state.records.getValue(first.key).provenance.revision.sequence)
    }

    @Test
    fun sameRevisionDifferentPayloadIsRejectedWithoutOverwrite() {
        val first = record(revision = 1L, digest = "a".repeat(64))
        val conflict = record(revision = 1L, digest = "f".repeat(64))

        val result = HistoryReconciler.apply(
            HistoryMergeState(),
            listOf(HistorySourceChange.Upsert(first), HistorySourceChange.Upsert(conflict)),
        )

        assertEquals(HistoryMergeAction.CONFLICT_REJECTED, result.results.last().action)
        assertEquals("a".repeat(64), result.state.records.getValue(first.key).provenance.payloadSha256)
    }

    @Test
    fun deleteRetainsExactTombstoneAndBlocksStaleResurrection() {
        val first = record(revision = 1L)
        val deleted = delete(first.key, revision = 2L)
        val staleReplay = record(revision = 1L)

        val result = HistoryReconciler.apply(
            HistoryMergeState(),
            listOf(
                HistorySourceChange.Upsert(first),
                deleted,
                HistorySourceChange.Upsert(staleReplay),
            ),
        )

        assertEquals(HistoryMergeAction.DELETED, result.results[1].action)
        assertEquals(HistoryMergeAction.STALE_IGNORED, result.results[2].action)
        assertFalse(first.key in result.state.records)
        assertTrue(first.key in result.state.tombstones)
        assertEquals(4L, result.state.tombstones.getValue(first.key).consentGeneration)
    }

    @Test
    fun sourceConfirmedNewerRevisionCanReplaceTombstone() {
        val first = record(revision = 1L)
        val result = HistoryReconciler.apply(
            HistoryMergeState(),
            listOf(
                HistorySourceChange.Upsert(first),
                delete(first.key, revision = 2L),
                HistorySourceChange.Upsert(record(revision = 3L, digest = "d".repeat(64))),
            ),
        )

        assertEquals(HistoryMergeAction.INSERTED, result.results.last().action)
        assertTrue(first.key in result.state.records)
        assertFalse(first.key in result.state.tombstones)
    }

    @Test
    fun mergeStateSnapshotsCallerOwnedMaps() {
        val record = record(revision = 1L)
        val mutableRecords = mutableMapOf(record.key to record)
        val mutableTombstones = mutableMapOf<SourceRecordKey, HistoryTombstone>()
        val state = HistoryMergeState(mutableRecords, mutableTombstones)

        mutableRecords.clear()
        mutableTombstones[record.key] = HistoryTombstone(
            key = record.key,
            revision = SourceRevision(2L, "delete-2"),
            participantPseudonym = "participant-1",
            sourceDeletedAtEpochMillis = 30_002L,
            retrievedAtEpochMillis = 40_002L,
            adapterVersion = "history-adapter-v1",
            consentGeneration = 4L,
            pilotProtocolId = "pilot-protocol-1",
            validationReceiptId = "validation-hc-1",
            sourceChangeCursorDigest = "e".repeat(64),
        )

        assertTrue(record.key in state.records)
        assertTrue(state.tombstones.isEmpty())
    }

    private fun record(
        revision: Long,
        digest: String = "a".repeat(64),
    ) = CanonicalHistoryRecord(
        participantPseudonym = "participant-1",
        concept = CodedConcept("http://loinc.org", "8867-4", "Heart rate"),
        clinicalTime = ClinicalTimeRange(10_000L, 10_000L, 600),
        value = HistoryValue.Quantity(72.0, MeasurementUnit.ucum("/min")),
        provenance = HistoryProvenance(
            sourceKey = SourceRecordKey(
                HistorySourceKind.HEALTH_CONNECT,
                "health-connect",
                "record-1",
            ),
            revision = SourceRevision(revision, "native-$revision"),
            sourceCreatedAtEpochMillis = 9_000L,
            sourceUpdatedAtEpochMillis = 10_000L + revision,
            retrievedAtEpochMillis = 20_000L + revision,
            adapterVersion = "history-adapter-v1",
            pilotCapability = PilotCapability.PHONE_HEALTH_CONNECT_HISTORY,
            consentGeneration = 4L,
            pilotProtocolId = "pilot-protocol-1",
            validationReceiptId = "validation-hc-1",
            sourceDevice = SourceDeviceDescriptor(
                SourceDeviceKind.WATCH,
                "Samsung",
                "Galaxy Watch Ultra2 fixture",
                "fixture-fw-1",
                "watch-pseudonym-1",
                "fixture.health.origin",
            ),
            payloadSha256 = digest,
            sourceChangeCursorDigest = "c".repeat(64),
        ),
    )

    private fun delete(
        key: SourceRecordKey,
        revision: Long,
    ) = HistorySourceChange.Delete(
        key = key,
        revision = SourceRevision(revision, "delete-$revision"),
        participantPseudonym = "participant-1",
        sourceDeletedAtEpochMillis = 30_000L + revision,
        retrievedAtEpochMillis = 40_000L + revision,
        adapterVersion = "history-adapter-v1",
        consentGeneration = 4L,
        pilotProtocolId = "pilot-protocol-1",
        validationReceiptId = "validation-hc-1",
        sourceChangeCursorDigest = "e".repeat(64),
    )
}
