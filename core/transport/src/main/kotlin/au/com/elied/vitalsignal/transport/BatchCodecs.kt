package au.com.elied.vitalsignal.transport

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object BatchEnvelopeCodec {
    private const val MAGIC: Int = 0x56534231 // VSB1
    private const val RESERVED_FLAGS: Int = 0

    fun encode(envelope: BatchEnvelope): ByteArray {
        val unsigned = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(envelope.protocolVersion)
                output.writeInt(RESERVED_FLAGS)
                output.writeBoundedString(envelope.batchId, BatchWireLimits.MAX_BATCH_ID_BYTES)
                output.writeBoundedString(envelope.sessionId, BatchWireLimits.MAX_SESSION_ID_BYTES)
                output.writeBoundedString(envelope.deviceId, BatchWireLimits.MAX_DEVICE_ID_BYTES)
                output.writeLong(envelope.sequence)
                output.writeLong(envelope.createdAtEpochMillis)
                output.writeInt(envelope.contentSchemaVersion)
                output.writeBoundedString(envelope.contentType, BatchWireLimits.MAX_CONTENT_TYPE_BYTES)
                output.writeInt(envelope.payloadSize)
                output.write(envelope.payloadForEncoding())
            }
            buffer.toByteArray()
        }
        val encoded = unsigned + sha256(unsigned)
        require(encoded.size <= BatchWireLimits.MAX_ENVELOPE_BYTES) { "Encoded envelope is too large" }
        return encoded
    }

    fun decode(encoded: ByteArray): BatchEnvelope {
        if (encoded.size > BatchWireLimits.MAX_ENVELOPE_BYTES) {
            fail(DecodeFailureCode.OVERSIZE, "Envelope exceeds the wire limit")
        }
        val cursor = WireCursor(encoded)
        if (cursor.readInt() != MAGIC) fail(DecodeFailureCode.BAD_MAGIC, "Envelope magic is invalid")
        val protocolVersion = cursor.readInt()
        if (protocolVersion != BatchWireLimits.PROTOCOL_VERSION) {
            fail(DecodeFailureCode.UNSUPPORTED_VERSION, "Envelope protocol version is unsupported")
        }
        if (cursor.readInt() != RESERVED_FLAGS) {
            fail(DecodeFailureCode.MALFORMED, "Envelope reserved flags must be zero")
        }

        val batchId = cursor.readBoundedString(BatchWireLimits.MAX_BATCH_ID_BYTES)
        val sessionId = cursor.readBoundedString(BatchWireLimits.MAX_SESSION_ID_BYTES)
        val deviceId = cursor.readBoundedString(BatchWireLimits.MAX_DEVICE_ID_BYTES)
        val sequence = cursor.readLong()
        val createdAt = cursor.readLong()
        val schemaVersion = cursor.readInt()
        val contentType = cursor.readBoundedString(BatchWireLimits.MAX_CONTENT_TYPE_BYTES)
        val payloadLength = cursor.readBoundedLength(BatchWireLimits.MAX_PAYLOAD_BYTES)

        val expectedRemaining = payloadLength.toLong() + BatchWireLimits.SHA_256_BYTES
        when {
            cursor.remaining.toLong() < expectedRemaining -> fail(
                DecodeFailureCode.TRUNCATED,
                "Envelope payload or checksum is truncated",
            )
            cursor.remaining.toLong() > expectedRemaining -> fail(
                DecodeFailureCode.TRAILING_BYTES,
                "Envelope has trailing bytes",
            )
        }

        val payload = cursor.readBytes(payloadLength)
        val checksumOffset = cursor.position
        val suppliedChecksum = cursor.readBytes(BatchWireLimits.SHA_256_BYTES)
        val expectedChecksum = sha256(encoded.copyOfRange(0, checksumOffset))
        if (!MessageDigest.isEqual(suppliedChecksum, expectedChecksum)) {
            fail(DecodeFailureCode.CHECKSUM_MISMATCH, "Envelope checksum does not match")
        }
        if (cursor.remaining != 0) fail(DecodeFailureCode.TRAILING_BYTES, "Envelope has trailing bytes")

        return try {
            BatchEnvelope(
                protocolVersion = protocolVersion,
                batchId = batchId,
                sessionId = sessionId,
                deviceId = deviceId,
                sequence = sequence,
                createdAtEpochMillis = createdAt,
                contentSchemaVersion = schemaVersion,
                contentType = contentType,
                payload = payload,
            )
        } catch (error: IllegalArgumentException) {
            fail(DecodeFailureCode.MALFORMED, error.message ?: "Envelope fields are invalid")
        }
    }
}

