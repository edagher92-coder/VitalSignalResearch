package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.model.HealthForecast

enum class ProspectiveForecastState {
    COMMITTED_HIDDEN,
    PRE_REVEAL_CHECKIN_STORED,
    REVEALED,
    RESOLUTION_DUE,
    RESOLVED,
    INDETERMINATE,
}

enum class ForecastLedgerAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class ForecastLedgerStatus(
    val availability: ForecastLedgerAvailability,
    val reason: String? = null,
)

sealed interface ProspectiveForecastView {
    val forecastId: String?
    val state: ProspectiveForecastState?
}

/**
 * Intentionally has no probability or interval fields. This is the only view
 * returned before a reveal event has been durably appended.
 */
data class LockedForecastView(
    override val forecastId: String,
    override val state: ProspectiveForecastState,
    val targetStartEpochMillis: Long,
    val targetEndEpochMillis: Long,
    val modelVersion: String,
    val policyVersion: String,
    val canonicalFeatureSnapshotSha256: String,
    val leadTimeAtCreationMillis: Long,
) : ProspectiveForecastView {
    init {
        require(
            state == ProspectiveForecastState.COMMITTED_HIDDEN ||
                state == ProspectiveForecastState.PRE_REVEAL_CHECKIN_STORED,
        )
    }
}

data class RevealedForecastView(
    override val forecastId: String,
    override val state: ProspectiveForecastState,
    val outcomeName: String,
    val probability: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val targetStartEpochMillis: Long,
    val targetEndEpochMillis: Long,
    val leadTimeAtCreationMillis: Long,
    val observedOutcome: Double? = null,
) : ProspectiveForecastView {
    init {
        require(
            state == ProspectiveForecastState.REVEALED ||
                state == ProspectiveForecastState.RESOLUTION_DUE ||
                state == ProspectiveForecastState.RESOLVED ||
                state == ProspectiveForecastState.INDETERMINATE,
        )
    }
}

data class UnavailableForecastView(
    val reason: String,
    override val forecastId: String? = null,
) : ProspectiveForecastView {
    override val state: ProspectiveForecastState? = null
}

sealed interface ForecastLedgerMutationResult {
    val view: ProspectiveForecastView

    data class Applied(
        override val view: ProspectiveForecastView,
    ) : ForecastLedgerMutationResult

    data class Idempotent(
        override val view: ProspectiveForecastView,
    ) : ForecastLedgerMutationResult

    data class Rejected(
        val reason: String,
        override val view: ProspectiveForecastView,
    ) : ForecastLedgerMutationResult

    data class Unavailable(
        val reason: String,
        override val view: ProspectiveForecastView = UnavailableForecastView(reason),
    ) : ForecastLedgerMutationResult
}

private data class ReconstructedForecast(
    val committed: ForecastCommittedEvent,
    var checkIn: PreRevealCheckInStoredEvent? = null,
    var reveal: ForecastRevealedEvent? = null,
    var outcome: ForecastOutcomeStoredEvent? = null,
)

/**
 * A restart-safe prospective state machine over an append-only journal. The
 * journal implementation owns durability; this class refuses to advance its
 * in-memory projection until an append is confirmed.
 */
