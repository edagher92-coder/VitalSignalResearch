package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.storage.AppendQuarantineReason
import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.LocalEncryptedRecord
import au.com.elied.vitalsignal.storage.StorageAppendResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Encrypted, restart-safe adapter for latched human concern events. */
class EncryptedHumanConcernJournal(
    private val store: EncryptedAppendOnlyRecordStore,
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) : AppendOnlyHumanConcernJournal {
    init {
        require(maximumRecords in 1..HARD_MAXIMUM_RECORDS)
    }

    @Synchronized
    override fun recover(): HumanConcernJournalRecovery = when (val decoded = recoverDecoded()) {
        is DecodedRecovery.Ready -> HumanConcernJournalRecovery.Recovered(decoded.records)
        is DecodedRecovery.Unavailable -> HumanConcernJournalRecovery.Unavailable(decoded.reason)
    }

    @Synchronized
    override fun append(
        event: HumanConcernAuditEvent,
        expectedJournalRevision: Long,
    ): HumanConcernJournalAppendResult {
        val recovered = recoverDecoded()
        if (recovered is DecodedRecovery.Unavailable) {
            return HumanConcernJournalAppendResult.Unavailable(recovered.reason)
        }
        recovered as DecodedRecovery.Ready
        recovered.records.firstOrNull { it.event.eventId == event.eventId }?.let { existing ->
            return if (existing.event == event) {
                HumanConcernJournalAppendResult.ExactDuplicate(existing)
            } else {
                HumanConcernJournalAppendResult.Rejected("Concern event ID replay conflict")
            }
        }
        val actualRevision = recovered.records.lastOrNull()?.revision ?: 0L
        if (expectedJournalRevision != actualRevision) {
            return HumanConcernJournalAppendResult.RevisionConflict(actualRevision)
        }
        if (recovered.records.size >= maximumRecords) {
            return HumanConcernJournalAppendResult.Rejected("Concern journal capacity reached")
        }

        val localRecord = try {
            LocalEncryptedRecord(
                recordId = event.eventId,
                sequence = actualRevision + 1L,
                createdEpochMillis = event.occurredAtEpochMillis,
                contentType = CONTENT_TYPE,
                payload = HumanConcernBinaryCodec.encode(event),
            )
        } catch (_: RuntimeException) {
            return HumanConcernJournalAppendResult.Rejected("Concern event could not be encoded")
        }
        val appended = try {
            store.append(localRecord)
        } catch (_: RuntimeException) {
            return HumanConcernJournalAppendResult.Unavailable(
                "Encrypted concern append outcome is unknown",
            )
        }
        return when (appended) {
            is StorageAppendResult.Accepted -> HumanConcernJournalAppendResult.Appended(
                HumanConcernJournalRecord(appended.acceptedRecord.record.sequence, event),
            )
            is StorageAppendResult.Duplicate -> when (val refreshed = recoverDecoded()) {
                is DecodedRecovery.Unavailable ->
                    HumanConcernJournalAppendResult.Unavailable(refreshed.reason)
                is DecodedRecovery.Ready -> refreshed.records
                    .firstOrNull { it.event.eventId == event.eventId }
                    ?.let {
                        if (it.event == event) HumanConcernJournalAppendResult.ExactDuplicate(it)
                        else HumanConcernJournalAppendResult.Rejected("Concern replay conflict")
                    }
                    ?: HumanConcernJournalAppendResult.Unavailable(
                        "Committed concern duplicate could not be recovered",
                    )
            }
            is StorageAppendResult.Quarantined -> when (appended.reason) {
                AppendQuarantineReason.OUT_OF_SEQUENCE -> when (val refreshed = recoverDecoded()) {
                    is DecodedRecovery.Ready -> HumanConcernJournalAppendResult.RevisionConflict(
                        refreshed.records.lastOrNull()?.revision ?: 0L,
                    )
                    is DecodedRecovery.Unavailable ->
                        HumanConcernJournalAppendResult.Unavailable(refreshed.reason)
                }
                AppendQuarantineReason.REPLAY_CONFLICT ->
                    HumanConcernJournalAppendResult.Rejected("Concern replay conflict")
                AppendQuarantineReason.PAYLOAD_TOO_LARGE ->
                    HumanConcernJournalAppendResult.Rejected("Concern payload exceeds bound")
                AppendQuarantineReason.RECOVERY_BLOCKED ->
                    HumanConcernJournalAppendResult.Unavailable("Concern storage is quarantined")
            }
        }
    }

    private fun recoverDecoded(): DecodedRecovery {
        val report = try {
            store.recover()
        } catch (_: RuntimeException) {
            return DecodedRecovery.Unavailable("Encrypted concern storage recovery failed")
        }
        if (!report.canAppend) {
            return DecodedRecovery.Unavailable("Encrypted concern storage is quarantined")
        }
        if (report.accepted.size > maximumRecords) {
            return DecodedRecovery.Unavailable("Concern journal exceeds capacity")
        }
        val records = mutableListOf<HumanConcernJournalRecord>()
        report.accepted.forEachIndexed { index, accepted ->
            val record = accepted.record
            if (record.sequence != index + 1L || record.contentType != CONTENT_TYPE) {
                return DecodedRecovery.Unavailable("Concern journal metadata is invalid")
            }
            val event = try {
                HumanConcernBinaryCodec.decode(record.payloadCopy())
            } catch (_: RuntimeException) {
                return DecodedRecovery.Unavailable("Concern journal payload is invalid")
            }
            if (event.eventId != record.recordId || event.occurredAtEpochMillis != record.createdEpochMillis) {
                return DecodedRecovery.Unavailable("Concern journal record binding is invalid")
            }
            records += HumanConcernJournalRecord(record.sequence, event)
        }
        return DecodedRecovery.Ready(records)
    }

    private sealed interface DecodedRecovery {
        data class Ready(val records: List<HumanConcernJournalRecord>) : DecodedRecovery
        data class Unavailable(val reason: String) : DecodedRecovery
    }

    private companion object {
        const val CONTENT_TYPE = "application/vnd.vitalsignal.human-concern.v1"
        const val DEFAULT_MAXIMUM_RECORDS = 20_000
        const val HARD_MAXIMUM_RECORDS = 100_000
    }
}

