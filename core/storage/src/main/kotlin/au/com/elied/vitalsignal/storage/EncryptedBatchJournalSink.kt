package au.com.elied.vitalsignal.storage

import au.com.elied.vitalsignal.transport.BatchAuthenticationResult
import au.com.elied.vitalsignal.transport.BatchCommitCandidate
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.BatchPayloadAuthenticator
import au.com.elied.vitalsignal.transport.BatchQuarantineRecord
import au.com.elied.vitalsignal.transport.DurableBatchSink
import au.com.elied.vitalsignal.transport.DurableCommitResult
import au.com.elied.vitalsignal.transport.QuarantineWriteResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Restart-safe phone receipt journal backed by two independently configurable encrypted stores.
 *
 * An ACK becomes possible only after [batchStore] has atomically committed the canonical wire
 * bytes. Recovery re-runs application-level authentication before recognising a durable receipt.
 * Delayed, non-overlapping batch ordinals are accepted; an already-used device/session/sequence is
 * never silently replaced.
 */
class EncryptedBatchJournalSink(
    private val batchStore: EncryptedAppendOnlyRecordStore,
    private val quarantineStore: EncryptedAppendOnlyRecordStore,
    private val payloadAuthenticator: BatchPayloadAuthenticator,
) : DurableBatchSink {
    @Synchronized
    override fun commit(candidate: BatchCommitCandidate): DurableCommitResult {
        val index = loadBatchIndex() ?: return DurableCommitResult.StoreFailure("journal_recovery_failed")
        index.byBatchId[candidate.envelope.batchId]?.let { existing ->
            return if (constantTimeEquals(existing.wireSha256Hex, candidate.wireSha256Hex)) {
                DurableCommitResult.AlreadyCommitted(
                    durableCommitToken = existing.durableCommitToken,
                    canonicalWireSha256Hex = existing.wireSha256Hex,
                )
            } else {
                DurableCommitResult.ConflictingBatchId("batch_id_reused")
            }
        }

        val ordinal = BatchOrdinal.from(candidate.envelope)
        if (index.byOrdinal.containsKey(ordinal)) {
            return DurableCommitResult.ConflictingBatchId("sequence_already_committed")
        }

        val expectedRecordId = batchRecordId(candidate.envelope.batchId)
        val append = try {
            batchStore.append(
                LocalEncryptedRecord(
                    recordId = expectedRecordId,
                    sequence = index.nextLocalSequence,
                    createdEpochMillis = candidate.envelope.createdAtEpochMillis,
                    contentType = BATCH_CONTENT_TYPE,
                    payload = candidate.canonicalWireBytesCopy(),
                ),
            )
        } catch (_: RuntimeException) {
            return DurableCommitResult.StoreFailure("journal_append_exception")
        }
        return when (append) {
            is StorageAppendResult.Accepted -> DurableCommitResult.Committed(
                commitToken(append.acceptedRecord.fileName, candidate.wireSha256Hex),
            )
            is StorageAppendResult.Duplicate -> {
                val refreshed = loadBatchIndex()
                    ?: return DurableCommitResult.StoreFailure("journal_duplicate_recovery_failed")
                val existing = refreshed.byBatchId[candidate.envelope.batchId]
                    ?: return DurableCommitResult.StoreFailure("journal_duplicate_missing")
                if (constantTimeEquals(existing.wireSha256Hex, candidate.wireSha256Hex)) {
                    DurableCommitResult.AlreadyCommitted(
                        existing.durableCommitToken,
                        existing.wireSha256Hex,
                    )
                } else {
                    DurableCommitResult.ConflictingBatchId("batch_id_reused")
                }
            }
            is StorageAppendResult.Quarantined -> when (append.reason) {
                AppendQuarantineReason.REPLAY_CONFLICT ->
                    DurableCommitResult.ConflictingBatchId("batch_id_reused")
                AppendQuarantineReason.OUT_OF_SEQUENCE,
                AppendQuarantineReason.PAYLOAD_TOO_LARGE,
                AppendQuarantineReason.RECOVERY_BLOCKED,
                -> DurableCommitResult.StoreFailure("journal_append_rejected")
            }
        }
    }

    @Synchronized
    override fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult {
        val report = quarantineStore.recover()
        if (!report.canAppend) return QuarantineWriteResult.Failed("quarantine_recovery_failed")
        val payload = encodeQuarantine(record)
        val append = try {
            quarantineStore.append(
                LocalEncryptedRecord(
                    recordId = quarantineRecordId(record.quarantineId),
                    sequence = (report.accepted.lastOrNull()?.record?.sequence ?: 0L) + 1L,
                    createdEpochMillis = record.receivedAtEpochMillis,
                    contentType = QUARANTINE_CONTENT_TYPE,
                    payload = payload,
                ),
            )
        } catch (_: RuntimeException) {
            return QuarantineWriteResult.Failed("quarantine_append_exception")
        }
        return when (append) {
            is StorageAppendResult.Accepted -> QuarantineWriteResult.Recorded(
                "quarantine-${append.acceptedRecord.fileName.take(96)}",
            )
            is StorageAppendResult.Duplicate -> QuarantineWriteResult.Recorded(
                "quarantine-${append.canonicalFileName.take(96)}",
            )
            is StorageAppendResult.Quarantined -> QuarantineWriteResult.Failed("quarantine_append_rejected")
        }
    }

    /** Fail-closed view for UI and interpretation gates. */
    @Synchronized
    fun status(): EncryptedBatchJournalStatus {
        val index = loadBatchIndex()
        val quarantine = quarantineStore.recover()
        return when {
            index == null || !quarantine.canAppend -> EncryptedBatchJournalStatus.RECOVERY_REQUIRED
            else -> EncryptedBatchJournalStatus.READY
        }
    }

    private fun loadBatchIndex(): BatchIndex? {
        val report = batchStore.recover()
        if (!report.canAppend) return null
        val byBatchId = linkedMapOf<String, DurableBatchEntry>()
        val byOrdinal = linkedMapOf<BatchOrdinal, DurableBatchEntry>()
        for (accepted in report.accepted) {
            if (accepted.record.contentType != BATCH_CONTENT_TYPE) return null
            val wire = accepted.record.payloadCopy()
            val envelope = try {
                BatchEnvelopeCodec.decode(wire)
            } catch (_: RuntimeException) {
                return null
            }
            if (accepted.record.recordId != batchRecordId(envelope.batchId)) return null
            val authentication = try {
                payloadAuthenticator.authenticate(envelope)
            } catch (_: RuntimeException) {
                return null
            }
            if (authentication !is BatchAuthenticationResult.Authenticated) return null
            val digest = sha256Hex(wire)
            val entry = DurableBatchEntry(
                envelope = envelope,
                wireSha256Hex = digest,
                durableCommitToken = commitToken(accepted.fileName, digest),
            )
            if (byBatchId.put(envelope.batchId, entry) != null) return null
            if (byOrdinal.put(BatchOrdinal.from(envelope), entry) != null) return null
        }
        return BatchIndex(
            byBatchId = byBatchId,
            byOrdinal = byOrdinal,
            nextLocalSequence = (report.accepted.lastOrNull()?.record?.sequence ?: 0L) + 1L,
        )
    }

    private fun encodeQuarantine(record: BatchQuarantineRecord): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeUTF("VitalSignal-quarantine-v1")
                output.writeUTF(record.reason.wireCode)
                output.writeNullableUtf(record.batchId)
                output.writeNullableUtf(record.sessionId)
                output.writeLong(record.sequence ?: -1L)
                output.writeUTF(record.wireSha256Hex)
                output.writeInt(record.wireSizeBytes)
                output.writeLong(record.receivedAtEpochMillis)
                output.writeUTF(record.detailCode)
            }
            buffer.toByteArray()
        }

    private data class BatchIndex(
        val byBatchId: Map<String, DurableBatchEntry>,
        val byOrdinal: Map<BatchOrdinal, DurableBatchEntry>,
        val nextLocalSequence: Long,
    )

    private data class DurableBatchEntry(
        val envelope: BatchEnvelope,
        val wireSha256Hex: String,
        val durableCommitToken: String,
    )

    private data class BatchOrdinal(
        val deviceId: String,
        val sessionId: String,
        val sequence: Long,
    ) {
        companion object {
            fun from(envelope: BatchEnvelope) = BatchOrdinal(
                envelope.deviceId,
                envelope.sessionId,
                envelope.sequence,
            )
        }
    }

    private companion object {
        const val BATCH_CONTENT_TYPE = "application/vnd.vitalsignal.received-batch.v1"
        const val QUARANTINE_CONTENT_TYPE = "application/vnd.vitalsignal.quarantine.v1"
    }
}

enum class EncryptedBatchJournalStatus { READY, RECOVERY_REQUIRED }

private fun DataOutputStream.writeNullableUtf(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun batchRecordId(batchId: String): String = "batch-${sha256Hex(batchId.toByteArray()).take(40)}"

private fun quarantineRecordId(quarantineId: String): String =
    "quarantine-${sha256Hex(quarantineId.toByteArray()).take(40)}"

private fun commitToken(fileName: String, digest: String): String =
    "commit-${fileName.removeSuffix(".vsr").takeLast(48)}-${digest.take(16)}"

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)
