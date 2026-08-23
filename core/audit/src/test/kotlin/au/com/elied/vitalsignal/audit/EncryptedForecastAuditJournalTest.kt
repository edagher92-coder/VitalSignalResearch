package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.model.HealthForecast
import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.LocalEncryptedRecord
import au.com.elied.vitalsignal.storage.StorageAppendResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedForecastAuditJournalTest {
    @Test
    fun binaryCodecRoundTripsEndpointSchemaAndPointAssessmentBindingExactly() {
        val committed = committedEvent()
        val outcomeEvent = ForecastOutcomeStoredEvent(outcome())

        assertEquals(committed, ForecastAuditBinaryCodec.decode(ForecastAuditBinaryCodec.encode(committed)))
        assertEquals(
            outcomeEvent,
            ForecastAuditBinaryCodec.decode(ForecastAuditBinaryCodec.encode(outcomeEvent)),
        )
    }

    @Test
    fun completeForecastLifecycleSurvivesNewStoreJournalAndLedgerInstances() {
        val root = Files.createTempDirectory("forecast-audit-restart")
        val key = aesKey()
        val firstLedger = ledger(root, key)

        assertTrue(
            firstLedger.commit("commit-unique-1", forecast(), FEATURE_HASH) is
                ForecastLedgerMutationResult.Applied,
        )
        assertTrue(
            firstLedger.storePreRevealCheckIn(checkIn()) is ForecastLedgerMutationResult.Applied,
        )
        assertTrue(
            firstLedger.reveal("reveal-unique-1", FORECAST_ID, REVEALED_AT) is
                ForecastLedgerMutationResult.Applied,
        )
        assertTrue(
            firstLedger.recordOutcome(outcome()) is ForecastLedgerMutationResult.Applied,
        )

        val reopenedStore = store(root, key)
        val reopenedJournal = EncryptedForecastAuditJournal(reopenedStore)
        val reopenedLedger = ProspectiveForecastLedger(reopenedJournal)
        val view = reopenedLedger.view(FORECAST_ID, TARGET_END + 1L) as RevealedForecastView

        assertEquals(ForecastLedgerAvailability.AVAILABLE, reopenedLedger.status().availability)
        assertEquals(ProspectiveForecastState.RESOLVED, view.state)
        assertEquals(UNIQUE_PROBABILITY, view.probability)
        assertEquals(1.0, view.observedOutcome)
        assertEquals(
            4,
            (reopenedJournal.recover() as ForecastJournalRecoveryResult.Recovered).records.size,
        )
    }

    @Test
    fun encryptedFilesContainNoUniquePlaintextOutcomeModelOrProbabilityStrings() {
        val root = Files.createTempDirectory("forecast-audit-plaintext")
        val key = aesKey()
        val ledger = ledger(root, key)
        ledger.commit("commit-unique-1", forecast(), FEATURE_HASH)
        ledger.storePreRevealCheckIn(checkIn())
        ledger.reveal("reveal-unique-1", FORECAST_ID, REVEALED_AT)
        ledger.recordOutcome(outcome())

        val committedBytes = Files.list(root.resolve("records")).use { paths ->
            val bytes = mutableListOf<ByteArray>()
            paths.filter { it.fileName.toString().endsWith(".vsr") }
                .forEach { bytes += Files.readAllBytes(it) }
            bytes
        }

        assertEquals(4, committedBytes.size)
        listOf(UNIQUE_OUTCOME, UNIQUE_MODEL, UNIQUE_PROBABILITY_TEXT).forEach { secret ->
            assertFalse(
                committedBytes.any { it.containsSubsequence(secret.toByteArray()) },
            )
        }
    }

    @Test
    fun flippedCiphertextMakesJournalAndLedgerUnavailable() {
        val root = Files.createTempDirectory("forecast-audit-tamper")
        val key = aesKey()
        val ledger = ledger(root, key)
        ledger.commit("commit-unique-1", forecast(), FEATURE_HASH)

        val committed = Files.list(root.resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".vsr") }
                .findFirst()
                .orElseThrow()
        }
        val bytes = Files.readAllBytes(committed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(committed, bytes, WRITE)

        val reopenedJournal = EncryptedForecastAuditJournal(store(root, key))
        val recovery = reopenedJournal.recover()
        val reopenedLedger = ProspectiveForecastLedger(reopenedJournal)

        assertTrue(recovery is ForecastJournalRecoveryResult.Unreadable)
        assertEquals(ForecastLedgerAvailability.UNAVAILABLE, reopenedLedger.status().availability)
        assertTrue(reopenedLedger.view(FORECAST_ID, REVEALED_AT) is UnavailableForecastView)
    }

    @Test
    fun failedDuplicateAndConflictingAppendsDoNotAdvanceRevision() {
        val root = Files.createTempDirectory("forecast-audit-conflict")
        val journal = EncryptedForecastAuditJournal(store(root, aesKey()))
        val committed = committedEvent()

        val wrongRevision = journal.append(committed, expectedRevision = 1L)
        assertTrue(wrongRevision is ForecastJournalAppendResult.RevisionConflict)
        assertEquals(0, recoveredRecords(journal).size)

        assertTrue(
            journal.append(committed, expectedRevision = 0L) is
                ForecastJournalAppendResult.Appended,
        )
        assertTrue(
            journal.append(committed, expectedRevision = 1L) is
                ForecastJournalAppendResult.ExactDuplicate,
        )

        val conflict = committed.copy(
            forecast = committed.forecast.copy(probability = 0.70),
        )
        assertTrue(
            journal.append(conflict, expectedRevision = 1L) is
                ForecastJournalAppendResult.ConflictingReplay,
        )
        assertEquals(1, recoveredRecords(journal).size)
        assertEquals(committed, recoveredRecords(journal).single().event)
    }

    @Test
    fun unknownSchemaAndTrailingPayloadBytesAreUnreadable() {
        val source = ForecastAuditBinaryCodec.encode(committedEvent())

        val unknownSchema = source.copyOf().also { it[5] = 99 }
        val unknownRoot = Files.createTempDirectory("forecast-audit-schema")
        val unknownStore = store(unknownRoot, aesKey())
        assertTrue(
            unknownStore.append(rawRecord(unknownSchema)) is StorageAppendResult.Accepted,
        )
        assertTrue(
            EncryptedForecastAuditJournal(unknownStore).recover() is
                ForecastJournalRecoveryResult.Unreadable,
        )

        val trailingRoot = Files.createTempDirectory("forecast-audit-trailing")
        val trailingStore = store(trailingRoot, aesKey())
        assertTrue(
            trailingStore.append(rawRecord(source + byteArrayOf(1))) is StorageAppendResult.Accepted,
        )
        assertTrue(
            EncryptedForecastAuditJournal(trailingStore).recover() is
                ForecastJournalRecoveryResult.Unreadable,
        )
    }

    private fun ledger(root: Path, key: SecretKey) = ProspectiveForecastLedger(
        EncryptedForecastAuditJournal(store(root, key)),
    )

    private fun store(root: Path, key: SecretKey) = EncryptedAppendOnlyRecordStore(
        rootDirectory = root,
        secretKey = key,
        keyId = "forecast-audit-key-v1",
        secureRandom = SecureRandom(),
        maxPayloadBytes = ForecastAuditBinaryCodec.MAX_ENCODED_BYTES,
    )

    private fun committedEvent() = ForecastCommittedEvent(
        eventId = "commit-unique-1",
        forecast = forecast(),
        canonicalFeatureSnapshotSha256 = FEATURE_HASH,
    )

    private fun checkIn() = PreRevealContextCheckIn(
        eventId = "checkin-unique-1",
        forecastId = FORECAST_ID,
        recordedAtEpochMillis = CHECK_IN_AT,
        contextSnapshotSha256 = CONTEXT_HASH,
    )

    private fun outcome() = ForecastOutcomeObservation(
        eventId = "outcome-unique-1",
        forecastId = FORECAST_ID,
        endpointId = ENDPOINT.id,
        endpointVersion = ENDPOINT.version,
        endpointDefinitionSha256 = ENDPOINT.definitionSha256,
        targetStartEpochMillis = TARGET_START,
        targetEndEpochMillis = TARGET_END,
        sourceAssessmentAtEpochMillis = TARGET_START,
        observedAtEpochMillis = TARGET_END,
        observedOutcome = 1.0,
        outcomeRecordSha256 = OUTCOME_HASH,
    )

    private fun forecast() = HealthForecast(
        id = FORECAST_ID,
        createdAtEpochMillis = CREATED_AT,
        endpoint = ENDPOINT,
        probability = UNIQUE_PROBABILITY,
        lowerBound = 0.61234567891,
        upperBound = 0.84567891234,
        confidence = 0.70123456789,
        modelVersion = UNIQUE_MODEL,
        featureSnapshotIds = listOf("feature-unique-nebula-7719"),
        featureSchema = FEATURE_SCHEMA,
        cutoffEpochMillis = CREATED_AT,
        targetStartEpochMillis = TARGET_START,
        targetEndEpochMillis = TARGET_END,
        policyVersion = "policy-secret-orbit-99117",
        intervalCoverage = 0.80,
        featureSnapshotHash = FEATURE_HASH,
    )

    private fun rawRecord(payload: ByteArray) = LocalEncryptedRecord(
        recordId = "commit-unique-1",
        sequence = 1L,
        createdEpochMillis = CREATED_AT,
        contentType = EncryptedForecastAuditJournal.CONTENT_TYPE,
        payload = payload,
    )

    private fun recoveredRecords(
        journal: EncryptedForecastAuditJournal,
    ): List<ForecastJournalRecord> =
        (journal.recover() as ForecastJournalRecoveryResult.Recovered).records

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private companion object {
        const val FORECAST_ID = "forecast-encrypted-unique-1"
        const val CREATED_AT = 1_000L
        const val CHECK_IN_AT = 2_000L
        const val REVEALED_AT = 3_000L
        const val TARGET_START = 10_000L
        const val TARGET_END = 20_000L
        const val UNIQUE_OUTCOME = "outcome-secret-zephyr-948271"
        const val UNIQUE_MODEL = "model-secret-quasar-681359"
        const val UNIQUE_PROBABILITY = 0.73123456789
        const val UNIQUE_PROBABILITY_TEXT = "0.73123456789"
        val FEATURE_HASH = "a".repeat(64)
        val CONTEXT_HASH = "b".repeat(64)
        val OUTCOME_HASH = "c".repeat(64)
        val ENDPOINT = ForecastEndpointDefinition.freeze(
            id = "encrypted-audit-fixture-point",
            version = "1.0.0",
            displayLabel = UNIQUE_OUTCOME,
            positiveClassDefinition = "Frozen encrypted audit fixture binary endpoint.",
            windowSemantics = ForecastWindowSemantics.POINT_ASSESSMENT,
            targetStartOffsetMillis = TARGET_START - CREATED_AT,
            targetEndOffsetMillis = TARGET_END - CREATED_AT,
        )
        val FEATURE_SCHEMA = ForecastFeatureSchemaDefinition.freeze(
            id = "encrypted-audit-fixture-schema",
            version = "1.0.0",
            featureVersions = mapOf("fixture" to "1.0.0"),
            standardizationProtocol = "Deterministic encrypted audit test fixture.",
        )
    }
}
