package au.com.elied.vitalsignal.phone.data.bridge

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.LocalEncryptedRecord
import au.com.elied.vitalsignal.storage.StorageAppendResult
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.BatchAcknowledgementCodec
import au.com.elied.vitalsignal.transport.BatchWireLimits
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Exact, immutable routing and acknowledgement material retained for at-least-once receipt
 * delivery. The canonical acknowledgement is encrypted at rest by the concrete outbox; no sensor
 * payload or decrypted batch is written here.
 */
class ReceiptDeliveryBinding private constructor(
    val deliveryId: String,
    val consentGeneration: Long,
    val targetNodeId: String,
    val path: String,
    val acknowledgementKeyId: String,
    val createdAtEpochMillis: Long,
    acknowledgementWire: ByteArray,
) {
    private val immutableAcknowledgementWire = acknowledgementWire.copyOf()

    init {
        require(deliveryId.matches(SAFE_DELIVERY_ID)) { "Invalid delivery ID" }
        require(consentGeneration > 0L) { "Consent generation must be positive" }
        require(targetNodeId.isNotBlank() && targetNodeId.utf8Size() <= MAX_NODE_ID_BYTES) {
            "Invalid target node ID"
        }
        require(path.utf8Size() <= MAX_PATH_BYTES) { "Receipt path exceeds its bound" }
        require(acknowledgementKeyId.matches(SAFE_KEY_ID)) { "Invalid acknowledgement key ID" }
        require(createdAtEpochMillis >= 0L) { "Created time must be non-negative" }
        require(immutableAcknowledgementWire.size in 1..BatchWireLimits.MAX_ACK_BYTES) {
            "Canonical acknowledgement exceeds its bound"
        }
        val acknowledgement = decodeAcknowledgement(immutableAcknowledgementWire)
        val batchId = requireNotNull(acknowledgement.batchId) {
            "A receipt delivery requires an identity-bound batch ID"
        }
        require(path == "${PhoneDataLayerBridgeCoordinator.RECEIPT_PATH_PREFIX}/$batchId") {
            "Receipt path and acknowledgement batch ID differ"
        }
        require(deliveryId == deliveryId(canonicalIdentityBytes())) {
            "Delivery ID does not bind the exact retained material"
        }
    }

    fun acknowledgementWireCopy(): ByteArray = immutableAcknowledgementWire.copyOf()

    fun acknowledgement(): BatchAcknowledgement = decodeAcknowledgement(immutableAcknowledgementWire)

    internal fun exactCopy(): ReceiptDeliveryBinding = ReceiptDeliveryBinding(
        deliveryId = deliveryId,
        consentGeneration = consentGeneration,
        targetNodeId = targetNodeId,
        path = path,
        acknowledgementKeyId = acknowledgementKeyId,
        createdAtEpochMillis = createdAtEpochMillis,
        acknowledgementWire = immutableAcknowledgementWire,
    )

    internal fun exactContentEquals(other: ReceiptDeliveryBinding): Boolean =
        deliveryId == other.deliveryId &&
            consentGeneration == other.consentGeneration &&
            targetNodeId == other.targetNodeId &&
            path == other.path &&
            acknowledgementKeyId == other.acknowledgementKeyId &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            MessageDigest.isEqual(immutableAcknowledgementWire, other.immutableAcknowledgementWire)

    private fun canonicalIdentityBytes(): ByteArray = canonicalIdentityBytes(
        consentGeneration = consentGeneration,
        targetNodeId = targetNodeId,
        path = path,
        acknowledgementKeyId = acknowledgementKeyId,
        createdAtEpochMillis = createdAtEpochMillis,
        acknowledgementWire = immutableAcknowledgementWire,
    )

    companion object {
        internal const val MAX_NODE_ID_BYTES = 256
        internal const val MAX_PATH_BYTES = 512
        internal val SAFE_KEY_ID = Regex("[A-Za-z0-9._:-]{1,96}")
        internal val SAFE_DELIVERY_ID = Regex("receipt-[a-f0-9]{48}")

        fun create(
            lease: ActiveBridgeConsentLease,
            acknowledgement: BatchAcknowledgement,
            createdAtEpochMillis: Long,
        ): ReceiptDeliveryBinding {
            val batchId = requireNotNull(acknowledgement.batchId) {
                "A receipt delivery requires an identity-bound batch ID"
            }
            val acknowledgementWire = BatchAcknowledgementCodec.encode(acknowledgement)
            val path = "${PhoneDataLayerBridgeCoordinator.RECEIPT_PATH_PREFIX}/$batchId"
            val identity = canonicalIdentityBytes(
                consentGeneration = lease.consentGeneration,
                targetNodeId = lease.pairedWatchNodeId,
                path = path,
                acknowledgementKeyId = lease.acknowledgementKeyId,
                createdAtEpochMillis = createdAtEpochMillis,
                acknowledgementWire = acknowledgementWire,
            )
            return ReceiptDeliveryBinding(
                deliveryId = deliveryId(identity),
                consentGeneration = lease.consentGeneration,
                targetNodeId = lease.pairedWatchNodeId,
                path = path,
                acknowledgementKeyId = lease.acknowledgementKeyId,
                createdAtEpochMillis = createdAtEpochMillis,
                acknowledgementWire = acknowledgementWire,
            )
        }

        internal fun restore(
            deliveryId: String,
            consentGeneration: Long,
            targetNodeId: String,
            path: String,
            acknowledgementKeyId: String,
            createdAtEpochMillis: Long,
            acknowledgementWire: ByteArray,
        ): ReceiptDeliveryBinding = ReceiptDeliveryBinding(
            deliveryId,
            consentGeneration,
            targetNodeId,
            path,
            acknowledgementKeyId,
            createdAtEpochMillis,
            acknowledgementWire,
        )

        private fun canonicalIdentityBytes(
            consentGeneration: Long,
            targetNodeId: String,
            path: String,
            acknowledgementKeyId: String,
            createdAtEpochMillis: Long,
            acknowledgementWire: ByteArray,
        ): ByteArray = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(IDENTITY_MAGIC)
                output.writeInt(VERSION)
                output.writeLong(consentGeneration)
                output.writeBoundedUtf8(targetNodeId, MAX_NODE_ID_BYTES)
                output.writeBoundedUtf8(path, MAX_PATH_BYTES)
                output.writeBoundedAscii(acknowledgementKeyId, 96)
                output.writeLong(createdAtEpochMillis)
                output.writeInt(acknowledgementWire.size)
                output.write(acknowledgementWire)
            }
            buffer.toByteArray()
        }

        private fun deliveryId(identity: ByteArray): String = "receipt-" + MessageDigest
            .getInstance("SHA-256")
            .digest(identity)
            .toHex()
            .take(48)

        private fun decodeAcknowledgement(bytes: ByteArray): BatchAcknowledgement = try {
            BatchAcknowledgementCodec.decode(bytes)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Canonical acknowledgement is invalid", error)
        }

        private const val IDENTITY_MAGIC = 0x56535249 // VSRI
        private const val VERSION = 1
    }
}