object BatchAcknowledgementCodec {
    private const val MAGIC: Int = 0x56534131 // VSA1

    fun encode(acknowledgement: BatchAcknowledgement): ByteArray {
        val unsigned = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(acknowledgement.protocolVersion)
                output.writeBoundedString(acknowledgement.disposition.wireCode, 8)
                output.writeBoundedString(acknowledgement.reason.wireCode, 48)
                output.writeBoundedString(
                    acknowledgement.quarantineDisposition.wireCode,
                    32,
                )
                output.writeBoundedString(
                    acknowledgement.receiptId,
                    BatchWireLimits.MAX_RECEIPT_ID_BYTES,
                )
                output.writeNullableBoundedString(
                    acknowledgement.batchId,
                    BatchWireLimits.MAX_BATCH_ID_BYTES,
                )
                output.writeNullableBoundedString(
                    acknowledgement.sessionId,
                    BatchWireLimits.MAX_SESSION_ID_BYTES,
                )
                output.writeLong(acknowledgement.sequence ?: -1L)
                output.writeLong(acknowledgement.receivedAtEpochMillis)
                output.writeBoundedString(acknowledgement.wireSha256Hex, 64)
                output.writeNullableBoundedString(
                    acknowledgement.durableCommitToken,
                    BatchWireLimits.MAX_TOKEN_BYTES,
                )
                output.writeNullableBoundedString(
                    acknowledgement.quarantineToken,
                    BatchWireLimits.MAX_TOKEN_BYTES,
                )
                output.writeBoundedString(
                    acknowledgement.detailCode,
                    BatchWireLimits.MAX_DETAIL_CODE_BYTES,
                )
            }
            buffer.toByteArray()
        }
        val encoded = unsigned + sha256(unsigned)
        require(encoded.size <= BatchWireLimits.MAX_ACK_BYTES) { "Encoded acknowledgement is too large" }
        return encoded
    }

    fun decode(encoded: ByteArray): BatchAcknowledgement {
        if (encoded.size > BatchWireLimits.MAX_ACK_BYTES) {
            fail(DecodeFailureCode.OVERSIZE, "Acknowledgement exceeds the wire limit")
        }
        val cursor = WireCursor(encoded)
        if (cursor.readInt() != MAGIC) fail(DecodeFailureCode.BAD_MAGIC, "Acknowledgement magic is invalid")
        val version = cursor.readInt()
        if (version != BatchWireLimits.PROTOCOL_VERSION) {
            fail(DecodeFailureCode.UNSUPPORTED_VERSION, "Acknowledgement protocol version is unsupported")
        }
        val disposition = ReceiptDisposition.fromWireCode(cursor.readBoundedString(8))
            ?: fail(DecodeFailureCode.MALFORMED, "Acknowledgement disposition is invalid")
        val reason = ReceiptReason.fromWireCode(cursor.readBoundedString(48))
            ?: fail(DecodeFailureCode.MALFORMED, "Acknowledgement reason is invalid")
        val quarantineDisposition = QuarantineDisposition.fromWireCode(cursor.readBoundedString(32))
            ?: fail(DecodeFailureCode.MALFORMED, "Quarantine disposition is invalid")
        val receiptId = cursor.readBoundedString(BatchWireLimits.MAX_RECEIPT_ID_BYTES)
        val batchId = cursor.readNullableBoundedString(BatchWireLimits.MAX_BATCH_ID_BYTES)
        val sessionId = cursor.readNullableBoundedString(BatchWireLimits.MAX_SESSION_ID_BYTES)
        val sequenceWire = cursor.readLong()
        val sequence = if (sequenceWire == -1L) null else sequenceWire
        val receivedAt = cursor.readLong()
        val wireDigest = cursor.readBoundedString(64)
        val durableToken = cursor.readNullableBoundedString(BatchWireLimits.MAX_TOKEN_BYTES)
        val quarantineToken = cursor.readNullableBoundedString(BatchWireLimits.MAX_TOKEN_BYTES)
        val detailCode = cursor.readBoundedString(BatchWireLimits.MAX_DETAIL_CODE_BYTES)

        when {
            cursor.remaining < BatchWireLimits.SHA_256_BYTES -> fail(
                DecodeFailureCode.TRUNCATED,
                "Acknowledgement checksum is truncated",
            )
            cursor.remaining > BatchWireLimits.SHA_256_BYTES -> fail(
                DecodeFailureCode.TRAILING_BYTES,
                "Acknowledgement has trailing bytes",
            )
        }
        val checksumOffset = cursor.position
        val suppliedChecksum = cursor.readBytes(BatchWireLimits.SHA_256_BYTES)
        if (!MessageDigest.isEqual(suppliedChecksum, sha256(encoded.copyOfRange(0, checksumOffset)))) {
            fail(DecodeFailureCode.CHECKSUM_MISMATCH, "Acknowledgement checksum does not match")
        }

        return try {
            BatchAcknowledgement(
                protocolVersion = version,
                disposition = disposition,
                reason = reason,
                receiptId = receiptId,
                batchId = batchId,
                sessionId = sessionId,
                sequence = sequence,
                receivedAtEpochMillis = receivedAt,
                wireSha256Hex = wireDigest,
                durableCommitToken = durableToken,
                quarantineDisposition = quarantineDisposition,
                quarantineToken = quarantineToken,
                detailCode = detailCode,
            )
        } catch (error: IllegalArgumentException) {
            fail(DecodeFailureCode.MALFORMED, error.message ?: "Acknowledgement fields are invalid")
        }
    }
}

