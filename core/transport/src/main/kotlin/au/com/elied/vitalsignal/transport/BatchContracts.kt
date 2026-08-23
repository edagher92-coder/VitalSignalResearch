package au.com.elied.vitalsignal.transport

/** Wire limits are part of protocol v1 and must not be relaxed without a version change. */
object BatchWireLimits {
    const val PROTOCOL_VERSION: Int = 1
    const val SHA_256_BYTES: Int = 32
    const val MAX_PAYLOAD_BYTES: Int = 512 * 1024
    const val MAX_ENVELOPE_BYTES: Int = MAX_PAYLOAD_BYTES + 8 * 1024
    const val MAX_ACK_BYTES: Int = 16 * 1024
    const val MAX_BATCH_ID_BYTES: Int = 96
    const val MAX_SESSION_ID_BYTES: Int = 96
    const val MAX_DEVICE_ID_BYTES: Int = 128
    const val MAX_CONTENT_TYPE_BYTES: Int = 64
    const val MAX_RECEIPT_ID_BYTES: Int = 96
    const val MAX_TOKEN_BYTES: Int = 256
    const val MAX_DETAIL_CODE_BYTES: Int = 96
}

/**
 * Immutable transport envelope. The payload is defensively copied at both boundaries so a caller
 * cannot change the bytes after validation or while a durable commit is in progress.
 */
class BatchEnvelope(
    val protocolVersion: Int = BatchWireLimits.PROTOCOL_VERSION,
    val batchId: String,
    val sessionId: String,
    val deviceId: String,
    val sequence: Long,
    val createdAtEpochMillis: Long,
    val contentSchemaVersion: Int,
    val contentType: String,
    payload: ByteArray,
) {
    private val immutablePayload: ByteArray = payload.copyOf()

    init {
        require(protocolVersion == BatchWireLimits.PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(batchId.matches(BATCH_ID_PATTERN)) { "Invalid batch ID" }
        require(sessionId.matches(SESSION_ID_PATTERN)) { "Invalid session ID" }
        require(deviceId.matches(DEVICE_ID_PATTERN)) { "Invalid device ID" }
        require(sequence >= 0) { "Sequence must be non-negative" }
        require(createdAtEpochMillis >= 0) { "Created time must be non-negative" }
        require(contentSchemaVersion > 0) { "Content schema version must be positive" }
        require(contentType.matches(CONTENT_TYPE_PATTERN)) { "Invalid content type" }
        require(immutablePayload.size <= BatchWireLimits.MAX_PAYLOAD_BYTES) { "Payload is too large" }
    }

    val payloadSize: Int get() = immutablePayload.size

    fun payloadCopy(): ByteArray = immutablePayload.copyOf()

    internal fun payloadForEncoding(): ByteArray = immutablePayload

    override fun equals(other: Any?): Boolean =
        other is BatchEnvelope &&
            protocolVersion == other.protocolVersion &&
            batchId == other.batchId &&
            sessionId == other.sessionId &&
            deviceId == other.deviceId &&
            sequence == other.sequence &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            contentSchemaVersion == other.contentSchemaVersion &&
            contentType == other.contentType &&
            immutablePayload.contentEquals(other.immutablePayload)

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + batchId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + createdAtEpochMillis.hashCode()
        result = 31 * result + contentSchemaVersion
        result = 31 * result + contentType.hashCode()
        return 31 * result + immutablePayload.contentHashCode()
    }

    companion object {
        private val BATCH_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,96}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private val CONTENT_TYPE_PATTERN = Regex("[a-z0-9][a-z0-9.+_-]{0,63}")
    }
}

enum class ReceiptDisposition(val wireCode: String) {
    ACK("ack"),
    NACK("nack");

    companion object {
        fun fromWireCode(value: String): ReceiptDisposition? = entries.firstOrNull { it.wireCode == value }
    }
}