class ProspectiveForecastLedger(
    private val journal: AppendOnlyForecastAuditJournal,
) {
    private val lock = Any()
    private val forecasts = linkedMapOf<String, ReconstructedForecast>()
    private val eventsById = linkedMapOf<String, ProspectiveForecastAuditEvent>()
    private var revision: Long = 0L
    private var status = ForecastLedgerStatus(ForecastLedgerAvailability.AVAILABLE)

    init {
        recoverFromJournal()
    }

    fun status(): ForecastLedgerStatus = synchronized(lock) { status }

    fun commit(
        eventId: String,
        forecast: HealthForecast,
        canonicalFeatureSnapshotSha256: String,
        nowEpochMillis: Long = forecast.createdAtEpochMillis,
    ): ForecastLedgerMutationResult = synchronized(lock) {
        require(nowEpochMillis >= 0L)
        mutate(
            event = ForecastCommittedEvent(
                eventId = eventId,
                forecast = forecast,
                canonicalFeatureSnapshotSha256 = canonicalFeatureSnapshotSha256,
                committedAtEpochMillis = nowEpochMillis,
            ),
            nowEpochMillis = nowEpochMillis,
        )
    }

    fun storePreRevealCheckIn(
        checkIn: PreRevealContextCheckIn,
    ): ForecastLedgerMutationResult = synchronized(lock) {
        mutate(
            event = PreRevealCheckInStoredEvent(checkIn),
            nowEpochMillis = checkIn.recordedAtEpochMillis,
        )
    }

    fun reveal(
        eventId: String,
        forecastId: String,
        revealedAtEpochMillis: Long,
    ): ForecastLedgerMutationResult = synchronized(lock) {
        mutate(
            event = ForecastRevealedEvent(eventId, forecastId, revealedAtEpochMillis),
            nowEpochMillis = revealedAtEpochMillis,
        )
    }

    fun recordOutcome(
        observation: ForecastOutcomeObservation,
    ): ForecastLedgerMutationResult = synchronized(lock) {
        mutate(
            event = ForecastOutcomeStoredEvent(observation),
            nowEpochMillis = observation.observedAtEpochMillis,
        )
    }

    fun view(
        forecastId: String,
        nowEpochMillis: Long,
    ): ProspectiveForecastView = synchronized(lock) {
        require(nowEpochMillis >= 0L)
        if (status.availability == ForecastLedgerAvailability.UNAVAILABLE) {
            return@synchronized UnavailableForecastView(
                reason = status.reason ?: "Forecast journal is unavailable",
                forecastId = forecastId,
            )
        }
        val record = forecasts[forecastId]
            ?: return@synchronized UnavailableForecastView("Forecast was not found", forecastId)
        record.toPublicView(nowEpochMillis)
    }

    private fun mutate(
        event: ProspectiveForecastAuditEvent,
        nowEpochMillis: Long,
    ): ForecastLedgerMutationResult {
        if (status.availability == ForecastLedgerAvailability.UNAVAILABLE) {
            val reason = status.reason ?: "Forecast journal is unavailable"
            return ForecastLedgerMutationResult.Unavailable(
                reason,
                UnavailableForecastView(reason, event.forecastId),
            )
        }

        eventsById[event.eventId]?.let { existing ->
            return if (existing == event) {
                ForecastLedgerMutationResult.Idempotent(view(event.forecastId, nowEpochMillis))
            } else {
                ForecastLedgerMutationResult.Rejected(
                    reason = "Event ID ${event.eventId} was replayed with conflicting content",
                    view = viewOrUnavailable(event.forecastId, nowEpochMillis),
                )
            }
        }

        validationError(event)?.let { reason ->
            return ForecastLedgerMutationResult.Rejected(
                reason = reason,
                view = viewOrUnavailable(event.forecastId, nowEpochMillis),
            )
        }

        return when (val appended = journal.append(event, revision)) {
            is ForecastJournalAppendResult.Appended -> {
                if (appended.record.revision != revision + 1L || appended.record.event != event) {
                    markUnavailable("Journal returned an inconsistent append receipt")
                    ForecastLedgerMutationResult.Unavailable(
                        status.reason!!,
                        UnavailableForecastView(status.reason!!, event.forecastId),
                    )
                } else {
                    applyPersisted(event)
                    revision = appended.record.revision
                    ForecastLedgerMutationResult.Applied(view(event.forecastId, nowEpochMillis))
                }
            }

            is ForecastJournalAppendResult.ExactDuplicate -> {
                if (!recoverFromJournal()) {
                    ForecastLedgerMutationResult.Unavailable(
                        status.reason ?: "Forecast journal recovery failed",
                        UnavailableForecastView(status.reason ?: "Recovery failed", event.forecastId),
                    )
                } else if (eventsById[event.eventId] == event) {
                    ForecastLedgerMutationResult.Idempotent(view(event.forecastId, nowEpochMillis))
                } else {
                    markUnavailable("Journal duplicate could not be reconstructed")
                    ForecastLedgerMutationResult.Unavailable(
                        status.reason!!,
                        UnavailableForecastView(status.reason!!, event.forecastId),
                    )
                }
            }

            is ForecastJournalAppendResult.ConflictingReplay ->
                ForecastLedgerMutationResult.Rejected(
                    appended.reason,
                    viewOrUnavailable(event.forecastId, nowEpochMillis),
                )

            is ForecastJournalAppendResult.RevisionConflict -> {
                val recovered = recoverFromJournal()
                val reason = if (recovered) {
                    "Journal changed concurrently; retry against revision ${appended.actualRevision}"
                } else {
                    status.reason ?: "Journal recovery failed"
                }
                if (recovered) {
                    ForecastLedgerMutationResult.Rejected(
                        reason,
                        viewOrUnavailable(event.forecastId, nowEpochMillis),
                    )
                } else {
                    ForecastLedgerMutationResult.Unavailable(
                        reason,
                        UnavailableForecastView(reason, event.forecastId),
                    )
                }
            }

            is ForecastJournalAppendResult.NotAppended ->
                ForecastLedgerMutationResult.Rejected(
                    appended.reason,
                    viewOrUnavailable(event.forecastId, nowEpochMillis),
                )

            is ForecastJournalAppendResult.Unavailable -> {
                markUnavailable(appended.reason)
                ForecastLedgerMutationResult.Unavailable(
                    appended.reason,
                    UnavailableForecastView(appended.reason, event.forecastId),
                )
            }
        }
    }

    private fun validationError(event: ProspectiveForecastAuditEvent): String? {
        val current = forecasts[event.forecastId]
        return when (event) {
            is ForecastCommittedEvent -> when {
                current == null -> null
                current.committed == event -> null
                else -> "A forecast ID cannot be committed with different content"
            }

            is PreRevealCheckInStoredEvent -> when {
                current == null -> "Forecast must be committed before its pre-reveal check-in"
                current.checkIn != null -> "A pre-reveal check-in is already stored"
                current.reveal != null || current.outcome != null -> "Check-in cannot follow reveal or outcome"
                event.occurredAtEpochMillis < current.committed.forecast.createdAtEpochMillis ->
                    "Pre-reveal check-in cannot precede forecast commitment"
                event.occurredAtEpochMillis >= current.committed.forecast.targetStartEpochMillis ->
                    "Pre-reveal check-in must be stored before the forecast target window starts"
                else -> null
            }

            is ForecastRevealedEvent -> when {
                current == null -> "Forecast must be committed before reveal"
                current.checkIn == null -> "Reveal requires a durably stored pre-reveal check-in"
                current.reveal != null -> "Forecast is already revealed"
                current.outcome != null -> "Reveal cannot follow outcome"
                event.revealedAtEpochMillis < current.checkIn!!.occurredAtEpochMillis ->
                    "Reveal cannot precede the stored check-in"
                event.revealedAtEpochMillis >= current.committed.forecast.targetStartEpochMillis ->
                    "Forecast cannot first be revealed after its target window starts"
                else -> null
            }

            is ForecastOutcomeStoredEvent -> when {
                current == null -> "Forecast must be committed before outcome resolution"
                current.reveal == null -> "Outcome requires a persisted reveal event"
                current.outcome != null -> "Forecast outcome is already stored"
                event.observation.endpointId != current.committed.forecast.endpoint.id ->
                    "Outcome endpoint ID does not match the committed forecast"
                event.observation.endpointVersion != current.committed.forecast.endpoint.version ->
                    "Outcome endpoint version does not match the committed forecast"
                event.observation.endpointDefinitionSha256 !=
                    current.committed.forecast.endpoint.definitionSha256 ->
                    "Outcome endpoint definition does not match the committed forecast"
                event.observation.targetStartEpochMillis !=
                    current.committed.forecast.targetStartEpochMillis ->
                    "Outcome target start does not match the committed forecast"
                event.observation.targetEndEpochMillis !=
                    current.committed.forecast.targetEndEpochMillis ->
                    "Outcome target end does not match the committed forecast"
                event.observation.observedAtEpochMillis < current.committed.forecast.targetEndEpochMillis ->
                    "Outcome cannot be accepted before the forecast target window ends"
                else -> null
            }
        }
    }

    private fun applyPersisted(event: ProspectiveForecastAuditEvent) {
        when (event) {
            is ForecastCommittedEvent -> forecasts[event.forecastId] = ReconstructedForecast(event)
            is PreRevealCheckInStoredEvent -> forecasts.getValue(event.forecastId).checkIn = event
            is ForecastRevealedEvent -> forecasts.getValue(event.forecastId).reveal = event
            is ForecastOutcomeStoredEvent -> forecasts.getValue(event.forecastId).outcome = event
        }
        eventsById[event.eventId] = event
    }

    private fun recoverFromJournal(): Boolean {
        forecasts.clear()
        eventsById.clear()
        revision = 0L
        status = ForecastLedgerStatus(ForecastLedgerAvailability.AVAILABLE)

        val recovery = journal.recover()
        if (recovery is ForecastJournalRecoveryResult.Unreadable) {
            markUnavailable(recovery.reason)
            return false
        }
        recovery as ForecastJournalRecoveryResult.Recovered

        recovery.records.forEachIndexed { index, record ->
            val expectedRevision = index + 1L
            if (record.revision != expectedRevision) {
                markUnavailable("Journal revisions are missing, duplicated, or out of order")
                return false
            }

            eventsById[record.event.eventId]?.let { existing ->
                if (existing == record.event) {
                    revision = record.revision
                    return@forEachIndexed
                }
                markUnavailable("Journal contains a conflicting event replay")
                return false
            }

            validationError(record.event)?.let { reason ->
                markUnavailable("Corrupt journal chronology: $reason")
                return false
            }
            applyPersisted(record.event)
            revision = record.revision
        }
        return true
    }

    private fun markUnavailable(reason: String) {
        status = ForecastLedgerStatus(ForecastLedgerAvailability.UNAVAILABLE, reason)
    }

    private fun viewOrUnavailable(
        forecastId: String,
        nowEpochMillis: Long,
    ): ProspectiveForecastView = forecasts[forecastId]?.toPublicView(nowEpochMillis)
        ?: UnavailableForecastView("Forecast was not found", forecastId)

    private fun ReconstructedForecast.toPublicView(nowEpochMillis: Long): ProspectiveForecastView {
        val forecast = committed.forecast
        if (reveal == null) {
            return LockedForecastView(
                forecastId = forecast.id,
                state = if (checkIn == null) {
                    ProspectiveForecastState.COMMITTED_HIDDEN
                } else {
                    ProspectiveForecastState.PRE_REVEAL_CHECKIN_STORED
                },
                targetStartEpochMillis = forecast.targetStartEpochMillis,
                targetEndEpochMillis = forecast.targetEndEpochMillis,
                modelVersion = forecast.modelVersion,
                policyVersion = forecast.policyVersion,
                canonicalFeatureSnapshotSha256 = committed.canonicalFeatureSnapshotSha256,
                leadTimeAtCreationMillis = forecast.leadTimeAtCreationMillis,
            )
        }

        val observation = outcome?.observation
        val resolvedState = when {
            observation?.observedOutcome != null -> ProspectiveForecastState.RESOLVED
            observation != null -> ProspectiveForecastState.INDETERMINATE
            nowEpochMillis >= forecast.targetEndEpochMillis -> ProspectiveForecastState.RESOLUTION_DUE
            else -> ProspectiveForecastState.REVEALED
        }
        return RevealedForecastView(
            forecastId = forecast.id,
            state = resolvedState,
            outcomeName = forecast.outcomeName,
            probability = forecast.probability,
            lowerBound = forecast.lowerBound,
            upperBound = forecast.upperBound,
            targetStartEpochMillis = forecast.targetStartEpochMillis,
            targetEndEpochMillis = forecast.targetEndEpochMillis,
            leadTimeAtCreationMillis = forecast.leadTimeAtCreationMillis,
            observedOutcome = observation?.observedOutcome,
        )
    }
}
