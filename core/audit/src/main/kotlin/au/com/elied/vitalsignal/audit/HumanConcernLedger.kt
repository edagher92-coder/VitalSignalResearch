package au.com.elied.vitalsignal.audit

enum class HumanConcernAction { REPORT, RESOLVE_BY_HUMAN }
enum class HumanConcernActorRole { PARTICIPANT, AUTHORIZED_CLINICIAN }
enum class HumanConcernLatchState { ACTIVE, RESOLVED }

data class HumanConcernAuditEvent(
    val eventId: String,
    val concernId: String,
    val subjectPseudonym: String,
    val sessionId: String,
    val consentGeneration: Long,
    val expectedConcernVersion: Long,
    val action: HumanConcernAction,
    val actorPrincipalId: String,
    val actorRole: HumanConcernActorRole,
    val occurredAtEpochMillis: Long,
    val contextSnapshotSha256: String,
    val authorityReceiptId: String,
) {
    init {
        requireSafeIdentifier(eventId, "eventId")
        requireSafeIdentifier(concernId, "concernId")
        requireSafeIdentifier(subjectPseudonym, "subjectPseudonym")
        requireSafeIdentifier(sessionId, "sessionId")
        require(consentGeneration > 0L)
        require(expectedConcernVersion >= 0L)
        requireSafeIdentifier(actorPrincipalId, "actorPrincipalId")
        require(occurredAtEpochMillis > 0L)
        requireCanonicalSha256(contextSnapshotSha256, "contextSnapshotSha256")
        requireSafeIdentifier(authorityReceiptId, "authorityReceiptId")
    }
}

fun interface HumanConcernAuthorityVerifier {
    /** Verifies authority over the exact immutable event, not merely its receipt ID. */
    fun verify(event: HumanConcernAuditEvent): Boolean
}

data class HumanConcernJournalRecord(
    val revision: Long,
    val event: HumanConcernAuditEvent,
) {
    init {
        require(revision > 0L)
    }
}

sealed interface HumanConcernJournalRecovery {
    data class Recovered(val records: List<HumanConcernJournalRecord>) : HumanConcernJournalRecovery
    data class Unavailable(val reason: String) : HumanConcernJournalRecovery
}

sealed interface HumanConcernJournalAppendResult {
    data class Appended(val record: HumanConcernJournalRecord) : HumanConcernJournalAppendResult
    data class ExactDuplicate(val record: HumanConcernJournalRecord) : HumanConcernJournalAppendResult
    data class RevisionConflict(val actualRevision: Long) : HumanConcernJournalAppendResult
    data class Rejected(val reason: String) : HumanConcernJournalAppendResult
    data class Unavailable(val reason: String) : HumanConcernJournalAppendResult
}

interface AppendOnlyHumanConcernJournal {
    fun recover(): HumanConcernJournalRecovery
    fun append(
        event: HumanConcernAuditEvent,
        expectedJournalRevision: Long,
    ): HumanConcernJournalAppendResult
}

class InMemoryHumanConcernJournal : AppendOnlyHumanConcernJournal {
    private val records = mutableListOf<HumanConcernJournalRecord>()

    @Synchronized
    override fun recover(): HumanConcernJournalRecovery = HumanConcernJournalRecovery.Recovered(
        records.toList(),
    )

    @Synchronized
    override fun append(
        event: HumanConcernAuditEvent,
        expectedJournalRevision: Long,
    ): HumanConcernJournalAppendResult {
        records.firstOrNull { it.event.eventId == event.eventId }?.let { existing ->
            return if (existing.event == event) {
                HumanConcernJournalAppendResult.ExactDuplicate(existing)
            } else {
                HumanConcernJournalAppendResult.Rejected("Concern event ID replay conflict")
            }
        }
        if (expectedJournalRevision != records.size.toLong()) {
            return HumanConcernJournalAppendResult.RevisionConflict(records.size.toLong())
        }
        val record = HumanConcernJournalRecord(records.size + 1L, event)
        records += record
        return HumanConcernJournalAppendResult.Appended(record)
    }
}

