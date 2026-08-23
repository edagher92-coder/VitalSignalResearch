package au.com.elied.vitalsignal.phone.data.integration

sealed interface HistorySourceChange {
    val key: SourceRecordKey
    val revision: SourceRevision
    val participantPseudonym: String
    val consentGeneration: Long
    val validationReceiptId: String

    data class Upsert(val record: CanonicalHistoryRecord) : HistorySourceChange {
        override val key: SourceRecordKey get() = record.key
        override val revision: SourceRevision get() = record.provenance.revision
        override val participantPseudonym: String get() = record.participantPseudonym
        override val consentGeneration: Long get() = record.provenance.consentGeneration
        override val validationReceiptId: String get() = record.provenance.validationReceiptId
    }

    /**
     * Exact source tombstone. Deletion never guesses by time, concept, or value;
     * adapters must provide the native source key and a monotonic revision.
     */
    data class Delete(
        override val key: SourceRecordKey,
        override val revision: SourceRevision,
        override val participantPseudonym: String,
        val sourceDeletedAtEpochMillis: Long,
        val retrievedAtEpochMillis: Long,
        val adapterVersion: String,
        override val consentGeneration: Long,
        val pilotProtocolId: String,
        override val validationReceiptId: String,
        val sourceChangeCursorDigest: String,
    ) : HistorySourceChange {
        init {
            require(sourceDeletedAtEpochMillis > 0L)
            require(retrievedAtEpochMillis >= sourceDeletedAtEpochMillis)
            require(adapterVersion.isNotBlank())
            require(participantPseudonym.isNotBlank())
            require(consentGeneration > 0L)
            require(pilotProtocolId.isNotBlank())
            require(validationReceiptId.isNotBlank())
            require(Regex("^[0-9a-f]{64}$").matches(sourceChangeCursorDigest))
        }
    }
}

data class HistoryTombstone(
    val key: SourceRecordKey,
    val revision: SourceRevision,
    val participantPseudonym: String,
    val sourceDeletedAtEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val adapterVersion: String,
    val consentGeneration: Long,
    val pilotProtocolId: String,
    val validationReceiptId: String,
    val sourceChangeCursorDigest: String,
)

class HistoryMergeState(
    records: Map<SourceRecordKey, CanonicalHistoryRecord> = emptyMap(),
    tombstones: Map<SourceRecordKey, HistoryTombstone> = emptyMap(),
) {
    val records: Map<SourceRecordKey, CanonicalHistoryRecord> = java.util.Map.copyOf(records)
    val tombstones: Map<SourceRecordKey, HistoryTombstone> = java.util.Map.copyOf(tombstones)

    init {
        require(this.records.all { (key, record) -> key == record.key })
        require(this.tombstones.all { (key, tombstone) -> key == tombstone.key })
        require(this.records.keys.intersect(this.tombstones.keys).isEmpty())
    }

    fun copy(
        records: Map<SourceRecordKey, CanonicalHistoryRecord> = this.records,
        tombstones: Map<SourceRecordKey, HistoryTombstone> = this.tombstones,
    ) = HistoryMergeState(records, tombstones)

    override fun equals(other: Any?): Boolean = other is HistoryMergeState &&
        records == other.records && tombstones == other.tombstones

    override fun hashCode(): Int = 31 * records.hashCode() + tombstones.hashCode()
}

enum class HistoryMergeAction {
    INSERTED,
    UPDATED,
    DELETED,
    DUPLICATE_IGNORED,
    STALE_IGNORED,
    CONFLICT_REJECTED,
}

data class HistoryMergeResult(
    val key: SourceRecordKey,
    val action: HistoryMergeAction,
    val detail: String,
)

data class HistoryMergeBatchResult(
    val state: HistoryMergeState,
    val results: List<HistoryMergeResult>,
)

/**
 * Deterministic, fail-closed change application for Samsung Health and Health
 * Connect streams. Equal revisions with different payload hashes are rejected
 * as conflicts instead of silently overwriting history.
 */
object HistoryReconciler {
    fun apply(
        initial: HistoryMergeState,
        changes: List<HistorySourceChange>,
    ): HistoryMergeBatchResult {
        val records = initial.records.toMutableMap()
        val tombstones = initial.tombstones.toMutableMap()
        val results = changes.map { change ->
            retainedGovernanceMismatch(change, records, tombstones)
                ?: when (change) {
                    is HistorySourceChange.Upsert -> applyUpsert(change, records, tombstones)
                    is HistorySourceChange.Delete -> applyDelete(change, records, tombstones)
                }
        }
        return HistoryMergeBatchResult(
            state = HistoryMergeState(records.toMap(), tombstones.toMap()),
            results = results,
        )
    }