enum class ReceiptDeliveryState {
    PENDING,
    DELIVERED,
    STALE_CONSENT,
    RETRY_EXHAUSTED,
}

/** A defensive recovery view. A terminal entry remains auditable but is never re-sent. */
class ReceiptDeliveryEntry(
    val binding: ReceiptDeliveryBinding,
    val state: ReceiptDeliveryState,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val terminalDetailCode: String?,
    val deliveryToken: String?,
) {
    init {
        require(attemptCount >= 0) { "Attempt count cannot be negative" }
        require(nextAttemptAtEpochMillis >= 0L) { "Next-attempt time cannot be negative" }
        require(terminalDetailCode == null || terminalDetailCode.matches(SAFE_DETAIL_CODE))
        require(deliveryToken == null || deliveryToken.matches(SAFE_DELIVERY_TOKEN))
        when (state) {
            ReceiptDeliveryState.PENDING -> {
                require(terminalDetailCode == null)
                require(deliveryToken == null)
            }
            ReceiptDeliveryState.DELIVERED -> {
                require(deliveryToken != null)
                require(terminalDetailCode == "receipt_delivered")
            }
            ReceiptDeliveryState.STALE_CONSENT,
            ReceiptDeliveryState.RETRY_EXHAUSTED,
            -> {
                require(terminalDetailCode != null)
                require(deliveryToken == null)
            }
        }
    }

    fun safeCopy(): ReceiptDeliveryEntry = ReceiptDeliveryEntry(
        binding = binding.exactCopy(),
        state = state,
        attemptCount = attemptCount,
        nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        terminalDetailCode = terminalDetailCode,
        deliveryToken = deliveryToken,
    )

    companion object {
        internal val SAFE_DELIVERY_TOKEN = Regex("[A-Za-z0-9._:-]{1,256}")
    }
}

sealed interface ReceiptOutboxRecovery {
    data class Ready(
        val entries: List<ReceiptDeliveryEntry>,
        val journalRecordCount: Int,
    ) : ReceiptOutboxRecovery {
        val pending: List<ReceiptDeliveryEntry>
            get() = entries.filter { it.state == ReceiptDeliveryState.PENDING }.map { it.safeCopy() }
    }

    data class RecoveryRequired(val detailCode: String) : ReceiptOutboxRecovery {
        init {
            require(detailCode.matches(SAFE_DETAIL_CODE))
        }
    }
}

sealed interface ReceiptOutboxStageResult {
    data class Staged(val entry: ReceiptDeliveryEntry, val newlyAppended: Boolean) : ReceiptOutboxStageResult
    data class Terminal(val entry: ReceiptDeliveryEntry) : ReceiptOutboxStageResult
    data class Rejected(val detailCode: String) : ReceiptOutboxStageResult
}

sealed interface ReceiptOutboxTransitionResult {
    data class Updated(val entry: ReceiptDeliveryEntry) : ReceiptOutboxTransitionResult
    data class Rejected(val detailCode: String) : ReceiptOutboxTransitionResult
}

/**
 * Crash-safe persistence contract. Implementations must not acknowledge an append until the exact
 * event survives restart. There is intentionally no in-memory production fallback.
 */
interface ReceiptDeliveryOutbox {
    fun recover(): ReceiptOutboxRecovery
    fun stage(binding: ReceiptDeliveryBinding): ReceiptOutboxStageResult
    fun recordFailure(deliveryId: String, attemptedAtEpochMillis: Long, detailCode: String): ReceiptOutboxTransitionResult
    fun recordDelivered(
        deliveryId: String,
        deliveredAtEpochMillis: Long,
        deliveryToken: String,
    ): ReceiptOutboxTransitionResult