internal fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal fun sha256Hex(value: ByteArray): String = sha256(value).joinToString("") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun DataOutputStream.writeBoundedString(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= maximumBytes) { "String exceeds wire limit" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeNullableBoundedString(value: String?, maximumBytes: Int) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeBoundedString(value, maximumBytes)
    }
}

private class WireCursor(private val encoded: ByteArray) {
    var position: Int = 0
        private set

    val remaining: Int get() = encoded.size - position

    fun readInt(): Int {
        requireAvailable(Int.SIZE_BYTES)
        return ByteBuffer.wrap(encoded, position, Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .also { position += Int.SIZE_BYTES }
    }

    fun readLong(): Long {
        requireAvailable(Long.SIZE_BYTES)
        return ByteBuffer.wrap(encoded, position, Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .long
            .also { position += Long.SIZE_BYTES }
    }

    fun readBoundedLength(maximum: Int): Int {
        val length = readInt()
        if (length < 0 || length > maximum) {
            fail(DecodeFailureCode.MALFORMED, "Length prefix is outside the wire limit")
        }
        return length
    }

    fun readBoundedString(maximumBytes: Int): String {
        val bytes = readBytes(readBoundedLength(maximumBytes))
        return decodeUtf8(bytes)
    }

    fun readNullableBoundedString(maximumBytes: Int): String? {
        val length = readInt()
        if (length == -1) return null
        if (length < 0 || length > maximumBytes) {
            fail(DecodeFailureCode.MALFORMED, "Nullable string length is outside the wire limit")
        }
        return decodeUtf8(readBytes(length))
    }

    fun readBytes(length: Int): ByteArray {
        requireAvailable(length)
        return encoded.copyOfRange(position, position + length).also { position += length }
    }

    private fun requireAvailable(length: Int) {
        if (length < 0 || length > remaining) {
            fail(DecodeFailureCode.TRUNCATED, "Wire value is truncated")
        }
    }
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    fail(DecodeFailureCode.MALFORMED, "String is not valid UTF-8")
}

private fun fail(code: DecodeFailureCode, message: String): Nothing =
    throw WireDecodeException(code, message)