    private fun retainedGovernanceMismatch(
        change: HistorySourceChange,
        records: Map<SourceRecordKey, CanonicalHistoryRecord>,
        tombstones: Map<SourceRecordKey, HistoryTombstone>,
    ): HistoryMergeResult? {
        val key = change.key
        val current = records[key]
        val tombstone = tombstones[key]
        val boundPseudonym = current?.participantPseudonym ?: tombstone?.participantPseudonym
        val boundGeneration = current?.provenance?.consentGeneration ?: tombstone?.consentGeneration
        if (boundPseudonym != null && boundPseudonym != change.participantPseudonym) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Source key is already bound to a different participant pseudonym",
            )
        }
        if (boundGeneration != null && change.consentGeneration < boundGeneration) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Change arrives under a superseded consent generation",
            )
        }
        return null
    }

    private fun applyUpsert(
        change: HistorySourceChange.Upsert,
        records: MutableMap<SourceRecordKey, CanonicalHistoryRecord>,
        tombstones: MutableMap<SourceRecordKey, HistoryTombstone>,
    ): HistoryMergeResult {
        val key = change.key
        val incoming = change.record
        val tombstone = tombstones[key]
        if (tombstone != null &&
            incoming.provenance.revision.conflictsAtSameSequence(tombstone.revision)
        ) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Equal normalized sequence has a different native source version",
            )
        }
        if (tombstone != null && incoming.provenance.revision == tombstone.revision) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Upsert claims the exact revision of the source tombstone",
            )
        }
        if (tombstone != null && incoming.provenance.revision <= tombstone.revision) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.STALE_IGNORED,
                "Upsert is not newer than the exact source tombstone",
            )
        }
        if (tombstone != null &&
            incoming.provenance.sourceUpdatedAtEpochMillis < tombstone.sourceDeletedAtEpochMillis
        ) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Resurrecting upsert predates the exact source deletion time",
            )
        }

        val current = records[key]
        if (current == null) {
            tombstones.remove(key)
            records[key] = incoming
            return HistoryMergeResult(key, HistoryMergeAction.INSERTED, "New exact source record")
        }

        val comparison = incoming.provenance.revision.compareTo(current.provenance.revision)
        return when {
            comparison < 0 -> HistoryMergeResult(
                key,
                HistoryMergeAction.STALE_IGNORED,
                "Source revision is older than the retained record",
            )
            comparison == 0 && incoming.provenance.revision.conflictsAtSameSequence(current.provenance.revision) ->
                HistoryMergeResult(
                    key,
                    HistoryMergeAction.CONFLICT_REJECTED,
                    "Equal normalized sequence has a different native source version",
                )
            comparison == 0 &&
                incoming.provenance.payloadSha256 == current.provenance.payloadSha256 ->
                HistoryMergeResult(
                    key,
                    HistoryMergeAction.DUPLICATE_IGNORED,
                    "Same source key, revision, and source-payload digest",
                )
            comparison == 0 -> HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Equal source revision has a different payload digest",
            )
            else -> {
                if (incoming.provenance.revision.opaqueVersion ==
                    current.provenance.revision.opaqueVersion &&
                    incoming.provenance.payloadSha256 != current.provenance.payloadSha256
                ) {
                    HistoryMergeResult(
                        key,
                        HistoryMergeAction.CONFLICT_REJECTED,
                        "Same native source version carries a different payload digest",
                    )
                } else {
                    records[key] = incoming
                    HistoryMergeResult(key, HistoryMergeAction.UPDATED, "Newer source revision retained")
                }
            }
        }
    }

    private fun applyDelete(
        change: HistorySourceChange.Delete,
        records: MutableMap<SourceRecordKey, CanonicalHistoryRecord>,
        tombstones: MutableMap<SourceRecordKey, HistoryTombstone>,
    ): HistoryMergeResult {
        val key = change.key
        val current = records[key]
        val existingTombstone = tombstones[key]
        val newestRevision = listOfNotNull(
            current?.provenance?.revision,
            existingTombstone?.revision,
        ).maxOrNull()

        if (current != null &&
            current.provenance.revision.sequence == change.revision.sequence
        ) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Equal delete sequence has a different native source version",
            )
        }
        if (newestRevision != null && change.revision < newestRevision) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.STALE_IGNORED,
                "Delete revision is older than retained source state",
            )
        }
        if (existingTombstone?.revision == change.revision) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.DUPLICATE_IGNORED,
                "Exact source tombstone already retained",
            )
        }
        if (existingTombstone != null &&
            existingTombstone.revision.sequence == change.revision.sequence &&
            existingTombstone.revision != change.revision
        ) {
            return HistoryMergeResult(
                key,
                HistoryMergeAction.CONFLICT_REJECTED,
                "Equal delete sequence has a different native source version",
            )
        }

        records.remove(key)
        tombstones[key] = HistoryTombstone(
            key = key,
            revision = change.revision,
            participantPseudonym = change.participantPseudonym,
            sourceDeletedAtEpochMillis = change.sourceDeletedAtEpochMillis,
            retrievedAtEpochMillis = change.retrievedAtEpochMillis,
            adapterVersion = change.adapterVersion,
            consentGeneration = change.consentGeneration,
            pilotProtocolId = change.pilotProtocolId,
            validationReceiptId = change.validationReceiptId,
            sourceChangeCursorDigest = change.sourceChangeCursorDigest,
        )
        return HistoryMergeResult(key, HistoryMergeAction.DELETED, "Exact source tombstone retained")
    }
}
