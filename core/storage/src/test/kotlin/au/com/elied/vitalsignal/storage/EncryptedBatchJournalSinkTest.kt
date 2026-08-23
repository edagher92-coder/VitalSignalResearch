package au.com.elied.vitalsignal.storage

import au.com.elied.vitalsignal.transport.AesGcmBatchPayloadAuthenticator
import au.com.elied.vitalsignal.transport.AuthenticatedBatchPayloadCipher
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.BatchReceiverCoordinator
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import au.com.elied.vitalsignal.transport.TransportKeyResolver
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedBatchJournalSinkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val transportKey = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
    private val authenticator = AesGcmBatchPayloadAuthenticator(
        AuthenticatedBatchPayloadCipher(SecureRandom()),
        TransportKeyResolver { keyId -> if (keyId == "transport-key-1") transportKey else null },
    )

    @Test
    fun commitBeforeAckSurvivesRestartAndLostAckIsIdempotentlyReissued() {
        val root = temporaryFolder.newFolder("receipt-restart").toPath()
        val storageKey = aesKey()
        val first = receiver(root, storageKey)
        val wire = wire("batch-1", sequence = 9)

        val original = first.receive(wire, 2_000L)
        val afterRestart = receiver(root, storageKey).receive(wire, 3_000L)

        assertEquals(ReceiptDisposition.ACK, original.disposition)
        assertEquals(ReceiptReason.DURABLY_COMMITTED, original.reason)
        assertEquals(ReceiptDisposition.ACK, afterRestart.disposition)
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, afterRestart.reason)
        assertEquals(original.durableCommitToken, afterRestart.durableCommitToken)
    }

    @Test
    fun delayedNonOverlappingOrdinalIsAcceptedButReusedOrdinalIsRejected() {
        val root = temporaryFolder.newFolder("reordered").toPath()
        val receiver = receiver(root, aesKey())

        val high = receiver.receive(wire("batch-high", 10), 2_000L)
        val delayed = receiver.receive(wire("batch-delayed", 8), 3_000L)
        val overlap = receiver.receive(wire("batch-overlap", 8), 4_000L)

        assertEquals(ReceiptDisposition.ACK, high.disposition)
        assertEquals(ReceiptDisposition.ACK, delayed.disposition)
        assertEquals(ReceiptDisposition.NACK, overlap.disposition)
        assertEquals(ReceiptReason.ID_CONFLICT, overlap.reason)
    }

    @Test
    fun tamperedEncryptedJournalFailsClosedAndCannotAckNewData() {
        val root = temporaryFolder.newFolder("tampered-journal").toPath()
        val storageKey = aesKey()
        val receiver = receiver(root, storageKey)
        assertEquals(ReceiptDisposition.ACK, receiver.receive(wire("batch-1", 1), 2_000L).disposition)
        val committed = Files.list(root.resolve("batches").resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".vsr") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(committed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(committed, bytes, WRITE)

        val result = receiver(root, storageKey).receive(wire("batch-2", 2), 3_000L)

        assertEquals(ReceiptDisposition.NACK, result.disposition)
        assertEquals(ReceiptReason.STORE_FAILURE, result.reason)
        assertTrue(result.durableCommitToken == null)
    }

    private fun receiver(root: java.nio.file.Path, storageKey: SecretKey): BatchReceiverCoordinator {
        val sink = EncryptedBatchJournalSink(
            batchStore = store(root.resolve("batches"), storageKey, "storage-batches"),
            quarantineStore = store(root.resolve("quarantine"), storageKey, "storage-quarantine"),
            payloadAuthenticator = authenticator,
        )
        return BatchReceiverCoordinator(sink, authenticator)
    }

    private fun store(root: java.nio.file.Path, key: SecretKey, keyId: String) =
        EncryptedAppendOnlyRecordStore(root, key, keyId, SecureRandom())

    private fun wire(batchId: String, sequence: Long): ByteArray = BatchEnvelopeCodec.encode(
        AuthenticatedBatchPayloadCipher(SecureRandom()).seal(
            batchId = batchId,
            sessionId = "session-1",
            deviceId = "watch-1",
            sequence = sequence,
            createdAtEpochMillis = 1_000L + sequence,
            contentSchemaVersion = 1,
            contentType = "sensor.features",
            plaintext = "fixture-$batchId".toByteArray(),
            keyId = "transport-key-1",
            secretKey = transportKey,
        ),
    )

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