enum class ReceiptReason(val wireCode: String) {
    DURABLY_COMMITTED("durably_committed"),
    DURABLE_DUPLICATE("durable_duplicate"),
    OVERSIZE("oversize"),
    MALFORMED("malformed"),
    UNSUPPORTED_VERSION("unsupported_version"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    TRAILING_BYTES("trailing_bytes"),
    AUTHENTICATION_FAILED("authentication_failed"),
    UNKNOWN_KEY("unknown_key"),
    ID_CONFLICT("id_conflict"),
    OUT_OF_ORDER("out_of_order"),
    STORE_FAILURE("store_failure");

    val isAckReason: Boolean
        get() = this == DURABLY_COMMITTED || this == DURABLE_DUPLICATE

    companion object {
        fun fromWireCode(value: String): ReceiptReason? = entries.firstOrNull { it.wireCode == value }
    }
}

enum class QuarantineDisposition(val wireCode: String) {
    NOT_APPLICABLE("not_applicable"),
    RECORDED("recorded"),
    RECORDING_FAILED("recording_failed");

    companion object {
        fun fromWireCode(value: String): QuarantineDisposition? = entries.firstOrNull { it.wireCode == value }
    }
}

/** Versioned acknowledgement contract. ACK means the sink confirmed durable state. */
data class BatchAcknowledgement(
    val protocolVersion: Int = BatchWireLimits.PROTOCOL_VERSION,
    val disposition: ReceiptDisposition,
    val reason: ReceiptReason,
    val receiptId: String,
    val batchId: String?,
    val sessionId: String?,
    val sequence: Long?,
    val receivedAtEpochMillis: Long,
    val wireSha256Hex: String,
    val durableCommitToken: String?,
    val quarantineDisposition: QuarantineDisposition,
    val quarantineToken: String?,
    val detailCode: String,
) {
    init {
        require(protocolVersion == BatchWireLimits.PROTOCOL_VERSION) { "Unsupported protocol version" }
        require(receiptId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "Invalid receipt ID" }
        require(batchId == null || batchId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "Invalid batch ID" }
        require(sessionId == null || sessionId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) { "Invalid session ID" }
        require(sequence == null || sequence >= 0) { "Invalid sequence" }
        require(receivedAtEpochMillis >= 0) { "Invalid receive time" }
        require(wireSha256Hex.matches(Regex("[a-f0-9]{64}"))) { "Invalid wire digest" }
        require(detailCode.matches(Regex("[a-z0-9_.-]{1,96}"))) { "Invalid detail code" }
        require(durableCommitToken == null || durableCommitToken.utf8Size() in 1..BatchWireLimits.MAX_TOKEN_BYTES) {
            "Invalid durable commit token"
        }
        require(quarantineToken == null || quarantineToken.utf8Size() in 1..BatchWireLimits.MAX_TOKEN_BYTES) {
            "Invalid quarantine token"
        }

        if (disposition == ReceiptDisposition.ACK) {
            require(reason.isAckReason) { "ACK requires a durable ACK reason" }
            require(batchId != null && sessionId != null && sequence != null) { "ACK requires batch identity" }
            require(!durableCommitToken.isNullOrBlank()) { "ACK requires a durable commit token" }
            require(quarantineDisposition == QuarantineDisposition.NOT_APPLICABLE)
            require(quarantineToken == null)
        } else {
            require(!reason.isAckReason) { "NACK cannot use a durable ACK reason" }
            require(durableCommitToken == null) { "NACK cannot carry a durable commit token" }
            require(quarantineDisposition != QuarantineDisposition.NOT_APPLICABLE)
            require(
                (quarantineDisposition == QuarantineDisposition.RECORDED) == !quarantineToken.isNullOrBlank(),
            ) { "Recorded quarantine requires a token; failed quarantine must not carry one" }
        }
    }
}

enum class DecodeFailureCode {
    OVERSIZE,
    TRUNCATED,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    MALFORMED,
    CHECKSUM_MISMATCH,
    TRAILING_BYTES,
}

class WireDecodeException(
    val failureCode: DecodeFailureCode,
    message: String,
) : IllegalArgumentException(message)

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
