package au.com.elied.vitalsignal.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchCodecsTest {
    @Test
    fun envelopeRoundTripIsCanonicalAndPayloadIsDefensivelyCopied() {
        val sourcePayload = byteArrayOf(1, 2, 3, 4)
        val envelope = envelope(payload = sourcePayload)
        sourcePayload[0] = 99

        val encoded = BatchEnvelopeCodec.encode(envelope)
        val decoded = BatchEnvelopeCodec.decode(encoded)
        val returnedPayload = decoded.payloadCopy()
        returnedPayload[1] = 88

        assertEquals(envelope, decoded)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.payloadCopy())
        assertArrayEquals(encoded, BatchEnvelopeCodec.encode(decoded))
    }

    @Test
    fun payloadTamperIsRejectedByChecksum() {
        val encoded = BatchEnvelopeCodec.encode(envelope(payload = "health".toByteArray()))
        encoded[encoded.size - BatchWireLimits.SHA_256_BYTES - 1] =
            (encoded[encoded.size - BatchWireLimits.SHA_256_BYTES - 1].toInt() xor 0x01).toByte()

        val failure = assertThrows(WireDecodeException::class.java) {
            BatchEnvelopeCodec.decode(encoded)
        }

        assertEquals(DecodeFailureCode.CHECKSUM_MISMATCH, failure.failureCode)
    }

    @Test
    fun trailingTruncatedAndOversizeEnvelopesAreDistinctFailures() {
        val encoded = BatchEnvelopeCodec.encode(envelope())

        assertFailure(DecodeFailureCode.TRAILING_BYTES, encoded + 0x01)
        assertFailure(DecodeFailureCode.TRUNCATED, encoded.copyOf(encoded.size - 1))
        assertFailure(
            DecodeFailureCode.OVERSIZE,
            ByteArray(BatchWireLimits.MAX_ENVELOPE_BYTES + 1),
        )
    }

    @Test
    fun unsupportedProtocolVersionIsRejected() {
        val encoded = BatchEnvelopeCodec.encode(envelope())
        encoded[7] = 2 // Big-endian version occupies bytes 4..7.

        assertFailure(DecodeFailureCode.UNSUPPORTED_VERSION, encoded)
    }

    @Test
    fun acknowledgementRoundTripRejectsTamperAndTrailingBytes() {
        val acknowledgement = acknowledgement()
        val encoded = BatchAcknowledgementCodec.encode(acknowledgement)

        assertEquals(acknowledgement, BatchAcknowledgementCodec.decode(encoded))

        val tampered = encoded.copyOf().also {
            it[it.size - BatchWireLimits.SHA_256_BYTES - 1] =
                (it[it.size - BatchWireLimits.SHA_256_BYTES - 1].toInt() xor 0x01).toByte()
        }
        assertFailure(DecodeFailureCode.CHECKSUM_MISMATCH, tampered, acknowledgement = true)
        assertFailure(DecodeFailureCode.TRAILING_BYTES, encoded + 0x02, acknowledgement = true)
    }

    private fun assertFailure(
        code: DecodeFailureCode,
        bytes: ByteArray,
        acknowledgement: Boolean = false,
    ) {
        val failure = assertThrows(WireDecodeException::class.java) {
            if (acknowledgement) {
                BatchAcknowledgementCodec.decode(bytes)
            } else {
                BatchEnvelopeCodec.decode(bytes)
            }
        }
        assertEquals(code, failure.failureCode)
    }

    private fun envelope(payload: ByteArray = byteArrayOf(8, 9)) = BatchEnvelope(
        batchId = "batch-1",
        sessionId = "session-1",
        deviceId = "watch-1",
        sequence = 4,
        createdAtEpochMillis = 1_800_000_000_000,
        contentSchemaVersion = 1,
        contentType = "sensor.features",
        payload = payload,
    )

    private fun acknowledgement() = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = ReceiptReason.DURABLY_COMMITTED,
        receiptId = "receipt-1",
        batchId = "batch-1",
        sessionId = "session-1",
        sequence = 4,
        receivedAtEpochMillis = 1_800_000_000_100,
        wireSha256Hex = "a".repeat(64),
        durableCommitToken = "commit-1",
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = "durable_commit",
    )
}
