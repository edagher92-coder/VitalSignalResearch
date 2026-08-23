package au.com.elied.vitalsignal.transport

import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchReceiverCoordinatorTest {
    @Test
    fun acknowledgementExistsOnlyAfterDurableCommit() {
        val sink = RecordingDurableSink(failNextCommit = true)
        val receiver = receiver(sink)
        val wire = wire(batchId = "batch-1", sequence = 1)

        val failed = receiver.receive(wire, 1_800_000_000_100)
        val committed = receiver.receive(wire, 1_800_000_000_200)

        assertEquals(ReceiptDisposition.NACK, failed.disposition)
        assertEquals(ReceiptReason.STORE_FAILURE, failed.reason)
        assertNull(failed.durableCommitToken)
        assertEquals(QuarantineDisposition.RECORDED, failed.quarantineDisposition)
        assertEquals(ReceiptDisposition.ACK, committed.disposition)
        assertEquals(ReceiptReason.DURABLY_COMMITTED, committed.reason)
        assertEquals("commit-batch-1", committed.durableCommitToken)
        assertEquals(1, sink.durableRecords.size)
    }

    @Test
    fun byteIdenticalDuplicateIsIdempotentlyAcknowledged() {
        val sink = RecordingDurableSink()
        val receiver = receiver(sink)
        val wire = wire(batchId = "batch-1", sequence = 1)

        val first = receiver.receive(wire, 1_800_000_000_100)
        val duplicate = receiver.receive(wire.copyOf(), 1_800_000_000_200)

        assertEquals(ReceiptReason.DURABLY_COMMITTED, first.reason)
        assertEquals(ReceiptDisposition.ACK, duplicate.disposition)
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, duplicate.reason)
        assertEquals(first.durableCommitToken, duplicate.durableCommitToken)
        assertEquals(1, sink.durableRecords.size)
        assertTrue(sink.quarantines.isEmpty())
    }

    @Test
    fun conflictingBatchIdAndOutOfOrderSequenceAreNackedAndQuarantined() {
        val sink = RecordingDurableSink()
        val receiver = receiver(sink)
        receiver.receive(wire(batchId = "batch-1", sequence = 10), 1_800_000_000_100)

        val conflict = receiver.receive(
            wire(batchId = "batch-1", sequence = 11, payload = byteArrayOf(99)),
            1_800_000_000_200,
        )
        val outOfOrder = receiver.receive(
            wire(batchId = "batch-2", sequence = 9),
            1_800_000_000_300,
        )

        assertNack(conflict, ReceiptReason.ID_CONFLICT)
        assertNack(outOfOrder, ReceiptReason.OUT_OF_ORDER)
        assertEquals(2, sink.quarantines.size)
        assertEquals(setOf(ReceiptReason.ID_CONFLICT, ReceiptReason.OUT_OF_ORDER), sink.quarantines.map { it.reason }.toSet())
    }

    @Test
    fun checksumCorruptionAndTrailingBytesNeverReachCommit() {
        val sink = RecordingDurableSink()
        val receiver = receiver(sink)
        val good = wire(batchId = "batch-1", sequence = 1)
        val corrupt = good.copyOf().also {
            it[it.size - BatchWireLimits.SHA_256_BYTES - 1] =
                (it[it.size - BatchWireLimits.SHA_256_BYTES - 1].toInt() xor 0x20).toByte()
        }

        val checksum = receiver.receive(corrupt, 1_800_000_000_100)
        val trailing = receiver.receive(good + 0x00, 1_800_000_000_200)

        assertNack(checksum, ReceiptReason.CHECKSUM_MISMATCH)
        assertNack(trailing, ReceiptReason.TRAILING_BYTES)
        assertEquals(0, sink.commitCalls)
        assertEquals(2, sink.quarantines.size)
    }

    @Test
    fun duplicateClaimWithDifferentDurableDigestIsTreatedAsConflict() {
        val wire = wire(batchId = "batch-1", sequence = 1)
        val sink = object : DurableBatchSink {
            val quarantines = mutableListOf<BatchQuarantineRecord>()

            override fun commit(candidate: BatchCommitCandidate): DurableCommitResult =
                DurableCommitResult.AlreadyCommitted("commit-old", "f".repeat(64))

            override fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult {
                quarantines += record
                return QuarantineWriteResult.Recorded("q-1")
            }
        }

        val result = receiver(sink).receive(wire, 1_800_000_000_100)

        assertNack(result, ReceiptReason.ID_CONFLICT)
        assertEquals(1, sink.quarantines.size)
    }

    @Test
    fun authenticatedMetadataTamperAndUnknownKeyNeverReachDurableCommit() {
        val sink = RecordingDurableSink()
        val original = BatchEnvelopeCodec.decode(wire(batchId = "batch-auth", sequence = 5))
        val metadataTampered = BatchEnvelopeCodec.encode(
            BatchEnvelope(
                batchId = original.batchId,
                sessionId = "different-session",
                deviceId = original.deviceId,
                sequence = original.sequence,
                createdAtEpochMillis = original.createdAtEpochMillis,
                contentSchemaVersion = original.contentSchemaVersion,
                contentType = original.contentType,
                payload = original.payloadCopy(),
            ),
        )
        val unknownKeyWire = BatchEnvelopeCodec.encode(
            AuthenticatedBatchPayloadCipher(SecureRandom()).seal(
                batchId = "batch-unknown",
                sessionId = "session-1",
                deviceId = "watch-1",
                sequence = 6,
                createdAtEpochMillis = 1_800_000_000_006,
                contentSchemaVersion = 1,
                contentType = "sensor.features",
                plaintext = byteArrayOf(9),
                keyId = "retired-key",
                secretKey = TEST_KEY,
            ),
        )

        val tampered = receiver(sink).receive(metadataTampered, 1_800_000_000_100)
        val unknown = receiver(sink).receive(unknownKeyWire, 1_800_000_000_200)

        assertNack(tampered, ReceiptReason.AUTHENTICATION_FAILED)
        assertNack(unknown, ReceiptReason.UNKNOWN_KEY)
        assertEquals(0, sink.commitCalls)
    }

    private fun assertNack(result: BatchAcknowledgement, reason: ReceiptReason) {
        assertEquals(ReceiptDisposition.NACK, result.disposition)
        assertEquals(reason, result.reason)
        assertNull(result.durableCommitToken)
        assertEquals(QuarantineDisposition.RECORDED, result.quarantineDisposition)
    }

    private fun receiver(sink: DurableBatchSink) = BatchReceiverCoordinator(
        durableSink = sink,
        payloadAuthenticator = AesGcmBatchPayloadAuthenticator(
            cipher = AuthenticatedBatchPayloadCipher(SecureRandom()),
            keyResolver = TransportKeyResolver { keyId -> if (keyId == TEST_KEY_ID) TEST_KEY else null },
        ),
    )

    private fun wire(
        batchId: String,
        sequence: Long,
        payload: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray = BatchEnvelopeCodec.encode(
        AuthenticatedBatchPayloadCipher(SecureRandom()).seal(
            batchId = batchId,
            sessionId = "session-1",
            deviceId = "watch-1",
            sequence = sequence,
            createdAtEpochMillis = 1_800_000_000_000 + sequence,
            contentSchemaVersion = 1,
            contentType = "sensor.features",
            plaintext = payload,
            keyId = TEST_KEY_ID,
            secretKey = TEST_KEY,
        ),
    )

    private companion object {
        const val TEST_KEY_ID = "test-pairing-key-1"
        val TEST_KEY = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    }
}