    fun discardStaleConsent(
        deliveryId: String,
        discardedAtEpochMillis: Long,
    ): ReceiptOutboxTransitionResult
}

data class ReceiptDeliveryBounds(
    val maximumPendingEntries: Int = 64,
    val maximumJournalRecords: Int = 4_096,
    val maximumAttemptsPerEntry: Int = 6,
    val initialRetryDelayMillis: Long = 1_000L,
    val maximumRetryDelayMillis: Long = 60_000L,
) {
    init {
        require(maximumPendingEntries in 1..256)
        require(maximumJournalRecords in 1..65_536)
        require(maximumAttemptsPerEntry in 1..16)
        require(initialRetryDelayMillis in 250L..60_000L)
        require(maximumRetryDelayMillis in initialRetryDelayMillis..3_600_000L)
    }
}

/**
 * Encrypted append-only receipt-delivery journal.
 *
 * Every stage, failed attempt and terminal result is a separately fsynced AES-GCM record. Recovery
 * replays a strict state machine and fails closed on an unknown type, mutation, sequence error,
 * duplicate stage, terminal replay or policy overflow. The finite journal intentionally requires a
 * separately reviewed retention/compaction operation before its configured bound is reached.
 */
class EncryptedAppendOnlyReceiptDeliveryOutbox(
    private val store: EncryptedAppendOnlyRecordStore,
    private val bounds: ReceiptDeliveryBounds = ReceiptDeliveryBounds(),
) : ReceiptDeliveryOutbox {
    @Synchronized
    override fun recover(): ReceiptOutboxRecovery = load().toPublicRecovery()

    @Synchronized
    override fun stage(binding: ReceiptDeliveryBinding): ReceiptOutboxStageResult {
        val loaded = load()
        if (loaded is LoadedOutbox.Blocked) return ReceiptOutboxStageResult.Rejected(loaded.detailCode)
        loaded as LoadedOutbox.Ready
        loaded.entries[binding.deliveryId]?.let { existing ->
            if (!existing.binding.exactContentEquals(binding)) {
                return ReceiptOutboxStageResult.Rejected("receipt_delivery_id_conflict")
            }
            return if (existing.state == ReceiptDeliveryState.PENDING) {
                ReceiptOutboxStageResult.Staged(existing.safeCopy(), newlyAppended = false)
            } else {
                ReceiptOutboxStageResult.Terminal(existing.safeCopy())
            }
        }
        if (loaded.pendingCount >= bounds.maximumPendingEntries) {
            return ReceiptOutboxStageResult.Rejected("receipt_outbox_pending_bound")
        }
        val event = JournalEvent.Stage(binding.exactCopy())
        return when (val appended = append(loaded, event)) {
            is AppendEventResult.Ready -> {
                val entry = appended.loaded.entries[binding.deliveryId]
                    ?: return ReceiptOutboxStageResult.Rejected("receipt_outbox_stage_missing")
                ReceiptOutboxStageResult.Staged(entry.safeCopy(), newlyAppended = true)
            }
            is AppendEventResult.Rejected -> ReceiptOutboxStageResult.Rejected(appended.detailCode)
        }
    }

    @Synchronized
    override fun recordFailure(
        deliveryId: String,
        attemptedAtEpochMillis: Long,
        detailCode: String,
    ): ReceiptOutboxTransitionResult {
        if (!deliveryId.matches(ReceiptDeliveryBinding.SAFE_DELIVERY_ID) || attemptedAtEpochMillis < 0L ||
            !detailCode.matches(SAFE_DETAIL_CODE)
        ) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_failure_event_invalid")
        }
        val loaded = load()
        if (loaded is LoadedOutbox.Blocked) return ReceiptOutboxTransitionResult.Rejected(loaded.detailCode)
        loaded as LoadedOutbox.Ready
        val entry = loaded.entries[deliveryId]
            ?: return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_unknown")
        if (entry.state != ReceiptDeliveryState.PENDING) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_terminal")
        }
        val attempt = entry.attemptCount + 1
        val exhausted = attempt >= bounds.maximumAttemptsPerEntry
        val nextAttempt = if (exhausted) {
            Long.MAX_VALUE
        } else {
            saturatingAdd(attemptedAtEpochMillis, retryDelay(attempt))
        }
        val event = JournalEvent.Failed(deliveryId, attempt, attemptedAtEpochMillis, nextAttempt, detailCode)
        return transitionResult(deliveryId, append(loaded, event))
    }

    @Synchronized
    override fun recordDelivered(
        deliveryId: String,
        deliveredAtEpochMillis: Long,
        deliveryToken: String,
    ): ReceiptOutboxTransitionResult {
        if (!deliveryId.matches(ReceiptDeliveryBinding.SAFE_DELIVERY_ID) || deliveredAtEpochMillis < 0L ||
            !deliveryToken.matches(ReceiptDeliveryEntry.SAFE_DELIVERY_TOKEN)
        ) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_delivered_event_invalid")
        }
        val loaded = load()
        if (loaded is LoadedOutbox.Blocked) return ReceiptOutboxTransitionResult.Rejected(loaded.detailCode)
        loaded as LoadedOutbox.Ready
        val entry = loaded.entries[deliveryId]
            ?: return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_unknown")
        if (entry.state != ReceiptDeliveryState.PENDING) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_terminal")
        }
        val event = JournalEvent.Delivered(
            deliveryId = deliveryId,
            attempt = entry.attemptCount + 1,
            deliveredAtEpochMillis = deliveredAtEpochMillis,
            deliveryToken = deliveryToken,
        )
        return transitionResult(deliveryId, append(loaded, event))
    }

    @Synchronized
    override fun discardStaleConsent(
        deliveryId: String,
        discardedAtEpochMillis: Long,
    ): ReceiptOutboxTransitionResult {
        if (!deliveryId.matches(ReceiptDeliveryBinding.SAFE_DELIVERY_ID) || discardedAtEpochMillis < 0L) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_discard_event_invalid")
        }
        val loaded = load()
        if (loaded is LoadedOutbox.Blocked) return ReceiptOutboxTransitionResult.Rejected(loaded.detailCode)
        loaded as LoadedOutbox.Ready
        val entry = loaded.entries[deliveryId]
            ?: return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_unknown")
        if (entry.state != ReceiptDeliveryState.PENDING) {
            return ReceiptOutboxTransitionResult.Rejected("receipt_delivery_terminal")
        }
        return transitionResult(
            deliveryId,
            append(loaded, JournalEvent.StaleConsent(deliveryId, discardedAtEpochMillis)),
        )
    }

    private fun transitionResult(
        deliveryId: String,
        result: AppendEventResult,
    ): ReceiptOutboxTransitionResult = when (result) {
        is AppendEventResult.Ready -> {
            val entry = result.loaded.entries[deliveryId]
                ?: return ReceiptOutboxTransitionResult.Rejected("receipt_transition_missing")
            ReceiptOutboxTransitionResult.Updated(entry.safeCopy())
        }
        is AppendEventResult.Rejected -> ReceiptOutboxTransitionResult.Rejected(result.detailCode)
    }

    private fun append(loaded: LoadedOutbox.Ready, event: JournalEvent): AppendEventResult {
        if (loaded.recordCount >= bounds.maximumJournalRecords) {
            return AppendEventResult.Rejected("receipt_outbox_journal_bound")
        }
        val sequence = loaded.recordCount.toLong() + 1L
        val encoded = try {
            ReceiptDeliveryJournalCodec.encode(event)
        } catch (_: RuntimeException) {
            return AppendEventResult.Rejected("receipt_outbox_encode_failed")
        }
        val appendResult = try {
            store.append(
                LocalEncryptedRecord(
                    recordId = recordId(sequence),
                    sequence = sequence,
                    createdEpochMillis = event.eventTimeEpochMillis,
                    contentType = CONTENT_TYPE,
                    payload = encoded,
                ),
            )
        } catch (_: RuntimeException) {
            return AppendEventResult.Rejected("receipt_outbox_append_exception")
        }
        if (appendResult is StorageAppendResult.Quarantined) {
            return AppendEventResult.Rejected("receipt_outbox_append_rejected")
        }
        val refreshed = load()
        return if (refreshed is LoadedOutbox.Ready && refreshed.recordCount >= sequence) {
            AppendEventResult.Ready(refreshed)
        } else {
            AppendEventResult.Rejected(
                if (refreshed is LoadedOutbox.Blocked) refreshed.detailCode else "receipt_outbox_append_unknown",
            )
        }
    }

    private fun load(): LoadedOutbox {
        val report = try {
            store.recover()
        } catch (_: RuntimeException) {
            return LoadedOutbox.Blocked("receipt_outbox_recovery_exception")
        }
        if (!report.canAppend) return LoadedOutbox.Blocked("receipt_outbox_storage_quarantined")
        if (report.accepted.size > bounds.maximumJournalRecords) {
            return LoadedOutbox.Blocked("receipt_outbox_journal_bound")
        }
        val entries = linkedMapOf<String, ReceiptDeliveryEntry>()
        for (accepted in report.accepted) {
            val record = accepted.record
            if (record.contentType != CONTENT_TYPE || record.recordId != recordId(record.sequence)) {
                return LoadedOutbox.Blocked("receipt_outbox_record_metadata_invalid")
            }
            val event = try {
                ReceiptDeliveryJournalCodec.decode(record.payloadCopy())
            } catch (_: RuntimeException) {
                return LoadedOutbox.Blocked("receipt_outbox_event_invalid")
            }
            if (record.createdEpochMillis != event.eventTimeEpochMillis) {
                return LoadedOutbox.Blocked("receipt_outbox_event_time_mismatch")
            }
            when (event) {
                is JournalEvent.Stage -> {
                    if (entries.containsKey(event.binding.deliveryId)) {
                        return LoadedOutbox.Blocked("receipt_outbox_duplicate_stage")
                    }
                    entries[event.binding.deliveryId] = ReceiptDeliveryEntry(
                        binding = event.binding.exactCopy(),
                        state = ReceiptDeliveryState.PENDING,
                        attemptCount = 0,
                        nextAttemptAtEpochMillis = event.binding.createdAtEpochMillis,
                        terminalDetailCode = null,
                        deliveryToken = null,
                    )
                    if (entries.values.count { it.state == ReceiptDeliveryState.PENDING } > bounds.maximumPendingEntries) {
                        return LoadedOutbox.Blocked("receipt_outbox_pending_bound")
                    }
                }
                is JournalEvent.Failed -> {
                    val prior = entries[event.deliveryId]
                        ?: return LoadedOutbox.Blocked("receipt_outbox_orphan_transition")
                    if (prior.state != ReceiptDeliveryState.PENDING || event.attempt != prior.attemptCount + 1) {
                        return LoadedOutbox.Blocked("receipt_outbox_attempt_sequence_invalid")
                    }
                    val exhausted = event.attempt >= bounds.maximumAttemptsPerEntry
                    val expectedNext = if (exhausted) Long.MAX_VALUE else {
                        saturatingAdd(event.attemptedAtEpochMillis, retryDelay(event.attempt))
                    }
                    if (event.nextAttemptAtEpochMillis != expectedNext) {
                        return LoadedOutbox.Blocked("receipt_outbox_retry_schedule_invalid")
                    }
                    entries[event.deliveryId] = ReceiptDeliveryEntry(
                        binding = prior.binding.exactCopy(),
                        state = if (exhausted) ReceiptDeliveryState.RETRY_EXHAUSTED else ReceiptDeliveryState.PENDING,
                        attemptCount = event.attempt,
                        nextAttemptAtEpochMillis = event.nextAttemptAtEpochMillis,
                        terminalDetailCode = if (exhausted) "receipt_retry_exhausted" else null,
                        deliveryToken = null,
                    )
                }
                is JournalEvent.Delivered -> {
                    val prior = entries[event.deliveryId]
                        ?: return LoadedOutbox.Blocked("receipt_outbox_orphan_transition")
                    if (prior.state != ReceiptDeliveryState.PENDING || event.attempt != prior.attemptCount + 1) {
                        return LoadedOutbox.Blocked("receipt_outbox_attempt_sequence_invalid")
                    }
                    entries[event.deliveryId] = ReceiptDeliveryEntry(
                        binding = prior.binding.exactCopy(),
                        state = ReceiptDeliveryState.DELIVERED,
                        attemptCount = event.attempt,
                        nextAttemptAtEpochMillis = Long.MAX_VALUE,
                        terminalDetailCode = "receipt_delivered",
                        deliveryToken = event.deliveryToken,
                    )
                }
                is JournalEvent.StaleConsent -> {
                    val prior = entries[event.deliveryId]
                        ?: return LoadedOutbox.Blocked("receipt_outbox_orphan_transition")
                    if (prior.state != ReceiptDeliveryState.PENDING) {
                        return LoadedOutbox.Blocked("receipt_outbox_terminal_replay")
                    }
                    entries[event.deliveryId] = ReceiptDeliveryEntry(
                        binding = prior.binding.exactCopy(),
                        state = ReceiptDeliveryState.STALE_CONSENT,
                        attemptCount = prior.attemptCount,
                        nextAttemptAtEpochMillis = Long.MAX_VALUE,
                        terminalDetailCode = "receipt_consent_stale",
                        deliveryToken = null,
                    )
                }
            }
        }
        return LoadedOutbox.Ready(entries, report.accepted.size)
    }

    private fun LoadedOutbox.toPublicRecovery(): ReceiptOutboxRecovery = when (this) {
        is LoadedOutbox.Blocked -> ReceiptOutboxRecovery.RecoveryRequired(detailCode)
        is LoadedOutbox.Ready -> ReceiptOutboxRecovery.Ready(
            entries = entries.values.map { it.safeCopy() },
            journalRecordCount = recordCount,
        )
    }

    private fun retryDelay(attempt: Int): Long {
        var delay = bounds.initialRetryDelayMillis
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = (delay * 2L).coerceAtMost(bounds.maximumRetryDelayMillis)
        }
        return delay.coerceAtMost(bounds.maximumRetryDelayMillis)
    }

    private sealed interface LoadedOutbox {
        data class Ready(
            val entries: LinkedHashMap<String, ReceiptDeliveryEntry>,
            val recordCount: Int,
        ) : LoadedOutbox {
            val pendingCount: Int get() = entries.values.count { it.state == ReceiptDeliveryState.PENDING }
        }

        data class Blocked(val detailCode: String) : LoadedOutbox
    }

    private sealed interface AppendEventResult {
        data class Ready(val loaded: LoadedOutbox.Ready) : AppendEventResult
        data class Rejected(val detailCode: String) : AppendEventResult
    }

    private companion object {
        const val CONTENT_TYPE = "application/vnd.vitalsignal.receipt-delivery.v1"

        fun recordId(sequence: Long): String = "receipt-event-${sequence.toString().padStart(20, '0')}"
    }
}

