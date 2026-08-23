package au.com.elied.vitalsignal.wear.transport

import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementResult
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.BatchWireLimits
import au.com.elied.vitalsignal.transport.DeletionDenialReason
import au.com.elied.vitalsignal.transport.OutboxAcknowledgementDecision
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

/** A consent version is a fence, not merely UI state. Older queued data cannot cross it. */
data class WatchConsentFence(
    val generation: Long,
    val installedAtEpochMillis: Long,
    val transferAllowed: Boolean,
) {
    init {
        require(generation > 0L)
        require(installedAtEpochMillis >= 0L)
    }
}

enum class WatchBatchSource {
    WEAR_HEALTH_SERVICES,
    SAMSUNG_SENSOR_SDK,
    SIMULATOR,
}

/** Measurement time and origin are retained independently from batch creation/receipt time. */
class WatchBatchProvenance(
    val source: WatchBatchSource,
    val consentGeneration: Long,
    val firstMeasurementEpochMillis: Long,
    val lastMeasurementEpochMillis: Long,
    sourceRecordIds: List<String>,
) {
    val sourceRecordIds: List<String> = java.util.List.copyOf(sourceRecordIds)

    init {
        require(consentGeneration > 0L)
        require(firstMeasurementEpochMillis >= 0L)
        require(lastMeasurementEpochMillis >= firstMeasurementEpochMillis)
        require(this.sourceRecordIds.isNotEmpty())
        require(this.sourceRecordIds.size <= MAX_SOURCE_RECORD_IDS)
        require(this.sourceRecordIds.distinct().size == this.sourceRecordIds.size)
        require(this.sourceRecordIds.all { it.matches(SAFE_PROVENANCE_ID) })
    }

    override fun equals(other: Any?): Boolean = other is WatchBatchProvenance &&
        source == other.source && consentGeneration == other.consentGeneration &&
        firstMeasurementEpochMillis == other.firstMeasurementEpochMillis &&
        lastMeasurementEpochMillis == other.lastMeasurementEpochMillis &&
        sourceRecordIds == other.sourceRecordIds

    override fun hashCode(): Int = listOf(
        source,
        consentGeneration,
        firstMeasurementEpochMillis,
        lastMeasurementEpochMillis,
        sourceRecordIds,
    ).hashCode()

    private companion object {
        const val MAX_SOURCE_RECORD_IDS = 256
        val SAFE_PROVENANCE_ID = Regex("[A-Za-z0-9._:@/-]{1,160}")
    }
}

data class WatchOutboxLimits(
    val maximumRecords: Int = 24,
    val maximumCanonicalWireBytes: Int = 8 * 1024 * 1024,
) {
    init {
        require(maximumRecords in 1..256)
        require(maximumCanonicalWireBytes in BatchWireLimits.MAX_ENVELOPE_BYTES..(64 * 1024 * 1024))
    }
}

data class DeterministicRetryPolicy(
    val initialDelayMillis: Long = 5_000L,
    val maximumDelayMillis: Long = 60L * 60L * 1_000L,
) {
    init {
        require(initialDelayMillis > 0L)
        require(maximumDelayMillis >= initialDelayMillis)
    }

    fun delayAfterAttempt(attemptNumber: Int): Long {
        require(attemptNumber > 0)
        var delay = initialDelayMillis
        repeat(min(attemptNumber - 1, 62)) {
            delay = if (delay > maximumDelayMillis / 2L) maximumDelayMillis else delay * 2L
        }
        return delay.coerceAtMost(maximumDelayMillis)
    }
}

data class StoredDataItem(
    val uri: String,
    val canonicalWireSha256: String,
    val queuedAtEpochMillis: Long,
) {
    init {
        require(uri.length in 1..MAX_URI_CHARACTERS)
        require(canonicalWireSha256.matches(SHA_256_HEX))
        require(queuedAtEpochMillis >= 0L)
    }

    private companion object {
        const val MAX_URI_CHARACTERS = 2_048
        val SHA_256_HEX = Regex("[a-f0-9]{64}")
    }
}

