package au.com.elied.vitalsignal.transport

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedAcknowledgementTest {
    @Test
    fun exactReceiptRoundTripsWithPurposeSpecificMac() {
        val encoded = AuthenticatedAcknowledgementCodec.encode(ack(), KEY_ID, KEY)

        val result = AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(encoded) { keyId ->
            if (keyId == KEY_ID) KEY else null
        }

        assertEquals(
            AuthenticatedAcknowledgementResult.Authenticated(KEY_ID, ack()),
            result,
        )
    }

    @Test
    fun recomputedInnerChecksumCannotForgeOuterAuthentication() {
        val original = AuthenticatedAcknowledgementCodec.encode(ack(), KEY_ID, KEY)
        val forgedInner = BatchAcknowledgementCodec.encode(ack().copy(batchId = "batch-9"))
        val forged = replaceInnerBytesWithoutMac(original, forgedInner)

        assertEquals(
            AuthenticatedAcknowledgementResult.AuthenticationFailed,
            AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(forged) { KEY },
        )
    }

    @Test
    fun unknownKeyTamperAndTrailingBytesFailClosed() {
        val encoded = AuthenticatedAcknowledgementCodec.encode(ack(), KEY_ID, KEY)
        assertEquals(
            AuthenticatedAcknowledgementResult.UnknownKey(KEY_ID),
            AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(encoded) { null },
        )
        val tampered = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(
            AuthenticatedAcknowledgementResult.AuthenticationFailed,
            AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(tampered) { KEY },
        )
        assertTrue(
            AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(encoded + 0) { KEY } is
                AuthenticatedAcknowledgementResult.Malformed,
        )
    }

    private fun replaceInnerBytesWithoutMac(original: ByteArray, replacement: ByteArray): ByteArray {
        val keyIdLength = java.nio.ByteBuffer.wrap(original, 8, 4).int
        val innerLengthOffset = 12 + keyIdLength
        val innerOffset = innerLengthOffset + 4
        val originalInnerLength = java.nio.ByteBuffer.wrap(original, innerLengthOffset, 4).int
        require(originalInnerLength == replacement.size)
        return original.copyOf().also {
            replacement.copyInto(it, innerOffset)
        }
    }

    private fun ack() = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = ReceiptReason.DURABLY_COMMITTED,
        receiptId = "receipt-1",
        batchId = "batch-1",
        sessionId = "session-1",
        sequence = 7,
        receivedAtEpochMillis = 1_800_000_000_100,
        wireSha256Hex = "a".repeat(64),
        durableCommitToken = "commit-1",
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = "durable_commit",
    )

    private companion object {
        const val KEY_ID = "ack-key-1"
        val KEY = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "HmacSHA256")
    }
}