/** At-least-once recovery engine. Android scheduling remains an injected outer composition seam. */
class CrashSafeReceiptDeliveryCoordinator(
    private val outbox: ReceiptDeliveryOutbox,
    private val consentLeaseProvider: BridgeConsentLeaseProvider,
    private val acknowledgementKeyResolver: BridgeAcknowledgementKeyResolver,
    private val receiptPublisher: DataLayerReceiptPublisher,
) {
    @Synchronized
    fun stageAndAttempt(
        expectedLease: ActiveBridgeConsentLease,
        acknowledgement: BatchAcknowledgement,
        nowEpochMillis: Long,
    ): PhoneBridgeProcessingResult {
        val binding = try {
            ReceiptDeliveryBinding.create(expectedLease, acknowledgement, nowEpochMillis)
        } catch (_: RuntimeException) {
            return PhoneBridgeProcessingResult.ReceiptDeliveryUnavailable(
                acknowledgement,
                "receipt_binding_invalid",
            )
        }
        return when (val staged = outbox.stage(binding)) {
            is ReceiptOutboxStageResult.Rejected -> PhoneBridgeProcessingResult.ReceiptDeliveryUnavailable(
                acknowledgement,
                staged.detailCode,
            )
            is ReceiptOutboxStageResult.Terminal -> terminalResult(staged.entry)
            is ReceiptOutboxStageResult.Staged -> attempt(staged.entry, nowEpochMillis)
        }
    }

    @Synchronized
    fun recoverAndRetry(
        nowEpochMillis: Long,
        maximumEntries: Int = DEFAULT_RECOVERY_BATCH_SIZE,
    ): ReceiptRecoveryRunResult {
        require(nowEpochMillis >= 0L)
        require(maximumEntries in 1..MAX_RECOVERY_BATCH_SIZE)
        return when (val recovered = outbox.recover()) {
            is ReceiptOutboxRecovery.RecoveryRequired -> ReceiptRecoveryRunResult.RecoveryRequired(
                recovered.detailCode,
            )
            is ReceiptOutboxRecovery.Ready -> {
                val allPending = recovered.pending
                val eligible = allPending
                    .filter { nowEpochMillis >= it.nextAttemptAtEpochMillis }
                    .sortedWith(compareBy<ReceiptDeliveryEntry> { it.binding.createdAtEpochMillis }
                        .thenBy { it.binding.deliveryId })
                    .take(maximumEntries)
                val results = eligible.map { attempt(it, nowEpochMillis) }
                val nextEligibleAt = when (val after = outbox.recover()) {
                    is ReceiptOutboxRecovery.RecoveryRequired -> return ReceiptRecoveryRunResult.RecoveryRequired(
                        after.detailCode,
                    )
                    is ReceiptOutboxRecovery.Ready -> after.pending.minOfOrNull {
                        it.nextAttemptAtEpochMillis
                    }
                }
                ReceiptRecoveryRunResult.Completed(
                    attemptedEntries = eligible.size,
                    remainingPendingBeforeRun = allPending.size,
                    nextEligibleAtEpochMillis = nextEligibleAt,
                    results = results,
                )
            }
        }
    }

    private fun attempt(entry: ReceiptDeliveryEntry, nowEpochMillis: Long): PhoneBridgeProcessingResult {
        val acknowledgement = entry.binding.acknowledgement()
        if (entry.state != ReceiptDeliveryState.PENDING) return terminalResult(entry)
        if (nowEpochMillis < entry.nextAttemptAtEpochMillis) {
            return PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                acknowledgement,
                command = null,
                detailCode = "receipt_retry_backoff",
            )
        }

        val lease = try {
            consentLeaseProvider.currentLease()
        } catch (_: RuntimeException) {
            return failed(entry, nowEpochMillis, "receipt_consent_lookup_failed", null)
        }
        if (!entry.binding.matches(lease)) {
            return when (val discarded = outbox.discardStaleConsent(entry.binding.deliveryId, nowEpochMillis)) {
                is ReceiptOutboxTransitionResult.Updated -> terminalResult(discarded.entry)
                is ReceiptOutboxTransitionResult.Rejected -> PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                    acknowledgement,
                    command = null,
                    detailCode = discarded.detailCode,
                )
            }
        }

        val key = try {
            acknowledgementKeyResolver.resolve(entry.binding.acknowledgementKeyId)
        } catch (_: RuntimeException) {
            null
        } ?: return failed(entry, nowEpochMillis, "ack_key_unavailable", null)

        val authenticatedBytes = try {
            AuthenticatedAcknowledgementCodec.encode(
                acknowledgement = acknowledgement,
                keyId = entry.binding.acknowledgementKeyId,
                authenticationKey = key,
            )
        } catch (_: RuntimeException) {
            return failed(entry, nowEpochMillis, "ack_authentication_failed", null)
        }
        val command = try {
            AuthenticatedReceiptCommand(
                targetNodeId = entry.binding.targetNodeId,
                path = entry.binding.path,
                consentGeneration = entry.binding.consentGeneration,
                authenticatedReceiptBytes = authenticatedBytes,
            )
        } catch (_: RuntimeException) {
            return failed(entry, nowEpochMillis, "receipt_command_invalid", null)
        }
        val publishResult = try {
            receiptPublisher.publish(command)
        } catch (_: RuntimeException) {
            ReceiptPublishResult.Failed("receipt_publisher_exception")
        }
        return when (publishResult) {
            is ReceiptPublishResult.Failed -> failed(entry, nowEpochMillis, publishResult.detailCode, command)
            is ReceiptPublishResult.Published -> when (
                val recorded = outbox.recordDelivered(
                    entry.binding.deliveryId,
                    nowEpochMillis,
                    publishResult.deliveryToken,
                )
            ) {
                is ReceiptOutboxTransitionResult.Updated -> terminalResult(recorded.entry)
                is ReceiptOutboxTransitionResult.Rejected -> PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                    acknowledgement,
                    command,
                    "receipt_delivery_state_unknown",
                )
            }
        }
    }

    private fun failed(
        entry: ReceiptDeliveryEntry,
        nowEpochMillis: Long,
        detailCode: String,
        command: AuthenticatedReceiptCommand?,
    ): PhoneBridgeProcessingResult {
        val safeDetail = detailCode.takeIf { it.matches(SAFE_DETAIL_CODE) } ?: "receipt_attempt_failed"
        return when (val recorded = outbox.recordFailure(entry.binding.deliveryId, nowEpochMillis, safeDetail)) {
            is ReceiptOutboxTransitionResult.Rejected -> PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                entry.binding.acknowledgement(),
                command,
                "receipt_failure_state_unknown",
            )
            is ReceiptOutboxTransitionResult.Updated -> if (
                recorded.entry.state == ReceiptDeliveryState.RETRY_EXHAUSTED
            ) {
                PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned(
                    recorded.entry.binding.acknowledgement(),
                    "receipt_retry_exhausted",
                )
            } else {
                PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                    recorded.entry.binding.acknowledgement(),
                    command,
                    safeDetail,
                )
            }
        }
    }

    private fun terminalResult(entry: ReceiptDeliveryEntry): PhoneBridgeProcessingResult {
        val acknowledgement = entry.binding.acknowledgement()
        return when (entry.state) {
            ReceiptDeliveryState.DELIVERED -> if (acknowledgement.disposition == ReceiptDisposition.ACK) {
                PhoneBridgeProcessingResult.AckPublished(acknowledgement, requireNotNull(entry.deliveryToken))
            } else {
                PhoneBridgeProcessingResult.NackPublished(acknowledgement, requireNotNull(entry.deliveryToken))
            }
            ReceiptDeliveryState.STALE_CONSENT -> PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned(
                acknowledgement,
                "receipt_consent_stale",
            )
            ReceiptDeliveryState.RETRY_EXHAUSTED -> PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned(
                acknowledgement,
                "receipt_retry_exhausted",
            )
            ReceiptDeliveryState.PENDING -> PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                acknowledgement,
                null,
                "receipt_delivery_pending",
            )
        }
    }

    private fun ReceiptDeliveryBinding.matches(lease: ActiveBridgeConsentLease?): Boolean = lease != null &&
        consentGeneration == lease.consentGeneration &&
        targetNodeId == lease.pairedWatchNodeId &&
        acknowledgementKeyId == lease.acknowledgementKeyId

    private companion object {
        const val DEFAULT_RECOVERY_BATCH_SIZE = 8
        const val MAX_RECOVERY_BATCH_SIZE = 32
    }
}