internal object HumanConcernBinaryCodec {
    private const val MAGIC = 0x5653434E
    private const val VERSION = 1

    fun encode(event: HumanConcernAuditEvent): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(event.eventId)
            out.writeUTF(event.concernId)
            out.writeUTF(event.subjectPseudonym)
            out.writeUTF(event.sessionId)
            out.writeLong(event.consentGeneration)
            out.writeLong(event.expectedConcernVersion)
            out.writeInt(event.action.ordinal)
            out.writeUTF(event.actorPrincipalId)
            out.writeInt(event.actorRole.ordinal)
            out.writeLong(event.occurredAtEpochMillis)
            out.writeUTF(event.contextSnapshotSha256)
            out.writeUTF(event.authorityReceiptId)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): HumanConcernAuditEvent = DataInputStream(
        ByteArrayInputStream(bytes),
    ).use { input ->
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val event = HumanConcernAuditEvent(
            eventId = input.readUTF(),
            concernId = input.readUTF(),
            subjectPseudonym = input.readUTF(),
            sessionId = input.readUTF(),
            consentGeneration = input.readLong(),
            expectedConcernVersion = input.readLong(),
            action = enumValue<HumanConcernAction>(input.readInt()),
            actorPrincipalId = input.readUTF(),
            actorRole = enumValue<HumanConcernActorRole>(input.readInt()),
            occurredAtEpochMillis = input.readLong(),
            contextSnapshotSha256 = input.readUTF(),
            authorityReceiptId = input.readUTF(),
        )
        require(input.available() == 0) { "Trailing concern bytes are not allowed" }
        event
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid enum ordinal")
}
