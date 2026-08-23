package au.com.elied.vitalsignal.storage

import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedAppendOnlyRecordStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun committedRecordsSurviveRestartAndContinueStrictSequence() {
        val root = temporaryFolder.newFolder("restart-store").toPath()
        val key = aesKey()
        val firstStore = store(root, key)

        assertTrue(firstStore.append(record("sim-001", 1, "sample-a")) is StorageAppendResult.Accepted)
        assertTrue(firstStore.append(record("sim-002", 2, "sample-b")) is StorageAppendResult.Accepted)

        val reopened = store(root, key)
        val recovered = reopened.recoveryReport()
        assertEquals(2, recovered.accepted.size)
        assertEquals(listOf(1L, 2L), recovered.accepted.map { it.record.sequence })
        assertArrayEquals("sample-a".toByteArray(), recovered.accepted.first().record.payloadCopy())
        assertTrue(recovered.quarantined.isEmpty())
        assertTrue(reopened.append(record("sim-003", 3, "sample-c")) is StorageAppendResult.Accepted)

        val fileNames = Files.list(root.resolve("records")).use { paths ->
            paths.map { it.fileName.toString() }.filter { it.endsWith(".vsr") }.sorted().toList()
        }
        assertEquals(3, fileNames.size)
        assertTrue(fileNames[0].startsWith("record-00000000000000000001-"))
        assertTrue(fileNames[1].startsWith("record-00000000000000000002-"))
        assertTrue(fileNames[2].startsWith("record-00000000000000000003-"))
        assertFalse(fileNames.any { it.contains("sim-00") })
    }

    @Test
    fun wrongKeyIdAndTamperingAreQuarantinedAndNeverAccepted() {
        val wrongKeyRoot = temporaryFolder.newFolder("wrong-key-store").toPath()
        val originalKey = aesKey()
        val original = store(wrongKeyRoot, originalKey, keyId = "pilot-key-a")
        assertTrue(original.append(record("sim-001", 1, "opaque-sample")) is StorageAppendResult.Accepted)

        val wrongKeyStore = store(wrongKeyRoot, aesKey(), keyId = "pilot-key-b")
        val wrongKeyReport = wrongKeyStore.recoveryReport()
        assertTrue(wrongKeyReport.accepted.isEmpty())
        assertEquals(RecoveryQuarantineReason.WRONG_KEY, wrongKeyReport.quarantined.single().reason)
        val blocked = wrongKeyStore.append(record("sim-002", 2, "must-not-commit"))
        assertEquals(
            AppendQuarantineReason.RECOVERY_BLOCKED,
            (blocked as StorageAppendResult.Quarantined).reason,
        )

        val sameIdWrongSecret = store(wrongKeyRoot, aesKey(), keyId = "pilot-key-a")
        val sameIdWrongSecretReport = sameIdWrongSecret.recoveryReport()
        assertTrue(sameIdWrongSecretReport.accepted.isEmpty())
        assertEquals(
            RecoveryQuarantineReason.AUTHENTICATION_FAILED,
            sameIdWrongSecretReport.quarantined.single().reason,
        )

        val tamperRoot = temporaryFolder.newFolder("tamper-store").toPath()
        val tamperKey = aesKey()
        val tamperStore = store(tamperRoot, tamperKey)
        assertTrue(tamperStore.append(record("sim-001", 1, "opaque-sample")) is StorageAppendResult.Accepted)
        val committed = Files.list(tamperRoot.resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".vsr") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(committed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(committed, bytes, WRITE)

        val tamperedReport = store(tamperRoot, tamperKey).recoveryReport()
        assertTrue(tamperedReport.accepted.isEmpty())
        assertEquals(
            RecoveryQuarantineReason.AUTHENTICATION_FAILED,
            tamperedReport.quarantined.single().reason,
        )
    }

    @Test
    fun identicalDuplicateIsIdempotentButReusedIdWithChangedBytesConflicts() {
        val root = temporaryFolder.newFolder("duplicate-store").toPath()
        val store = store(root, aesKey())
        val canonical = record("sim-001", 1, "sample-a")

        assertTrue(store.append(canonical) is StorageAppendResult.Accepted)
        val duplicate = store.append(canonical)
        assertEquals(1L, (duplicate as StorageAppendResult.Duplicate).canonicalSequence)

        val conflict = store.append(record("sim-001", 2, "changed-sample"))
        assertEquals(
            AppendQuarantineReason.REPLAY_CONFLICT,
            (conflict as StorageAppendResult.Quarantined).reason,
        )
        assertEquals(1, store.recoveryReport().accepted.size)
        assertTrue(store.append(record("sim-002", 2, "sample-b")) is StorageAppendResult.Accepted)
    }

    @Test
    fun gapsReplaysAndOutOfOrderSequencesDoNotAdvanceTheLedger() {
        val root = temporaryFolder.newFolder("ordering-store").toPath()
        val store = store(root, aesKey())

        assertQuarantined(store.append(record("sim-002", 2)), AppendQuarantineReason.OUT_OF_SEQUENCE)
        assertTrue(store.append(record("sim-001", 1)) is StorageAppendResult.Accepted)
        assertQuarantined(store.append(record("sim-003", 3)), AppendQuarantineReason.OUT_OF_SEQUENCE)
        assertQuarantined(store.append(record("other-id", 1)), AppendQuarantineReason.OUT_OF_SEQUENCE)
        assertTrue(store.append(record("sim-002", 2)) is StorageAppendResult.Accepted)
        assertTrue(store.append(record("sim-003", 3)) is StorageAppendResult.Accepted)
        assertEquals(listOf(1L, 2L, 3L), store.recoveryReport().accepted.map { it.record.sequence })
    }

    @Test
    fun partialTemporaryFileIsIgnoredAcrossRestartAndCannotBecomeARecord() {
        val root = temporaryFolder.newFolder("partial-store").toPath()
        val key = aesKey()
        val store = store(root, key)
        assertTrue(store.append(record("sim-001", 1)) is StorageAppendResult.Accepted)
        Files.write(root.resolve("records").resolve(".pending-crash.tmp"), byteArrayOf(1, 2, 3))

        val reopened = store(root, key)
        val report = reopened.recoveryReport()
        assertEquals(1, report.accepted.size)
        assertTrue(report.quarantined.isEmpty())
        assertEquals(listOf(".pending-crash.tmp"), report.ignoredTemporaryFiles)
        assertTrue(reopened.append(record("sim-002", 2)) is StorageAppendResult.Accepted)
    }

    @Test
    fun missingEarlierCommittedSequenceQuarantinesTheRemainingChain() {
        val root = temporaryFolder.newFolder("gap-recovery-store").toPath()
        val key = aesKey()
        val store = store(root, key)
        assertTrue(store.append(record("sim-001", 1)) is StorageAppendResult.Accepted)
        assertTrue(store.append(record("sim-002", 2)) is StorageAppendResult.Accepted)
        val firstFile = Files.list(root.resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().startsWith("record-00000000000000000001-") }
                .findFirst()
                .orElseThrow()
        }
        Files.delete(firstFile)

        val report = store(root, key).recoveryReport()
        assertTrue(report.accepted.isEmpty())
        assertEquals(RecoveryQuarantineReason.OUT_OF_SEQUENCE, report.quarantined.single().reason)
    }

    @Test
    fun configuredPayloadBoundRejectsWithoutCreatingACommittedFile() {
        val root = temporaryFolder.newFolder("bounded-store").toPath()
        val store = store(root, aesKey(), maxPayloadBytes = 8)
        val oversized = LocalEncryptedRecord(
            recordId = "sim-001",
            sequence = 1,
            createdEpochMillis = 1_000L,
            contentType = "application/vnd.vitalsignal.simulated.v1",
            payload = ByteArray(9) { 7 },
        )

        assertQuarantined(store.append(oversized), AppendQuarantineReason.PAYLOAD_TOO_LARGE)
        assertTrue(store.recoveryReport().accepted.isEmpty())
        val committedCount = Files.list(root.resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".vsr") }.count()
        }
        assertEquals(0L, committedCount)
    }

    @Test
    fun malformedCommittedFileIsQuarantinedAndBlocksAppend() {
        val root = temporaryFolder.newFolder("malformed-store").toPath()
        val records = root.resolve("records")
        Files.createDirectories(records)
        Files.write(
            records.resolve("record-00000000000000000001-aaaaaaaaaaaaaaaaaaaaaaaa.vsr"),
            byteArrayOf(1, 2, 3),
        )

        val store = store(root, aesKey())
        val report = store.recoveryReport()
        assertTrue(report.accepted.isEmpty())
        assertEquals(RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT, report.quarantined.single().reason)
        assertQuarantined(
            store.append(record("sim-001", 1)),
            AppendQuarantineReason.RECOVERY_BLOCKED,
        )
    }

    private fun assertQuarantined(result: StorageAppendResult, reason: AppendQuarantineReason) {
        assertEquals(reason, (result as StorageAppendResult.Quarantined).reason)
    }

    private fun store(
        root: java.nio.file.Path,
        key: SecretKey,
        keyId: String = "pilot-key-a",
        maxPayloadBytes: Int = LocalEncryptedRecord.ABSOLUTE_MAX_PAYLOAD_BYTES,
    ) = EncryptedAppendOnlyRecordStore(
        rootDirectory = root,
        secretKey = key,
        keyId = keyId,
        secureRandom = SecureRandom(),
        maxPayloadBytes = maxPayloadBytes,
    )

    private fun record(
        id: String,
        sequence: Long,
        payload: String = "simulated-sample",
    ) = LocalEncryptedRecord(
        recordId = id,
        sequence = sequence,
        createdEpochMillis = sequence * 1_000L,
        contentType = "application/vnd.vitalsignal.simulated.v1",
        payload = payload.toByteArray(),
    )

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