sealed interface ReceiptRecoveryRunResult {
    data class Completed(
        val attemptedEntries: Int,
        val remainingPendingBeforeRun: Int,
        val nextEligibleAtEpochMillis: Long?,
        val results: List<PhoneBridgeProcessingResult>,
    ) : ReceiptRecoveryRunResult {
        init {
            require(attemptedEntries >= 0)
            require(remainingPendingBeforeRun >= attemptedEntries)
            require(nextEligibleAtEpochMillis == null || nextEligibleAtEpochMillis >= 0L)
        }
    }

    data class RecoveryRequired(val detailCode: String) : ReceiptRecoveryRunResult
}

private sealed interface JournalEvent {
    val eventTimeEpochMillis: Long

    data class Stage(val binding: ReceiptDeliveryBinding) : JournalEvent {
        override val eventTimeEpochMillis: Long get() = binding.createdAtEpochMillis
    }

    data class Failed(
        val deliveryId: String,
        val attempt: Int,
        val attemptedAtEpochMillis: Long,
        val nextAttemptAtEpochMillis: Long,
        val detailCode: String,
    ) : JournalEvent {
        override val eventTimeEpochMillis: Long get() = attemptedAtEpochMillis
    }

    data class Delivered(
        val deliveryId: String,
        val attempt: Int,
        val deliveredAtEpochMillis: Long,
        val deliveryToken: String,
    ) : JournalEvent {
        override val eventTimeEpochMillis: Long get() = deliveredAtEpochMillis
    }

