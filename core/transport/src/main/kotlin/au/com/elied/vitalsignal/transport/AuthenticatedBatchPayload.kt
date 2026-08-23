package au.com.elied.vitalsignal.transport

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Application-level authenticated encryption for a [BatchEnvelope] payload.
 *
 * The outer codec checksum is only a corruption detector. Authenticity comes from AES-GCM: all
 * routing and provenance metadata is canonicalised as additional authenticated data (AAD), so a
 * changed batch/session/device/sequence/time/schema/content-type/key/nonce fails to open.
 *
 * Key agreement is intentionally outside this platform-free class. The private pilot remains
 * blocked until a physical watch and phone complete an authenticated pairing protocol; tests use
 * injected keys only.
 */
class AuthenticatedBatchPayloadCipher(
    private val secureRandom: SecureRandom,
) {
    fun seal(
        batchId: String,
        sessionId: String,
        deviceId: String,
        sequence: Long,
        createdAtEpochMillis: Long,
        contentSchemaVersion: Int,
        contentType: String,
        plaintext: ByteArray,
        keyId: String,
        secretKey: SecretKey,
    ): BatchEnvelope {
        require(secretKey.algorithm.equals("AES", ignoreCase = true)) { "Transport key must use AES" }
        require(keyId.matches(KEY_ID_PATTERN)) { "Invalid transport key ID" }
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Plaintext batch is too large" }

        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val skeleton = BatchEnvelope(
            batchId = batchId,
            sessionId = sessionId,
            deviceId = deviceId,
            sequence = sequence,
            createdAtEpochMillis = createdAtEpochMillis,
            contentSchemaVersion = contentSchemaVersion,
            contentType = contentType,
            payload = ByteArray(0),
        )
        val aad = canonicalAad(skeleton, keyId, nonce)
        val encrypted = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }
        return BatchEnvelope(
            protocolVersion = skeleton.protocolVersion,
            batchId = skeleton.batchId,
            sessionId = skeleton.sessionId,
            deviceId = skeleton.deviceId,
            sequence = skeleton.sequence,
            createdAtEpochMillis = skeleton.createdAtEpochMillis,
            contentSchemaVersion = skeleton.contentSchemaVersion,
            contentType = skeleton.contentType,
            payload = AuthenticatedPayloadCodec.encode(
                AuthenticatedPayload(
                    keyId = keyId,
                    nonce = nonce,
                    ciphertext = encrypted,
                ),
            ),
        )
    }

    fun open(
        envelope: BatchEnvelope,
        keyResolver: TransportKeyResolver,
    ): AuthenticatedPayloadOpenResult {
        val payload = try {
            AuthenticatedPayloadCodec.decode(envelope.payloadCopy())
        } catch (_: Exception) {
            return AuthenticatedPayloadOpenResult.Malformed
        }
        val key = keyResolver.resolve(payload.keyId)
            ?: return AuthenticatedPayloadOpenResult.UnknownKey(payload.keyId)
        if (!key.algorithm.equals("AES", ignoreCase = true)) {
            return AuthenticatedPayloadOpenResult.InvalidKey(payload.keyId)
        }
        val plaintext = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.nonceCopy()))
                updateAAD(canonicalAad(envelope, payload.keyId, payload.nonceCopy()))
                doFinal(payload.ciphertextCopy())
            }
        } catch (_: AEADBadTagException) {
            return AuthenticatedPayloadOpenResult.AuthenticationFailed
        } catch (_: GeneralSecurityException) {
            return AuthenticatedPayloadOpenResult.AuthenticationFailed
        }
        return AuthenticatedPayloadOpenResult.Opened(payload.keyId, plaintext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

fun interface TransportKeyResolver {
    fun resolve(keyId: String): SecretKey?
}

sealed interface AuthenticatedPayloadOpenResult {
    class Opened(val keyId: String, plaintext: ByteArray) : AuthenticatedPayloadOpenResult {
        private val immutablePlaintext = plaintext.copyOf()
        fun plaintextCopy(): ByteArray = immutablePlaintext.copyOf()
    }

    data class UnknownKey(val keyId: String) : AuthenticatedPayloadOpenResult
    data class InvalidKey(val keyId: String) : AuthenticatedPayloadOpenResult
    data object Malformed : AuthenticatedPayloadOpenResult
    data object AuthenticationFailed : AuthenticatedPayloadOpenResult
}

sealed interface BatchAuthenticationResult {
    class Authenticated(val keyId: String, plaintext: ByteArray) : BatchAuthenticationResult {
        private val immutablePlaintext = plaintext.copyOf()
        fun plaintextCopy(): ByteArray = immutablePlaintext.copyOf()
    }

    data class Rejected(
        val reason: ReceiptReason,
        val detailCode: String,
    ) : BatchAuthenticationResult {
        init {
            require(!reason.isAckReason)
            require(detailCode.matches(Regex("[a-z0-9_.-]{1,96}")))
        }
    }
}

fun interface BatchPayloadAuthenticator {
    fun authenticate(envelope: BatchEnvelope): BatchAuthenticationResult
}

/** Production-shaped authenticator: no caller can access plaintext before AES-GCM succeeds. */
class AesGcmBatchPayloadAuthenticator(
    private val cipher: AuthenticatedBatchPayloadCipher,
    private val keyResolver: TransportKeyResolver,
) : BatchPayloadAuthenticator {
    override fun authenticate(envelope: BatchEnvelope): BatchAuthenticationResult = when (
        val opened = cipher.open(envelope, keyResolver)
    ) {
        is AuthenticatedPayloadOpenResult.Opened -> BatchAuthenticationResult.Authenticated(
            keyId = opened.keyId,
            plaintext = opened.plaintextCopy(),
        )
        is AuthenticatedPayloadOpenResult.UnknownKey -> BatchAuthenticationResult.Rejected(
            ReceiptReason.UNKNOWN_KEY,
            "unknown_transport_key",
        )
        is AuthenticatedPayloadOpenResult.InvalidKey -> BatchAuthenticationResult.Rejected(
            ReceiptReason.UNKNOWN_KEY,
            "invalid_transport_key",
        )
        AuthenticatedPayloadOpenResult.Malformed -> BatchAuthenticationResult.Rejected(
            ReceiptReason.MALFORMED,
            "malformed_encrypted_payload",
        )
        AuthenticatedPayloadOpenResult.AuthenticationFailed -> BatchAuthenticationResult.Rejected(
            ReceiptReason.AUTHENTICATION_FAILED,
            "aes_gcm_authentication_failed",
        )
    }
}

private class AuthenticatedPayload(
    val keyId: String,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val immutableNonce = nonce.copyOf()
    private val immutableCiphertext = ciphertext.copyOf()

    init {
        require(keyId.matches(Regex("[A-Za-z0-9._:-]{1,96}")))
        require(immutableNonce.size == GCM_NONCE_BYTES)
        require(immutableCiphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES)
    }

    fun nonceCopy(): ByteArray = immutableNonce.copyOf()
    fun ciphertextCopy(): ByteArray = immutableCiphertext.copyOf()
}

private object AuthenticatedPayloadCodec {
    private const val MAGIC = 0x56534531 // VSE1
    private const val VERSION = 1

    fun encode(value: AuthenticatedPayload): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            val keyId = value.keyId.toByteArray(StandardCharsets.UTF_8)
            val nonce = value.nonceCopy()
            val ciphertext = value.ciphertextCopy()
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(keyId.size)
            output.write(keyId)
            output.writeInt(nonce.size)
            output.write(nonce)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
        }
        buffer.toByteArray().also {
            require(it.size <= BatchWireLimits.MAX_PAYLOAD_BYTES) { "Encrypted payload exceeds wire limit" }
        }
    }

    fun decode(encoded: ByteArray): AuthenticatedPayload {
        require(encoded.size in MIN_ENCODED_BYTES..BatchWireLimits.MAX_PAYLOAD_BYTES)
        val cursor = PayloadCursor(encoded)
        require(cursor.readInt() == MAGIC) { "Encrypted payload magic is invalid" }
        require(cursor.readInt() == VERSION) { "Encrypted payload version is unsupported" }
        val keyId = cursor.readUtf8(cursor.readBoundedLength(MAX_KEY_ID_BYTES))
        val nonce = cursor.readBytes(cursor.readBoundedLength(GCM_NONCE_BYTES))
        require(nonce.size == GCM_NONCE_BYTES)
        val ciphertext = cursor.readBytes(cursor.readBoundedLength(MAX_CIPHERTEXT_BYTES))
        require(ciphertext.size >= GCM_TAG_BYTES)
        require(cursor.remaining == 0) { "Encrypted payload has trailing bytes" }
        return AuthenticatedPayload(keyId, nonce, ciphertext)
    }
}

