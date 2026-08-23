package au.com.elied.vitalsignal.storage

import au.com.elied.vitalsignal.transport.AcknowledgementReplayStore
import au.com.elied.vitalsignal.transport.ReplayClaimResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Durable watch-side replay guard used before an ACK may authorize outbox deletion. */
class EncryptedAcknowledgementReplayStore(
    private val encryptedStore: EncryptedAppendOnlyRecordStore,
) : AcknowledgementReplayStore {
    @Synchronized
    override fun claim(receiptId: String, batchId: String): ReplayClaimResult {
        if (!receiptId.matches(SAFE_ID) || !batchId.matches(SAFE_ID)) {
            return ReplayClaimResult.StoreFailure
        }
        val report = encryptedStore.recover()
        if (!report.canAppend) return ReplayClaimResult.StoreFailure
        val claims = linkedMapOf<String, String>()
        for (accepted in report.accepted) {
            if (accepted.record.contentType != CONTENT_TYPE) return ReplayClaimResult.StoreFailure
            val decoded = decode(accepted.record.payloadCopy()) ?: return ReplayClaimResult.StoreFailure
            if (accepted.record.recordId != recordId(decoded.first)) return ReplayClaimResult.StoreFailure
            val previous = claims.put(decoded.first, decoded.second)
            if (previous != null) return ReplayClaimResult.StoreFailure
        }
        claims[receiptId]?.let { owner ->
            return if (owner == batchId) ReplayClaimResult.AlreadyClaimed else ReplayClaimResult.StoreFailure
        }

        val append = try {
            encryptedStore.append(
                LocalEncryptedRecord(
                    recordId = recordId(receiptId),
                    sequence = (report.accepted.lastOrNull()?.record?.sequence ?: 0L) + 1L,
                    createdEpochMillis = 0L,
                    contentType = CONTENT_TYPE,
                    payload = encode(receiptId, batchId),
                ),
            )
        } catch (_: RuntimeException) {
            return ReplayClaimResult.StoreFailure
        }
        return when (append) {
            is StorageAppendResult.Accepted -> ReplayClaimResult.Claimed
            is StorageAppendResult.Duplicate -> ReplayClaimResult.AlreadyClaimed
            is StorageAppendResult.Quarantined -> ReplayClaimResult.StoreFailure
        }
    }

    private fun encode(receiptId: String, batchId: String): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeBounded(receiptId)
                output.writeBounded(batchId)
            }
            buffer.toByteArray()
        }

    private fun decode(bytes: ByteArray): Pair<String, String>? = try {
        val cursor = ReplayCursor(bytes)
        if (cursor.readInt() != MAGIC || cursor.readInt() != VERSION) return null
        val receiptId = cursor.readString()
        val batchId = cursor.readString()
        if (cursor.remaining != 0 || !receiptId.matches(SAFE_ID) || !batchId.matches(SAFE_ID)) null
        else receiptId to batchId
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAGIC = 0x56535247 // VSRG
        const val VERSION = 1
        const val CONTENT_TYPE = "application/vnd.vitalsignal.ack-claim.v1"
        val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

private fun recordId(receiptId: String): String = "receipt-" + MessageDigest
    .getInstance("SHA-256")
    .digest(receiptId.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    .take(40)

private fun DataOutputStream.writeBounded(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size in 1..96)
    writeInt(bytes.size)
    write(bytes)
}

private class ReplayCursor(private val bytes: ByteArray) {
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

    fun readString(): String {
        val length = readInt()
        require(length in 1..96 && remaining >= length)
        val value = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, position, length))
            .toString()
        position += length
        return value
    }
}