    data class StaleConsent(
        val deliveryId: String,
        val discardedAtEpochMillis: Long,
    ) : JournalEvent {
        override val eventTimeEpochMillis: Long get() = discardedAtEpochMillis
    }
}

private object ReceiptDeliveryJournalCodec {
    fun encode(event: JournalEvent): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            when (event) {
                is JournalEvent.Stage -> {
                    output.writeByte(TYPE_STAGE)
                    val binding = event.binding
                    output.writeBoundedAscii(binding.deliveryId, 96)
                    output.writeLong(binding.consentGeneration)
                    output.writeBoundedUtf8(binding.targetNodeId, ReceiptDeliveryBinding.MAX_NODE_ID_BYTES)
                    output.writeBoundedUtf8(binding.path, ReceiptDeliveryBinding.MAX_PATH_BYTES)
                    output.writeBoundedAscii(binding.acknowledgementKeyId, 96)
                    output.writeLong(binding.createdAtEpochMillis)
                    val acknowledgement = binding.acknowledgementWireCopy()
                    output.writeInt(acknowledgement.size)
                    output.write(acknowledgement)
                }
                is JournalEvent.Failed -> {
                    output.writeByte(TYPE_FAILED)
                    output.writeBoundedAscii(event.deliveryId, 96)
                    output.writeInt(event.attempt)
                    output.writeLong(event.attemptedAtEpochMillis)
                    output.writeLong(event.nextAttemptAtEpochMillis)
                    output.writeBoundedAscii(event.detailCode, 96)
                }
                is JournalEvent.Delivered -> {
                    output.writeByte(TYPE_DELIVERED)
                    output.writeBoundedAscii(event.deliveryId, 96)
                    output.writeInt(event.attempt)
                    output.writeLong(event.deliveredAtEpochMillis)
                    output.writeBoundedAscii(event.deliveryToken, 256)
                }
                is JournalEvent.StaleConsent -> {
                    output.writeByte(TYPE_STALE_CONSENT)
                    output.writeBoundedAscii(event.deliveryId, 96)
                    output.writeLong(event.discardedAtEpochMillis)
                }
            }
        }
        buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): JournalEvent {
        require(bytes.size in MIN_EVENT_BYTES..MAX_EVENT_BYTES)
        val cursor = ReceiptJournalCursor(bytes)
        require(cursor.readInt() == MAGIC)
        require(cursor.readInt() == VERSION)
        val result = when (cursor.readUnsignedByte()) {
            TYPE_STAGE -> {
                val deliveryId = cursor.readAscii(96)
                val generation = cursor.readLong()
                val nodeId = cursor.readUtf8(ReceiptDeliveryBinding.MAX_NODE_ID_BYTES)
                val path = cursor.readUtf8(ReceiptDeliveryBinding.MAX_PATH_BYTES)
                val keyId = cursor.readAscii(96)
                val createdAt = cursor.readLong()
                val acknowledgement = cursor.readBytes(cursor.readLength(BatchWireLimits.MAX_ACK_BYTES))
                JournalEvent.Stage(
                    ReceiptDeliveryBinding.restore(
                        deliveryId,
                        generation,
                        nodeId,
                        path,
                        keyId,
                        createdAt,
                        acknowledgement,
                    ),
                )
            }
            TYPE_FAILED -> JournalEvent.Failed(
                deliveryId = cursor.readAscii(96),
                attempt = cursor.readInt().also { require(it in 1..16) },
                attemptedAtEpochMillis = cursor.readLong().also { require(it >= 0L) },
                nextAttemptAtEpochMillis = cursor.readLong().also { require(it >= 0L) },
                detailCode = cursor.readAscii(96).also { require(it.matches(SAFE_DETAIL_CODE)) },
            )
            TYPE_DELIVERED -> JournalEvent.Delivered(
                deliveryId = cursor.readAscii(96),
                attempt = cursor.readInt().also { require(it in 1..16) },
                deliveredAtEpochMillis = cursor.readLong().also { require(it >= 0L) },
                deliveryToken = cursor.readAscii(256).also {
                    require(it.matches(ReceiptDeliveryEntry.SAFE_DELIVERY_TOKEN))
                },
            )
            TYPE_STALE_CONSENT -> JournalEvent.StaleConsent(
                deliveryId = cursor.readAscii(96),
                discardedAtEpochMillis = cursor.readLong().also { require(it >= 0L) },
            )
            else -> error("Unknown receipt journal event")
        }
        require(cursor.remaining == 0)
        return result
    }

    private const val MAGIC = 0x5653524a // VSRJ
    private const val VERSION = 1
    private const val TYPE_STAGE = 1
    private const val TYPE_FAILED = 2
    private const val TYPE_DELIVERED = 3
    private const val TYPE_STALE_CONSENT = 4
    private const val MIN_EVENT_BYTES = 16
    private const val MAX_EVENT_BYTES = BatchWireLimits.MAX_ACK_BYTES + 2_048
}

