package au.com.elied.vitalsignal.transport

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Keyed application-level authentication for a phone receipt.
 *
 * The inner acknowledgement checksum remains useful for corruption classification, but it cannot
 * authorize deletion. Watch deletion consumes only this HMAC-authenticated wrapper using a
 * purpose-separated pairing-generation key.
 */
object AuthenticatedAcknowledgementCodec {
    private const val MAGIC = 0x56534131 // VSA1
    private const val VERSION = 1
    private const val MAC_BYTES = 32
    private const val MAX_KEY_ID_BYTES = 96
    const val MAX_AUTHENTICATED_ACK_BYTES = BatchWireLimits.MAX_ACK_BYTES + 512

    fun encode(
        acknowledgement: BatchAcknowledgement,
        keyId: String,
        authenticationKey: SecretKey,
    ): ByteArray {
        require(keyId.matches(KEY_ID_PATTERN)) { "Invalid acknowledgement key ID" }
        require(authenticationKey.algorithm.equals(MAC_ALGORITHM, ignoreCase = true)) {
            "Acknowledgement key must be purpose-specific HmacSHA256"
        }
        val inner = BatchAcknowledgementCodec.encode(acknowledgement)
        val unsigned = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                val keyIdBytes = keyId.toByteArray(StandardCharsets.US_ASCII)
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(keyIdBytes.size)
                output.write(keyIdBytes)
                output.writeInt(inner.size)
                output.write(inner)
            }
            buffer.toByteArray()
        }
        return (unsigned + mac(unsigned, authenticationKey)).also {
            require(it.size <= MAX_AUTHENTICATED_ACK_BYTES)
        }
    }

    fun decodeAndAuthenticate(
        encoded: ByteArray,
        keyResolver: AcknowledgementKeyResolver,
    ): AuthenticatedAcknowledgementResult {
        if (encoded.size !in MINIMUM_BYTES..MAX_AUTHENTICATED_ACK_BYTES) {
            return AuthenticatedAcknowledgementResult.Malformed
        }
        val parsed = try {
            val cursor = AuthAckCursor(encoded)
            require(cursor.readInt() == MAGIC)
            require(cursor.readInt() == VERSION)
            val keyId = cursor.readAscii(cursor.readBoundedLength(MAX_KEY_ID_BYTES))
            require(keyId.matches(KEY_ID_PATTERN))
            val inner = cursor.readBytes(cursor.readBoundedLength(BatchWireLimits.MAX_ACK_BYTES))
            require(cursor.remaining == MAC_BYTES)
            val macOffset = cursor.position
            val suppliedMac = cursor.readBytes(MAC_BYTES)
            ParsedAuthenticatedAcknowledgement(keyId, inner, macOffset, suppliedMac)
        } catch (_: Exception) {
            return AuthenticatedAcknowledgementResult.Malformed
        }
        val key = keyResolver.resolve(parsed.keyId)
            ?: return AuthenticatedAcknowledgementResult.UnknownKey(parsed.keyId)
        if (!key.algorithm.equals(MAC_ALGORITHM, ignoreCase = true)) {
            return AuthenticatedAcknowledgementResult.UnknownKey(parsed.keyId)
        }
        val expectedMac = try {
            mac(encoded.copyOfRange(0, parsed.macOffset), key)
        } catch (_: Exception) {
            return AuthenticatedAcknowledgementResult.AuthenticationFailed
        }
        if (!MessageDigest.isEqual(expectedMac, parsed.suppliedMac)) {
            return AuthenticatedAcknowledgementResult.AuthenticationFailed
        }
        val acknowledgement = try {
            BatchAcknowledgementCodec.decode(parsed.innerAcknowledgement)
        } catch (_: RuntimeException) {
            return AuthenticatedAcknowledgementResult.Malformed
        }
        return AuthenticatedAcknowledgementResult.Authenticated(parsed.keyId, acknowledgement)
    }

    private fun mac(value: ByteArray, key: SecretKey): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).run {
            init(key)
            doFinal(value)
        }

    private data class ParsedAuthenticatedAcknowledgement(
        val keyId: String,
        val innerAcknowledgement: ByteArray,
        val macOffset: Int,
        val suppliedMac: ByteArray,
    )

    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val MINIMUM_BYTES = 4 + 4 + 4 + 1 + 4 + MAC_BYTES
    private val KEY_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
}

fun interface AcknowledgementKeyResolver {
    fun resolve(keyId: String): SecretKey?
}

sealed interface AuthenticatedAcknowledgementResult {
    data class Authenticated(
        val keyId: String,
        val acknowledgement: BatchAcknowledgement,
    ) : AuthenticatedAcknowledgementResult

    data class UnknownKey(val keyId: String) : AuthenticatedAcknowledgementResult
    data object AuthenticationFailed : AuthenticatedAcknowledgementResult
    data object Malformed : AuthenticatedAcknowledgementResult
}

private class AuthAckCursor(private val bytes: ByteArray) {
    var position = 0
        private set
    val remaining: Int get() = bytes.size - position

    fun readInt(): Int {
        require(remaining >= Int.SIZE_BYTES)
        return ByteBuffer.wrap(bytes, position, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .also { position += Int.SIZE_BYTES }
    }

    fun readBoundedLength(maximum: Int): Int = readInt().also { require(it in 1..maximum) }

    fun readBytes(length: Int): ByteArray {
        require(length <= remaining)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readAscii(length: Int): String = StandardCharsets.US_ASCII.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(readBytes(length)))
        .toString()
}