data class HumanConcernProjection(
    val concernId: String,
    val subjectPseudonym: String,
    val sessionId: String,
    val consentGeneration: Long,
    val state: HumanConcernLatchState,
    val version: Long,
    val lastEventId: String,
    val lastChangedAtEpochMillis: Long,
)

sealed interface HumanConcernQueryResult {
    data class Active(val projection: HumanConcernProjection) : HumanConcernQueryResult
    data object None : HumanConcernQueryResult
    data class Unavailable(val reason: String) : HumanConcernQueryResult
}

sealed interface HumanConcernMutationResult {
    data class Applied(val projection: HumanConcernProjection) : HumanConcernMutationResult
    data class Idempotent(val projection: HumanConcernProjection) : HumanConcernMutationResult
    data class Rejected(val reason: String) : HumanConcernMutationResult
    data class Unavailable(val reason: String) : HumanConcernMutationResult
}

/**
 * Human concern is a latched, audited state. Sensors and models have no action
 * type and therefore cannot clear it; only an exact human-authorized resolution
 * event can advance ACTIVE to RESOLVED.
 */
class HumanConcernLedger(
    private val journal: AppendOnlyHumanConcernJournal,
    private val authorityVerifier: HumanConcernAuthorityVerifier,
) {
    private val lock = Any()
    private val projections = linkedMapOf<String, HumanConcernProjection>()
    private val eventsById = linkedMapOf<String, HumanConcernAuditEvent>()
    private var journalRevision = 0L
    private var unavailableReason: String? = null

    init {
        synchronized(lock) { recoverLocked() }
    }

    fun mutate(event: HumanConcernAuditEvent): HumanConcernMutationResult = synchronized(lock) {
        unavailableReason?.let { return@synchronized HumanConcernMutationResult.Unavailable(it) }
        val authorized = try {
            authorityVerifier.verify(event)
        } catch (_: RuntimeException) {
            false
        }
        if (!authorized) {
            return@synchronized HumanConcernMutationResult.Rejected(
                "Exact human concern action authority is required",
            )
        }
        eventsById[event.eventId]?.let { existing ->
            return@synchronized if (existing == event) {
                HumanConcernMutationResult.Idempotent(projections.getValue(event.concernId))
            } else {
                HumanConcernMutationResult.Rejected("Concern event ID replay conflict")
            }
        }
        validationError(event)?.let {
            return@synchronized HumanConcernMutationResult.Rejected(it)
        }
        when (val appended = journal.append(event, journalRevision)) {
            is HumanConcernJournalAppendResult.Appended -> {
                if (appended.record.revision != journalRevision + 1L || appended.record.event != event) {
                    markUnavailable("Concern journal returned an inconsistent append receipt")
                    HumanConcernMutationResult.Unavailable(unavailableReason!!)
                } else {
                    apply(event)
                    journalRevision = appended.record.revision
                    HumanConcernMutationResult.Applied(projections.getValue(event.concernId))
                }
            }
            is HumanConcernJournalAppendResult.ExactDuplicate -> {
                if (recoverLocked() && eventsById[event.eventId] == event) {
                    HumanConcernMutationResult.Idempotent(projections.getValue(event.concernId))
                } else {
                    HumanConcernMutationResult.Unavailable(
                        unavailableReason ?: "Concern duplicate could not be recovered",
                    )
                }
            }
            is HumanConcernJournalAppendResult.RevisionConflict -> {
                if (recoverLocked()) {
                    HumanConcernMutationResult.Rejected("Concern journal changed concurrently; retry")
                } else {
                    HumanConcernMutationResult.Unavailable(unavailableReason!!)
                }
            }
            is HumanConcernJournalAppendResult.Rejected ->
                HumanConcernMutationResult.Rejected(appended.reason)
            is HumanConcernJournalAppendResult.Unavailable -> {
                markUnavailable(appended.reason)
                HumanConcernMutationResult.Unavailable(appended.reason)
            }
        }
    }

    fun activeConcern(
        subjectPseudonym: String,
        sessionId: String,
        consentGeneration: Long,
    ): HumanConcernProjection? = when (
        val result = queryConcern(subjectPseudonym, sessionId, consentGeneration)
    ) {
        is HumanConcernQueryResult.Active -> result.projection
        HumanConcernQueryResult.None,
        is HumanConcernQueryResult.Unavailable,
        -> null
    }

    /** Unlike the compatibility nullable view, this never conflates none with unavailable. */
    fun queryConcern(
        subjectPseudonym: String,
        sessionId: String,
        consentGeneration: Long,
    ): HumanConcernQueryResult = synchronized(lock) {
        unavailableReason?.let {
            return@synchronized HumanConcernQueryResult.Unavailable(it)
        }
        val active = projections.values
            .filter {
                it.subjectPseudonym == subjectPseudonym &&
                    it.sessionId == sessionId &&
                    it.consentGeneration == consentGeneration &&
                    it.state == HumanConcernLatchState.ACTIVE
            }
            .maxByOrNull { it.lastChangedAtEpochMillis }
        active?.let(HumanConcernQueryResult::Active) ?: HumanConcernQueryResult.None
    }

    fun projection(concernId: String): HumanConcernProjection? = synchronized(lock) {
        projections[concernId]
    }

    fun isAvailable(): Boolean = synchronized(lock) { unavailableReason == null }

    private fun validationError(event: HumanConcernAuditEvent): String? {
        val current = projections[event.concernId]
        return when {
            current == null && event.expectedConcernVersion != 0L ->
                "A new concern must start at version zero"
            current == null && event.action != HumanConcernAction.REPORT ->
                "A concern must be reported before it can be resolved"
            current != null && event.expectedConcernVersion != current.version ->
                "Concern expected version does not match"
            current != null && (
                event.subjectPseudonym != current.subjectPseudonym ||
                    event.sessionId != current.sessionId ||
                    event.consentGeneration != current.consentGeneration
                ) -> "Concern subject, session, or consent binding changed"
            current != null && event.occurredAtEpochMillis < current.lastChangedAtEpochMillis ->
                "Concern events cannot be backdated"
            current?.state == HumanConcernLatchState.ACTIVE && event.action == HumanConcernAction.REPORT ->
                "Concern is already active"
            current?.state == HumanConcernLatchState.RESOLVED &&
                event.action == HumanConcernAction.RESOLVE_BY_HUMAN ->
                "Concern is already resolved"
            else -> null
        }
    }

    private fun apply(event: HumanConcernAuditEvent) {
        val currentVersion = projections[event.concernId]?.version ?: 0L
        projections[event.concernId] = HumanConcernProjection(
            concernId = event.concernId,
            subjectPseudonym = event.subjectPseudonym,
            sessionId = event.sessionId,
            consentGeneration = event.consentGeneration,
            state = if (event.action == HumanConcernAction.REPORT) {
                HumanConcernLatchState.ACTIVE
            } else {
                HumanConcernLatchState.RESOLVED
            },
            version = currentVersion + 1L,
            lastEventId = event.eventId,
            lastChangedAtEpochMillis = event.occurredAtEpochMillis,
        )
        eventsById[event.eventId] = event
    }

    private fun recoverLocked(): Boolean {
        projections.clear()
        eventsById.clear()
        journalRevision = 0L
        unavailableReason = null
        return when (val recovered = journal.recover()) {
            is HumanConcernJournalRecovery.Unavailable -> {
                markUnavailable(recovered.reason)
                false
            }
            is HumanConcernJournalRecovery.Recovered -> {
                for ((index, record) in recovered.records.withIndex()) {
                    if (record.revision != index + 1L) {
                        markUnavailable("Concern journal revisions are not contiguous")
                        return false
                    }
                    val authorityStillValid = try {
                        authorityVerifier.verify(record.event)
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!authorityStillValid) {
                        markUnavailable("Recovered concern authority could not be verified")
                        return false
                    }
                    if (eventsById.containsKey(record.event.eventId) ||
                        validationError(record.event) != null
                    ) {
                        markUnavailable("Concern journal chronology is invalid")
                        return false
                    }
                    apply(record.event)
                    journalRevision = record.revision
                }
                true
            }
        }
    }

    private fun markUnavailable(reason: String) {
        unavailableReason = reason
    }
}
