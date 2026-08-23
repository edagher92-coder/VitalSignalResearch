package au.com.elied.vitalsignal.wear.continuity

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.LocalEncryptedRecord
import au.com.elied.vitalsignal.storage.StorageAppendResult

sealed interface WatchContinuityRecoveryResult {
    data class Available(val latest: WatchContinuitySnapshot?) : WatchContinuityRecoveryResult
    data class Unavailable(val code: String) : WatchContinuityRecoveryResult
}

sealed interface WatchContinuityJournalAppendResult {
    data class Committed(val snapshot: WatchContinuitySnapshot) : WatchContinuityJournalAppendResult
    data class Duplicate(val snapshot: WatchContinuitySnapshot) : WatchContinuityJournalAppendResult
    data class Rejected(val code: String) : WatchContinuityJournalAppendResult
}

/**
 * Encrypted append-only checkpoint journal for one continuity stream. A new consent/stream uses a
 * new journal root. Recovery verifies the generic
 * storage chain and an application-level predecessor hash, identity, sequence and gap transition.
 * It is deliberately bounded: retention/compaction must be reviewed before the configured limit.
 */
class EncryptedWatchContinuityJournal(
    private val store: EncryptedAppendOnlyRecordStore,
    private val maximumSnapshots: Int = 8_192,
) {
    init {
        require(maximumSnapshots in 16..100_000)
    }

    @Synchronized
    fun recover(): WatchContinuityRecoveryResult {
        val report = try {
            store.recover()
        } catch (_: Throwable) {
            return WatchContinuityRecoveryResult.Unavailable("continuity_store_recovery_failed")
        }
        if (!report.canAppend) {
            return WatchContinuityRecoveryResult.Unavailable("continuity_store_quarantined")
        }
        if (report.accepted.size > maximumSnapshots) {
            return WatchContinuityRecoveryResult.Unavailable("continuity_journal_limit_exceeded")
        }

        var previous: WatchContinuitySnapshot? = null
        for ((index, accepted) in report.accepted.withIndex()) {
            if (accepted.record.contentType != CONTENT_TYPE) {
                return WatchContinuityRecoveryResult.Unavailable("continuity_content_type_mismatch")
            }
            val snapshot = try {
                WatchContinuitySnapshotCodec.decode(accepted.record.payloadCopy())
            } catch (_: Throwable) {
                return WatchContinuityRecoveryResult.Unavailable("continuity_payload_invalid")
            }
            val expectedRevision = index.toLong() + 1L
            if (accepted.record.sequence != expectedRevision ||
                snapshot.revision != expectedRevision ||
                accepted.record.recordId != recordId(expectedRevision) ||
                accepted.record.createdEpochMillis != snapshot.observedAtEpochMillis ||
                !validSuccessor(previous, snapshot)
            ) {
                return WatchContinuityRecoveryResult.Unavailable("continuity_chain_invalid")
            }
            previous = snapshot
        }
        return WatchContinuityRecoveryResult.Available(previous)
    }

    @Synchronized
    fun append(snapshot: WatchContinuitySnapshot): WatchContinuityJournalAppendResult {
        val recovered = recover()
        if (recovered !is WatchContinuityRecoveryResult.Available) {
            return WatchContinuityJournalAppendResult.Rejected(
                (recovered as WatchContinuityRecoveryResult.Unavailable).code,
            )
        }
        val previous = recovered.latest
        if (previous?.revision == snapshot.revision && previous.sha256() == snapshot.sha256()) {
            return WatchContinuityJournalAppendResult.Duplicate(snapshot)
        }
        if (snapshot.revision > maximumSnapshots ||
            (previous?.revision ?: 0L) >= maximumSnapshots
        ) return WatchContinuityJournalAppendResult.Rejected("continuity_journal_limit_reached")
        if (!validSuccessor(previous, snapshot)) {
            return WatchContinuityJournalAppendResult.Rejected("continuity_successor_invalid")
        }
        val record = LocalEncryptedRecord(
            recordId = recordId(snapshot.revision),
            sequence = snapshot.revision,
            createdEpochMillis = snapshot.observedAtEpochMillis,
            contentType = CONTENT_TYPE,
            payload = WatchContinuitySnapshotCodec.encode(snapshot),
        )
        return when (store.append(record)) {
            is StorageAppendResult.Accepted -> WatchContinuityJournalAppendResult.Committed(snapshot)
            is StorageAppendResult.Duplicate -> WatchContinuityJournalAppendResult.Duplicate(snapshot)
            is StorageAppendResult.Quarantined ->
                WatchContinuityJournalAppendResult.Rejected("continuity_store_append_rejected")
        }
    }

    private fun validSuccessor(
        previous: WatchContinuitySnapshot?,
        candidate: WatchContinuitySnapshot,
    ): Boolean {
        if (previous == null) {
            return candidate.revision == 1L &&
                candidate.previousSnapshotSha256 == ZERO_SHA_256 &&
                candidate.lastCommittedSequence == 0L &&
                candidate.provenanceChainSha256 == ZERO_SHA_256 &&
                candidate.state != ContinuityState.RESUME_PENDING &&
                (candidate.state == ContinuityState.COLLECTING || candidate.latestGap?.active == true)
        }
        if (previous.state == ContinuityState.CONSENT_CLOSED) return false
        if (candidate.revision != previous.revision + 1L ||
            candidate.previousSnapshotSha256 != previous.sha256() ||
            candidate.streamId != previous.streamId ||
            candidate.deviceAlias != previous.deviceAlias ||
            candidate.firmwareGeneration != previous.firmwareGeneration ||
            candidate.consentGeneration != previous.consentGeneration ||
            candidate.lastCommittedSequence !in previous.lastCommittedSequence..(previous.lastCommittedSequence + 1L)
        ) return false

        val sequenceAdvanced = candidate.lastCommittedSequence == previous.lastCommittedSequence + 1L
        if (sequenceAdvanced) {
            if (candidate.state != ContinuityState.COLLECTING ||
                candidate.provenanceChainSha256 == previous.provenanceChainSha256 ||
                candidate.lastMeasurementEpochMillis == null ||
                candidate.lastMeasurementEpochMillis < (previous.lastMeasurementEpochMillis ?: 0L)
            ) return false
        } else if (candidate.provenanceChainSha256 != previous.provenanceChainSha256 ||
            candidate.lastMeasurementEpochMillis != previous.lastMeasurementEpochMillis
        ) return false

        if (candidate.bootSessionId != previous.bootSessionId) {
            val rebootRecorded = candidate.latestGap?.reasons?.contains(
                CollectionInterruptionReason.REBOOT,
            ) == true
            if (!rebootRecorded || candidate.state == ContinuityState.COLLECTING) return false
        }
        if (candidate.state == ContinuityState.COLLECTING && previous.state != ContinuityState.COLLECTING) {
            if (previous.state != ContinuityState.RESUME_PENDING ||
                candidate.latestGap?.endedAtEpochMillis == null
            ) return false
        }
        if (candidate.state == ContinuityState.RESUME_PENDING && candidate.latestGap?.active != true) {
            return false
        }
        if (candidate.state in setOf(
                ContinuityState.PAUSED,
                ContinuityState.RECOVERY_REQUIRED,
                ContinuityState.CONSENT_CLOSED,
            ) && candidate.latestGap?.active != true
        ) return false
        return true
    }

    private fun recordId(revision: Long): String = "continuity-${revision.toString().padStart(19, '0')}"

    private companion object {
        const val CONTENT_TYPE = "application/vnd.vitalsignal.watch-continuity.v1"
    }
}

