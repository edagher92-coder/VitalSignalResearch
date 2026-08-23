package au.com.elied.vitalsignal.transport

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedBatchPayloadTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Test
    fun roundTripAuthenticatesPlaintextAndMetadata() {
        val cipher = AuthenticatedBatchPayloadCipher(DeterministicSecureRandom())
        val sealed = cipher.seal(
            batchId = "batch-1",
            sessionId = "session-1",
            deviceId = "watch-1",
            sequence = 7,
            createdAtEpochMillis = 1_000L,
            contentSchemaVersion = 1,
            contentType = "sensor-batch",
            plaintext = "private-health-fixture".toByteArray(),
            keyId = "pairing-key-1",
            secretKey = key,
        )

        val opened = cipher.open(sealed) { requested -> if (requested == "pairing-key-1") key else null }

        assertTrue(opened is AuthenticatedPayloadOpenResult.Opened)
        assertArrayEquals(
            "private-health-fixture".toByteArray(),
            (opened as AuthenticatedPayloadOpenResult.Opened).plaintextCopy(),
        )
    }

    @Test
    fun changedRoutingMetadataFailsAuthentication() {
        val cipher = AuthenticatedBatchPayloadCipher(DeterministicSecureRandom())
        val sealed = sample(cipher)
        val changed = BatchEnvelope(
            batchId = sealed.batchId,
            sessionId = "different-session",
            deviceId = sealed.deviceId,
            sequence = sealed.sequence,
            createdAtEpochMillis = sealed.createdAtEpochMillis,
            contentSchemaVersion = sealed.contentSchemaVersion,
            contentType = sealed.contentType,
            payload = sealed.payloadCopy(),
        )

        assertEquals(
            AuthenticatedPayloadOpenResult.AuthenticationFailed,
            cipher.open(changed) { key },
        )
    }

    @Test
    fun changedCiphertextAndUnknownKeyFailClosed() {
        val cipher = AuthenticatedBatchPayloadCipher(DeterministicSecureRandom())
        val sealed = sample(cipher)
        val payload = sealed.payloadCopy().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val tampered = BatchEnvelope(
            batchId = sealed.batchId,
            sessionId = sealed.sessionId,
            deviceId = sealed.deviceId,
            sequence = sealed.sequence,
            createdAtEpochMillis = sealed.createdAtEpochMillis,
            contentSchemaVersion = sealed.contentSchemaVersion,
            contentType = sealed.contentType,
            payload = payload,
        )

        assertEquals(
            AuthenticatedPayloadOpenResult.AuthenticationFailed,
            cipher.open(tampered) { key },
        )
        assertEquals(
            AuthenticatedPayloadOpenResult.UnknownKey("pairing-key-1"),
            cipher.open(sealed) { null },
        )
    }

    private fun sample(cipher: AuthenticatedBatchPayloadCipher) = cipher.seal(
        batchId = "batch-1",
        sessionId = "session-1",
        deviceId = "watch-1",
        sequence = 7,
        createdAtEpochMillis = 1_000L,
        contentSchemaVersion = 1,
        contentType = "sensor-batch",
        plaintext = byteArrayOf(10, 20, 30, 40),
        keyId = "pairing-key-1",
        secretKey = key,
    )
}

private class DeterministicSecureRandom : java.security.SecureRandom() {
    private var next = 1

    override fun nextBytes(bytes: ByteArray) {
        bytes.indices.forEach { index -> bytes[index] = (next++).toByte() }
    }
}