private class ReceiptJournalCursor(private val bytes: ByteArray) {
    var position: Int = 0
        private set
    val remaining: Int get() = bytes.size - position

    fun readInt(): Int {
        require(remaining >= Int.SIZE_BYTES)
        return ByteBuffer.wrap(bytes, position, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
            .also { position += Int.SIZE_BYTES }
    }

    fun readLong(): Long {
        require(remaining >= Long.SIZE_BYTES)
        return ByteBuffer.wrap(bytes, position, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long
            .also { position += Long.SIZE_BYTES }
    }

    fun readUnsignedByte(): Int {
        require(remaining >= 1)
        return bytes[position++].toInt() and 0xff
    }

    fun readLength(maximum: Int): Int = readInt().also { require(it in 1..maximum && it <= remaining) }

    fun readBytes(length: Int): ByteArray {
        require(length in 0..remaining)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun readAscii(maximum: Int): String = strictAscii(readBytes(readLength(maximum)))

    fun readUtf8(maximum: Int): String = strictUtf8(readBytes(readLength(maximum)))
}

private fun DataOutputStream.writeBoundedAscii(value: String, maximum: Int) {
    val bytes = value.toByteArray(StandardCharsets.US_ASCII)
    require(bytes.size in 1..maximum && String(bytes, StandardCharsets.US_ASCII) == value)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeBoundedUtf8(value: String, maximum: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size in 1..maximum)
    writeInt(bytes.size)
    write(bytes)
}

private fun strictAscii(bytes: ByteArray): String = StandardCharsets.US_ASCII.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