sealed interface DurableWatchContinuityResult {
    /** The decision is actionable only because its exact snapshot is already durable. */
    data class Applied(val decision: WatchContinuityDecision) : DurableWatchContinuityResult
    data class MeasurementCommitted(val snapshot: WatchContinuitySnapshot) : DurableWatchContinuityResult
    data class Rejected(val code: String) : DurableWatchContinuityResult
    data class Unavailable(val code: String) : DurableWatchContinuityResult
}

/** Audit-before-action coordinator. A resume permit is never returned ahead of its checkpoint. */
class DurableWatchContinuityCoordinator(
    private val engine: WatchCollectionContinuityEngine,
    private val journal: EncryptedWatchContinuityJournal,
) {
    @Synchronized
    fun start(
        streamId: String,
        signal: WatchRuntimeSignal,
    ): DurableWatchContinuityResult {
        val recovered = journal.recover()
        if (recovered is WatchContinuityRecoveryResult.Unavailable) {
            return DurableWatchContinuityResult.Unavailable(recovered.code)
        }
        recovered as WatchContinuityRecoveryResult.Available
        if (recovered.latest != null) {
            return DurableWatchContinuityResult.Rejected("continuity_already_started")
        }
        return persist(engine.start(streamId, signal))
    }

    @Synchronized
    fun observe(
        signal: WatchRuntimeSignal,
        recoveryEvidence: WatchRecoveryEvidence? = null,
    ): DurableWatchContinuityResult = withCurrent { current ->
        persist(engine.observe(current, signal, recoveryEvidence))
    }

    @Synchronized
    fun confirmResume(
        permit: WatchResumePermit,
        signal: WatchRuntimeSignal,
        recoveryEvidence: WatchRecoveryEvidence? = null,
    ): DurableWatchContinuityResult = withCurrent { current ->
        persist(engine.confirmResume(current, permit, signal, recoveryEvidence))
    }

    @Synchronized
    fun commit(commit: WatchCollectionCommit): DurableWatchContinuityResult = withCurrent { current ->
        when (val decision = engine.commit(current, commit)) {
            is WatchCommitDecision.Rejected -> DurableWatchContinuityResult.Rejected(decision.code)
            is WatchCommitDecision.Committed -> when (val append = journal.append(decision.snapshot)) {
                is WatchContinuityJournalAppendResult.Committed,
                is WatchContinuityJournalAppendResult.Duplicate,
                -> DurableWatchContinuityResult.MeasurementCommitted(decision.snapshot)
                is WatchContinuityJournalAppendResult.Rejected ->
                    DurableWatchContinuityResult.Unavailable(append.code)
            }
        }
    }

    private fun withCurrent(
        action: (WatchContinuitySnapshot) -> DurableWatchContinuityResult,
    ): DurableWatchContinuityResult = when (val recovered = journal.recover()) {
        is WatchContinuityRecoveryResult.Unavailable ->
            DurableWatchContinuityResult.Unavailable(recovered.code)
        is WatchContinuityRecoveryResult.Available -> recovered.latest?.let(action)
            ?: DurableWatchContinuityResult.Rejected("continuity_not_started")
    }

    private fun persist(decision: WatchContinuityDecision): DurableWatchContinuityResult {
        if (decision is WatchContinuityDecision.Rejected) {
            return DurableWatchContinuityResult.Rejected(decision.code)
        }
        val snapshot = when (decision) {
            is WatchContinuityDecision.Continuing -> decision.snapshot
            is WatchContinuityDecision.Paused -> decision.snapshot
            is WatchContinuityDecision.ResumeReady -> decision.snapshot
            is WatchContinuityDecision.RecoveryRequired -> decision.snapshot
            is WatchContinuityDecision.ConsentClosed -> decision.snapshot
            is WatchContinuityDecision.Rejected -> error("handled above")
        }
        return when (val appended = journal.append(snapshot)) {
            is WatchContinuityJournalAppendResult.Committed,
            is WatchContinuityJournalAppendResult.Duplicate,
            -> DurableWatchContinuityResult.Applied(decision)
            is WatchContinuityJournalAppendResult.Rejected ->
                DurableWatchContinuityResult.Unavailable(appended.code)
        }
    }
}