data class PendingWatchDeletion(
    val receiptId: String,
    val durableCommitToken: String,
    val acknowledgementKeyId: String,
) {
    init {
        require(receiptId.matches(SAFE_ID))
        require(durableCommitToken.length in 1..BatchWireLimits.MAX_TOKEN_BYTES)
        require(acknowledgementKeyId.matches(SAFE_ID))
    }

    fun authorization(batchId: String) = OutboxAcknowledgementDecision.DeletionAuthorized(
        batchId = batchId,
        receiptId = receiptId,
        durableCommitToken = durableCommitToken,
        acknowledgementKeyId = acknowledgementKeyId,
    )

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

/** Defensive public view of one durable outbox record. */
class WatchOutboxItem internal constructor(
    val ordinal: Long,
    canonicalWire: ByteArray,
    val provenance: WatchBatchProvenance,
    val acceptedAtEpochMillis: Long,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val dataItem: StoredDataItem?,
    val pendingDeletion: PendingWatchDeletion?,
) {
    private val immutableWire = canonicalWire.copyOf()
    val envelope: BatchEnvelope = BatchEnvelopeCodec.decode(immutableWire)
    val batchId: String get() = envelope.batchId
    val canonicalWireSha256: String = sha256Hex(immutableWire)
    val canonicalWireSize: Int get() = immutableWire.size

    init {
        require(ordinal > 0L)
        require(acceptedAtEpochMillis >= 0L)
        require(attemptCount >= 0)
        require(nextAttemptAtEpochMillis >= 0L)
        require(provenance.lastMeasurementEpochMillis <= envelope.createdAtEpochMillis + MAX_FUTURE_SKEW_MILLIS) {
            "Measurement time is implausibly after batch creation time"
        }
        require(dataItem == null || dataItem.canonicalWireSha256 == canonicalWireSha256)
        require(pendingDeletion == null || dataItem != null)
    }

    fun canonicalWireCopy(): ByteArray = immutableWire.copyOf()

    internal fun copyWith(
        attemptCount: Int = this.attemptCount,
        nextAttemptAtEpochMillis: Long = this.nextAttemptAtEpochMillis,
        dataItem: StoredDataItem? = this.dataItem,
        pendingDeletion: PendingWatchDeletion? = this.pendingDeletion,
    ): WatchOutboxItem = WatchOutboxItem(
        ordinal = ordinal,
        canonicalWire = immutableWire,
        provenance = provenance,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
        attemptCount = attemptCount,
        nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        dataItem = dataItem,
        pendingDeletion = pendingDeletion,
    )

    internal fun safeCopy(): WatchOutboxItem = copyWith()

    private companion object {
        const val MAX_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L
    }
}

class WatchOutboxSnapshot(
    val revision: Long,
    val activeConsentFence: WatchConsentFence?,
    items: List<WatchOutboxItem>,
) {
    val items: List<WatchOutboxItem> = java.util.List.copyOf(items)
    val canonicalWireBytes: Long = this.items.sumOf { it.canonicalWireSize.toLong() }
    val staleGenerationCount: Int = this.items.count {
        it.provenance.consentGeneration != activeConsentFence?.generation
    }
}

sealed interface ConsentFenceUpdateResult {
    data class Installed(val fence: WatchConsentFence) : ConsentFenceUpdateResult
    data object Unchanged : ConsentFenceUpdateResult
    data class Rejected(val reason: String) : ConsentFenceUpdateResult
}

sealed interface WatchOutboxEnqueueResult {
    data class Accepted(val item: WatchOutboxItem) : WatchOutboxEnqueueResult
    data class Duplicate(val item: WatchOutboxItem) : WatchOutboxEnqueueResult
    data class Rejected(val code: String) : WatchOutboxEnqueueResult
}

sealed interface PendingAttemptResult {
    data class Ready(val item: WatchOutboxItem) : PendingAttemptResult
    data object NothingDue : PendingAttemptResult
}

sealed interface DeletionStagingResult {
    data class Staged(val item: WatchOutboxItem) : DeletionStagingResult
    data class Rejected(val code: String) : DeletionStagingResult
}

interface DurableWatchOutbox {
    fun snapshot(): WatchOutboxSnapshot
    fun installConsentFence(fence: WatchConsentFence): ConsentFenceUpdateResult
    fun enqueue(
        envelope: BatchEnvelope,
        provenance: WatchBatchProvenance,
        acceptedAtEpochMillis: Long,
    ): WatchOutboxEnqueueResult
    fun beginNextAttempt(nowEpochMillis: Long, retryPolicy: DeterministicRetryPolicy): PendingAttemptResult
    fun markDataItemQueued(batchId: String, expectedWireSha256: String, dataItem: StoredDataItem): Boolean
    fun stageExactDeletion(
        batchId: String,
        expectedWireSha256: String,
        pendingDeletion: PendingWatchDeletion,
    ): DeletionStagingResult
    fun deleteExactAuthorized(
        batchId: String,
        expectedWireSha256: String,
        receiptId: String,
    ): Boolean
}

class WatchOutboxRecoveryException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * One encrypted, atomically replaced watch snapshot. Every mutating call returns only after the
 * new snapshot is fsynced and published. A stale temporary file is never mistaken for committed
 * state. Corruption or a wrong key fails closed instead of presenting an empty queue.
 *
 * This is single-process. The Android integration must construct one process-wide instance.
 */
class EncryptedSnapshotWatchOutbox(
    private val rootDirectory: Path,
    private val secretKey: SecretKey,
    private val keyId: String,
    private val secureRandom: SecureRandom,
    private val limits: WatchOutboxLimits = WatchOutboxLimits(),
) : DurableWatchOutbox {
    private val snapshotPath = rootDirectory.resolve(SNAPSHOT_FILE_NAME)
    private var state: MutableOutboxState

    init {
        require(secretKey.algorithm.equals("AES", ignoreCase = true))
        require(keyId.matches(SAFE_KEY_ID))
        Files.createDirectories(rootDirectory)
        state = recover()
    }

    @Synchronized
    override fun snapshot(): WatchOutboxSnapshot = state.publicSnapshot()

    @Synchronized
    override fun installConsentFence(fence: WatchConsentFence): ConsentFenceUpdateResult {
        val current = state.consentFence
        if (current == fence) return ConsentFenceUpdateResult.Unchanged
        if (current != null) {
            if (fence.generation < current.generation) {
                return ConsentFenceUpdateResult.Rejected("consent_generation_rollback")
            }
            if (fence.generation == current.generation && !current.transferAllowed && fence.transferAllowed) {
                return ConsentFenceUpdateResult.Rejected("revoked_generation_cannot_be_reenabled")
            }
            if (fence.generation == current.generation && fence.installedAtEpochMillis < current.installedAtEpochMillis) {
                return ConsentFenceUpdateResult.Rejected("consent_timestamp_rollback")
            }
        }
        commit(state.copy(consentFence = fence))
        return ConsentFenceUpdateResult.Installed(fence)
    }

    @Synchronized
    override fun enqueue(
        envelope: BatchEnvelope,
        provenance: WatchBatchProvenance,
        acceptedAtEpochMillis: Long,
    ): WatchOutboxEnqueueResult {
        require(acceptedAtEpochMillis >= 0L)
        val wire = BatchEnvelopeCodec.encode(envelope)
        WearDataItemPayloadPolicy.rejectionCode(wire.size)?.let { code ->
            return WatchOutboxEnqueueResult.Rejected(code)
        }
        val existing = state.items.firstOrNull { it.batchId == envelope.batchId }
        if (existing != null) {
            val same = MessageDigest.isEqual(existing.canonicalWireCopy(), wire) && existing.provenance == provenance
            return if (same) WatchOutboxEnqueueResult.Duplicate(existing.safeCopy())
            else WatchOutboxEnqueueResult.Rejected("batch_id_conflict")
        }
        val consent = state.consentFence
            ?: return WatchOutboxEnqueueResult.Rejected("consent_not_installed")
        if (!consent.transferAllowed) return WatchOutboxEnqueueResult.Rejected("transfer_not_consented")
        if (provenance.consentGeneration != consent.generation) {
            return WatchOutboxEnqueueResult.Rejected("consent_generation_mismatch")
        }
        if (acceptedAtEpochMillis < provenance.lastMeasurementEpochMillis) {
            return WatchOutboxEnqueueResult.Rejected("accepted_before_measurement")
        }
        if (state.items.size >= limits.maximumRecords) {
            return WatchOutboxEnqueueResult.Rejected("record_limit_reached")
        }
        if (state.totalWireBytes() + wire.size > limits.maximumCanonicalWireBytes) {
            return WatchOutboxEnqueueResult.Rejected("byte_limit_reached")
        }
        val item = try {
            WatchOutboxItem(
                ordinal = state.nextOrdinal,
                canonicalWire = wire,
                provenance = provenance,
                acceptedAtEpochMillis = acceptedAtEpochMillis,
                attemptCount = 0,
                nextAttemptAtEpochMillis = acceptedAtEpochMillis,
                dataItem = null,
                pendingDeletion = null,
            )
        } catch (_: IllegalArgumentException) {
            return WatchOutboxEnqueueResult.Rejected("invalid_provenance_time")
        }
        commit(
            state.copy(
                nextOrdinal = state.nextOrdinal + 1L,
                items = state.items + item,
            ),
        )
        return WatchOutboxEnqueueResult.Accepted(item.safeCopy())
    }

    @Synchronized
    override fun beginNextAttempt(
        nowEpochMillis: Long,
        retryPolicy: DeterministicRetryPolicy,
    ): PendingAttemptResult {
        require(nowEpochMillis >= 0L)
        val consent = state.consentFence
        if (consent?.transferAllowed != true) return PendingAttemptResult.NothingDue
        val candidate = state.items
            .asSequence()
            .filter { it.pendingDeletion == null }
            .filter { it.provenance.consentGeneration == consent.generation }
            .filter { it.nextAttemptAtEpochMillis <= nowEpochMillis }
            .minByOrNull { it.ordinal }
            ?: return PendingAttemptResult.NothingDue
        val newAttemptCount = candidate.attemptCount + 1
        val attempted = candidate.copyWith(
            attemptCount = newAttemptCount,
            nextAttemptAtEpochMillis = safeAdd(
                nowEpochMillis,
                retryPolicy.delayAfterAttempt(newAttemptCount),
            ),
        )
        commit(state.replace(attempted))
        return PendingAttemptResult.Ready(attempted.safeCopy())
    }

    @Synchronized
    override fun markDataItemQueued(
        batchId: String,
        expectedWireSha256: String,
        dataItem: StoredDataItem,
    ): Boolean {
        val existing = state.items.firstOrNull { it.batchId == batchId } ?: return false
        if (existing.pendingDeletion != null ||
            !constantTimeEquals(existing.canonicalWireSha256, expectedWireSha256) ||
            !constantTimeEquals(existing.canonicalWireSha256, dataItem.canonicalWireSha256) ||
            !isExactVitalSignalUri(dataItem.uri, batchId)
        ) return false
        commit(state.replace(existing.copyWith(dataItem = dataItem)))
        return true
    }

    @Synchronized
    override fun stageExactDeletion(
        batchId: String,
        expectedWireSha256: String,
        pendingDeletion: PendingWatchDeletion,
    ): DeletionStagingResult {
        val existing = state.items.firstOrNull { it.batchId == batchId }
            ?: return DeletionStagingResult.Rejected("batch_not_queued")
        if (!constantTimeEquals(existing.canonicalWireSha256, expectedWireSha256)) {
            return DeletionStagingResult.Rejected("wire_digest_mismatch")
        }
        if (existing.dataItem == null) return DeletionStagingResult.Rejected("data_item_not_queued")
        val receiptOwner = state.items.firstOrNull {
            it.pendingDeletion?.receiptId == pendingDeletion.receiptId
        }
        if (receiptOwner != null) {
            return if (receiptOwner.batchId == batchId && receiptOwner.pendingDeletion == pendingDeletion) {
                DeletionStagingResult.Staged(receiptOwner.safeCopy())
            } else {
                DeletionStagingResult.Rejected("receipt_replay")
            }
        }
        if (existing.pendingDeletion != null) {
            return DeletionStagingResult.Rejected("different_deletion_already_staged")
        }
        val staged = existing.copyWith(pendingDeletion = pendingDeletion)
        commit(state.replace(staged))
        return DeletionStagingResult.Staged(staged.safeCopy())
    }

    @Synchronized
    override fun deleteExactAuthorized(
        batchId: String,
        expectedWireSha256: String,
        receiptId: String,
    ): Boolean {
        val existing = state.items.firstOrNull { it.batchId == batchId } ?: return false
        if (!constantTimeEquals(existing.canonicalWireSha256, expectedWireSha256) ||
            existing.pendingDeletion?.receiptId != receiptId
        ) return false
        commit(state.copy(items = state.items.filterNot { it.ordinal == existing.ordinal }))
        return true
    }

    private fun commit(next: MutableOutboxState) {
        val committed = next.copy(revision = state.revision + 1L)
        validateState(committed)
        persist(committed)
        state = committed
    }

    private fun persist(next: MutableOutboxState) {
        val plaintext = encodeState(next)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val keyBytes = keyId.toByteArray(Charsets.US_ASCII)
        val aad = aad(keyBytes, nonce)
        val ciphertext = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }
        val envelope = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(FILE_MAGIC)
                output.writeInt(FILE_VERSION)
                output.writeInt(keyBytes.size)
                output.write(keyBytes)
                output.writeInt(nonce.size)
                output.write(nonce)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            buffer.toByteArray()
        }
        var temporary: Path? = null
        try {
            temporary = Files.createTempFile(rootDirectory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
            FileChannel.open(temporary, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(envelope)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, snapshotPath, ATOMIC_MOVE, REPLACE_EXISTING)
            temporary = null
            runCatching { FileChannel.open(rootDirectory, READ).use { it.force(true) } }
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }

    private fun recover(): MutableOutboxState {
        if (!Files.exists(snapshotPath)) return MutableOutboxState.empty()
        return try {
            val maximumFileBytes = limits.maximumCanonicalWireBytes.toLong() +
                limits.maximumRecords.toLong() * MAX_METADATA_BYTES_PER_RECORD + 64L * 1024L
            val size = Files.size(snapshotPath)
            if (size !in MINIMUM_FILE_BYTES.toLong()..maximumFileBytes) {
                throw WatchOutboxRecoveryException("Watch outbox snapshot size is invalid")
            }
            val bytes = Files.readAllBytes(snapshotPath)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == FILE_MAGIC)
            require(input.readInt() == FILE_VERSION)
            val keyBytes = input.readBoundedBytes(MAX_KEY_ID_BYTES)
            val storedKeyId = keyBytes.toString(Charsets.US_ASCII)
            if (storedKeyId != keyId) throw WatchOutboxRecoveryException("Watch outbox key generation changed")
            val nonce = input.readBoundedBytes(GCM_NONCE_BYTES)
            require(nonce.size == GCM_NONCE_BYTES)
            val ciphertext = input.readBoundedBytes(maximumFileBytes.toInt())
            require(input.available() == 0)
            val plaintext = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(aad(keyBytes, nonce))
                doFinal(ciphertext)
            }
            decodeState(plaintext).also(::validateState)
        } catch (error: WatchOutboxRecoveryException) {
            throw error
        } catch (error: AEADBadTagException) {
            throw WatchOutboxRecoveryException("Watch outbox authentication failed", error)
        } catch (error: Exception) {
            throw WatchOutboxRecoveryException("Watch outbox recovery failed closed", error)
        }
    }

    private fun validateState(candidate: MutableOutboxState) {
        require(candidate.revision >= 0L)
        require(candidate.nextOrdinal > 0L)
        require(candidate.items.size <= limits.maximumRecords)
        require(candidate.totalWireBytes() <= limits.maximumCanonicalWireBytes)
        require(candidate.items.map { it.ordinal }.distinct().size == candidate.items.size)
        require(candidate.items.map { it.batchId }.distinct().size == candidate.items.size)
        require(candidate.items.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal })
        require(candidate.items.all { it.ordinal < candidate.nextOrdinal })
        val receipts = candidate.items.mapNotNull { it.pendingDeletion?.receiptId }
        require(receipts.distinct().size == receipts.size)
    }

    private fun encodeState(value: MutableOutboxState): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(STATE_MAGIC)
            output.writeInt(STATE_VERSION)
            output.writeLong(value.revision)
            output.writeLong(value.nextOrdinal)
            output.writeBoolean(value.consentFence != null)
            value.consentFence?.let { fence ->
                output.writeLong(fence.generation)
                output.writeLong(fence.installedAtEpochMillis)
                output.writeBoolean(fence.transferAllowed)
            }
            output.writeInt(value.items.size)
            value.items.forEach { item -> encodeItem(output, item) }
        }
        buffer.toByteArray()
    }

    private fun decodeState(bytes: ByteArray): MutableOutboxState {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == STATE_MAGIC)
        require(input.readInt() == STATE_VERSION)
        val revision = input.readLong().also { require(it >= 0L) }
        val nextOrdinal = input.readLong().also { require(it > 0L) }
        val consent = if (input.readBoolean()) {
            WatchConsentFence(input.readLong(), input.readLong(), input.readBoolean())
        } else null
        val count = input.readInt().also { require(it in 0..limits.maximumRecords) }
        val items = ArrayList<WatchOutboxItem>(count)
        repeat(count) { items += decodeItem(input) }
        require(input.available() == 0)
        return MutableOutboxState(revision, nextOrdinal, consent, items.sortedBy { it.ordinal })
    }

    private fun encodeItem(output: DataOutputStream, item: WatchOutboxItem) {
        output.writeLong(item.ordinal)
        output.writeBounded(item.canonicalWireCopy())
        output.writeInt(item.provenance.source.ordinal)
        output.writeLong(item.provenance.consentGeneration)
        output.writeLong(item.provenance.firstMeasurementEpochMillis)
        output.writeLong(item.provenance.lastMeasurementEpochMillis)
        output.writeInt(item.provenance.sourceRecordIds.size)
        item.provenance.sourceRecordIds.forEach(output::writeBoundedString)
        output.writeLong(item.acceptedAtEpochMillis)
        output.writeInt(item.attemptCount)
        output.writeLong(item.nextAttemptAtEpochMillis)
        output.writeBoolean(item.dataItem != null)
        item.dataItem?.let { dataItem ->
            output.writeBoundedString(dataItem.uri)
            output.writeBoundedString(dataItem.canonicalWireSha256)
            output.writeLong(dataItem.queuedAtEpochMillis)
        }
        output.writeBoolean(item.pendingDeletion != null)
        item.pendingDeletion?.let { deletion ->
            output.writeBoundedString(deletion.receiptId)
            output.writeBoundedString(deletion.durableCommitToken)
            output.writeBoundedString(deletion.acknowledgementKeyId)
        }
    }

    private fun decodeItem(input: DataInputStream): WatchOutboxItem {
        val ordinal = input.readLong()
        val wire = input.readBoundedBytes(BatchWireLimits.MAX_ENVELOPE_BYTES)
        val sourceOrdinal = input.readInt()
        val source = WatchBatchSource.entries.getOrNull(sourceOrdinal) ?: error("Unknown source")
        val consentGeneration = input.readLong()
        val firstMeasurement = input.readLong()
        val lastMeasurement = input.readLong()
        val provenanceCount = input.readInt().also { require(it in 1..256) }
        val sourceIds = List(provenanceCount) { input.readBoundedString(160) }
        val acceptedAt = input.readLong()
        val attemptCount = input.readInt()
        val nextAttemptAt = input.readLong()
        val dataItem = if (input.readBoolean()) {
            StoredDataItem(
                uri = input.readBoundedString(2_048),
                canonicalWireSha256 = input.readBoundedString(64),
                queuedAtEpochMillis = input.readLong(),
            )
        } else null
        val deletion = if (input.readBoolean()) {
            PendingWatchDeletion(
                receiptId = input.readBoundedString(96),
                durableCommitToken = input.readBoundedString(BatchWireLimits.MAX_TOKEN_BYTES),
                acknowledgementKeyId = input.readBoundedString(96),
            )
        } else null
        return WatchOutboxItem(
            ordinal = ordinal,
            canonicalWire = wire,
            provenance = WatchBatchProvenance(
                source = source,
                consentGeneration = consentGeneration,
                firstMeasurementEpochMillis = firstMeasurement,
                lastMeasurementEpochMillis = lastMeasurement,
                sourceRecordIds = sourceIds,
            ),
            acceptedAtEpochMillis = acceptedAt,
            attemptCount = attemptCount,
            nextAttemptAtEpochMillis = nextAttemptAt,
            dataItem = dataItem,
            pendingDeletion = deletion,
        )
    }

    private data class MutableOutboxState(
        val revision: Long,
        val nextOrdinal: Long,
        val consentFence: WatchConsentFence?,
        val items: List<WatchOutboxItem>,
    ) {
        fun replace(replacement: WatchOutboxItem): MutableOutboxState = copy(
            items = items.map { if (it.ordinal == replacement.ordinal) replacement else it },
        )

        fun totalWireBytes(): Long = items.sumOf { it.canonicalWireSize.toLong() }

        fun publicSnapshot() = WatchOutboxSnapshot(
            revision = revision,
            activeConsentFence = consentFence,
            items = items.map(WatchOutboxItem::safeCopy),
        )

        companion object {
            fun empty() = MutableOutboxState(0L, 1L, null, emptyList())
        }
    }

    private companion object {
        const val SNAPSHOT_FILE_NAME = "watch-outbox.vsob"
        const val TEMPORARY_PREFIX = ".watch-outbox-"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val FILE_MAGIC = 0x56534f42 // VSOB
        const val FILE_VERSION = 1
        const val STATE_MAGIC = 0x56534f53 // VSOS
        const val STATE_VERSION = 1
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_KEY_ID_BYTES = 96
        const val MAX_METADATA_BYTES_PER_RECORD = 64L * 1024L
        const val MINIMUM_FILE_BYTES = 4 + 4 + 4 + 1 + 4 + GCM_NONCE_BYTES + 4 + 16
        val SAFE_KEY_ID = Regex("[A-Za-z0-9._:-]{1,$MAX_KEY_ID_BYTES}")
    }
}