private class RecordingDurableSink(
    private var failNextCommit: Boolean = false,
) : DurableBatchSink {
    val durableRecords = linkedMapOf<String, BatchCommitCandidate>()
    val quarantines = mutableListOf<BatchQuarantineRecord>()
    private val lastSequenceBySession = mutableMapOf<String, Long>()
    var commitCalls: Int = 0
        private set

    override fun commit(candidate: BatchCommitCandidate): DurableCommitResult {
        commitCalls += 1
        if (failNextCommit) {
            failNextCommit = false
            return DurableCommitResult.StoreFailure()
        }
        durableRecords[candidate.envelope.batchId]?.let { existing ->
            return if (existing.wireSha256Hex == candidate.wireSha256Hex) {
                DurableCommitResult.AlreadyCommitted(
                    "commit-${existing.envelope.batchId}",
                    existing.wireSha256Hex,
                )
            } else {
                DurableCommitResult.ConflictingBatchId()
            }
        }
        val previous = lastSequenceBySession[candidate.envelope.sessionId]
        if (previous != null && candidate.envelope.sequence <= previous) {
            return DurableCommitResult.OutOfOrder()
        }
        durableRecords[candidate.envelope.batchId] = candidate
        lastSequenceBySession[candidate.envelope.sessionId] = candidate.envelope.sequence
        return DurableCommitResult.Committed("commit-${candidate.envelope.batchId}")
    }

    override fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult {
        quarantines += record
        return QuarantineWriteResult.Recorded("q-${quarantines.size}")
    }
}