private fun canonicalAad(
    envelope: BatchEnvelope,
    keyId: String,
    nonce: ByteArray,
): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use { output ->
        output.writeUTF("VitalSignal-transport-AAD-v1")
        output.writeInt(envelope.protocolVersion)
        output.writeCanonicalString(envelope.batchId)
        output.writeCanonicalString(envelope.sessionId)
        output.writeCanonicalString(envelope.deviceId)
        output.writeLong(envelope.sequence)
        output.writeLong(envelope.createdAtEpochMillis)
        output.writeInt(envelope.contentSchemaVersion)
        output.writeCanonicalString(envelope.contentType)
        output.writeCanonicalString(keyId)
        output.writeInt(nonce.size)
        output.write(nonce)
    }
    buffer.toByteArray()
}

private fun DataOutputStream.writeCanonicalString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private class PayloadCursor(private val bytes: ByteArray) {
    var position: Int = 0
        private set
    val remaining: Int get() = bytes.size - position

    fun readInt(): Int {
        require(remaining >= Int.SIZE_BYTES)
        return ByteBuffer.wrap(bytes, position, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .also { position += Int.SIZE_BYTES }
    }

    fun readBoundedLength(maximum: Int): Int = readInt().also { require(it in 0..maximum) }

    fun readBytes(length: Int): ByteArray {
        require(length <= remaining)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readUtf8(length: Int): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(readBytes(length)))
        .toString()
}

private const val GCM_NONCE_BYTES = 12
private const val GCM_TAG_BYTES = 16
private const val MAX_KEY_ID_BYTES = 96
private const val MAX_CIPHERTEXT_BYTES = BatchWireLimits.MAX_PAYLOAD_BYTES - 256
private const val MAX_PLAINTEXT_BYTES = MAX_CIPHERTEXT_BYTES - GCM_TAG_BYTES
private const val MIN_ENCODED_BYTES = 4 + 4 + 4 + 1 + 4 + GCM_NONCE_BYTES + 4 + GCM_TAG_BYTES