sealed interface WatchSendAttemptResult {
    data class Started(val batchId: String, val attemptCount: Int) : WatchSendAttemptResult
    data object NothingDue : WatchSendAttemptResult
}

sealed interface WatchAcknowledgementResult {
    data class DeletionStarted(val batchId: String, val receiptId: String) : WatchAcknowledgementResult
    data class Denied(val reason: DeletionDenialReason) : WatchAcknowledgementResult
    data class NotQueued(val code: String) : WatchAcknowledgementResult
}

/**
 * Deterministic domain coordinator around the asynchronous Data Layer. Local enqueue happens first.
 * A phone ACK is authenticated and exact-matched, then its deletion authorization is atomically
 * staged with the record before the remote Data Item is removed. Failed removals remain retryable
 * after process restart without accepting the ACK a second time.
 */
class WatchOutboxCoordinator(
    private val outbox: DurableWatchOutbox,
    private val transport: DataLayerBatchTransport,
    private val acknowledgementKeyResolver: AcknowledgementKeyResolver,
    private val retryPolicy: DeterministicRetryPolicy = DeterministicRetryPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun attemptNext(): WatchSendAttemptResult {
        val attempt = outbox.beginNextAttempt(now(), retryPolicy)
        if (attempt !is PendingAttemptResult.Ready) return WatchSendAttemptResult.NothingDue
        val item = attempt.item
        transport.enqueue(item.envelope, item.provenance.consentGeneration) { result ->
            if (result is BatchQueueResult.Queued &&
                result.batchId == item.batchId &&
                result.consentGeneration == item.provenance.consentGeneration &&
                constantTimeEquals(result.canonicalWireSha256, item.canonicalWireSha256)
            ) {
                outbox.markDataItemQueued(
                    batchId = item.batchId,
                    expectedWireSha256 = item.canonicalWireSha256,
                    dataItem = StoredDataItem(
                        uri = result.dataItemUri,
                        canonicalWireSha256 = result.canonicalWireSha256,
                        queuedAtEpochMillis = now(),
                    ),
                )
            }
            // Failure or a mismatched callback leaves the persisted retry schedule intact.
        }
        return WatchSendAttemptResult.Started(item.batchId, item.attemptCount)
    }

    fun handleAcknowledgement(
        encodedAcknowledgement: ByteArray,
        consentGeneration: Long,
    ): WatchAcknowledgementResult {
        if (consentGeneration <= 0L ||
            outbox.snapshot().activeConsentFence?.generation != consentGeneration
        ) {
            return WatchAcknowledgementResult.NotQueued("consent_generation_mismatch")
        }
        val authenticated = when (
            val decoded = AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(
                encodedAcknowledgement,
                acknowledgementKeyResolver,
            )
        ) {
            is AuthenticatedAcknowledgementResult.Authenticated -> decoded
            is AuthenticatedAcknowledgementResult.UnknownKey -> {
                return WatchAcknowledgementResult.Denied(DeletionDenialReason.ACK_KEY_UNAVAILABLE)
            }
            AuthenticatedAcknowledgementResult.AuthenticationFailed -> {
                return WatchAcknowledgementResult.Denied(DeletionDenialReason.ACK_AUTHENTICATION_FAILED)
            }
            AuthenticatedAcknowledgementResult.Malformed -> {
                return WatchAcknowledgementResult.Denied(DeletionDenialReason.ACK_WIRE_INVALID)
            }
        }
        val acknowledgement = authenticated.acknowledgement
        if (acknowledgement.disposition != ReceiptDisposition.ACK) {
            return WatchAcknowledgementResult.Denied(DeletionDenialReason.NACK)
        }
        val batchId = acknowledgement.batchId
            ?: return WatchAcknowledgementResult.Denied(DeletionDenialReason.BATCH_ID_MISMATCH)
        val item = outbox.snapshot().items.firstOrNull { it.batchId == batchId }
            ?: return WatchAcknowledgementResult.NotQueued("batch_not_queued")
        if (item.provenance.consentGeneration != consentGeneration) {
            return WatchAcknowledgementResult.NotQueued("consent_generation_mismatch")
        }
        if (acknowledgement.sessionId != item.envelope.sessionId) {
            return WatchAcknowledgementResult.Denied(DeletionDenialReason.SESSION_ID_MISMATCH)
        }
        if (acknowledgement.sequence != item.envelope.sequence) {
            return WatchAcknowledgementResult.Denied(DeletionDenialReason.SEQUENCE_MISMATCH)
        }
        if (!constantTimeEquals(acknowledgement.wireSha256Hex, item.canonicalWireSha256)) {
            return WatchAcknowledgementResult.Denied(DeletionDenialReason.CHECKSUM_MISMATCH)
        }
        val pending = PendingWatchDeletion(
            receiptId = acknowledgement.receiptId,
            durableCommitToken = requireNotNull(acknowledgement.durableCommitToken),
            acknowledgementKeyId = authenticated.keyId,
        )
        val staged = outbox.stageExactDeletion(item.batchId, item.canonicalWireSha256, pending)
        if (staged !is DeletionStagingResult.Staged) {
            val code = (staged as DeletionStagingResult.Rejected).code
            return if (code == "receipt_replay") {
                WatchAcknowledgementResult.Denied(DeletionDenialReason.RECEIPT_REPLAY)
            } else {
                WatchAcknowledgementResult.NotQueued(code)
            }
        }
        continuePendingDeletion(staged.item)
        return WatchAcknowledgementResult.DeletionStarted(item.batchId, pending.receiptId)
    }

    fun retryPendingDeletions(): Int {
        val pending = outbox.snapshot().items.filter { it.pendingDeletion != null && it.dataItem != null }
        pending.forEach(::continuePendingDeletion)
        return pending.size
    }

    private fun continuePendingDeletion(item: WatchOutboxItem) {
        val dataItem = item.dataItem ?: return
        val pending = item.pendingDeletion ?: return
        transport.removeAuthorized(
            queued = BatchQueueResult.Queued(
                batchId = item.batchId,
                dataItemUri = dataItem.uri,
                canonicalWireSha256 = dataItem.canonicalWireSha256,
                consentGeneration = item.provenance.consentGeneration,
            ),
            authorization = pending.authorization(item.batchId),
        ) { result ->
            if (result.isSuccess) {
                outbox.deleteExactAuthorized(
                    batchId = item.batchId,
                    expectedWireSha256 = item.canonicalWireSha256,
                    receiptId = pending.receiptId,
                )
            }
        }
    }
}

private fun aad(keyId: ByteArray, nonce: ByteArray): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use { output ->
        output.writeInt(0x56534f42)
        output.writeInt(1)
        output.writeInt(keyId.size)
        output.write(keyId)
        output.writeInt(nonce.size)
        output.write(nonce)
    }
    buffer.toByteArray()
}

private fun DataOutputStream.writeBounded(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeBoundedString(value: String) =
    writeBounded(value.toByteArray(Charsets.UTF_8))

private fun DataInputStream.readBoundedBytes(maximum: Int): ByteArray {
    val size = readInt()
    require(size in 1..maximum)
    val bytes = ByteArray(size)
    readFully(bytes)
    return bytes
}

private fun DataInputStream.readBoundedString(maximumCharacters: Int): String =
    readBoundedBytes(maximumCharacters * 4).toString(Charsets.UTF_8).also {
        require(it.length in 1..maximumCharacters)
    }

private fun safeAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun isExactVitalSignalUri(value: String, batchId: String): Boolean = runCatching {
    URI(value).path == "/v1/research/batches/$batchId"
}.getOrDefault(false)

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
