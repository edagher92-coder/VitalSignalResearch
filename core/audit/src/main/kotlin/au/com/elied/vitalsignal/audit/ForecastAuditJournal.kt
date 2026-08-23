package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.model.HealthForecast

private val CANONICAL_SHA256 = Regex("[0-9a-f]{64}")
private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9._-]{1,96}")

internal fun requireCanonicalSha256(value: String, fieldName: String) {
    require(CANONICAL_SHA256.matches(value)) {
        "$fieldName must be a canonical lowercase 64-character SHA-256"
    }
}

internal fun requireSafeIdentifier(value: String, fieldName: String) {
    require(SAFE_IDENTIFIER.matches(value)) {
        "$fieldName must contain 1–96 safe identifier characters"
    }
}

data class PreRevealContextCheckIn(
    val eventId: String,
    val forecastId: String,
    val recordedAtEpochMillis: Long,
    val contextSnapshotSha256: String,
) {
    init {
        requireSafeIdentifier(eventId, "eventId")
        requireSafeIdentifier(forecastId, "forecastId")
        require(recordedAtEpochMillis >= 0L)
        requireCanonicalSha256(contextSnapshotSha256, "contextSnapshotSha256")
    }
}

/**
 * A target-window observation is deliberately distinct from the pre-reveal
 * context check-in. A missing target observation remains null and resolves the
 * audit as indeterminate; it is never converted to a negative outcome.
 */
data class ForecastOutcomeObservation(
    val eventId: String,
    val forecastId: String,
    val endpointId: String,
    val endpointVersion: String,
    val endpointDefinitionSha256: String,
    val targetStartEpochMillis: Long,
    val targetEndEpochMillis: Long,
    val sourceAssessmentAtEpochMillis: Long?,
    val observedAtEpochMillis: Long,
    val observedOutcome: Double?,
    val outcomeRecordSha256: String,
) {
    init {
        requireSafeIdentifier(eventId, "eventId")
        requireSafeIdentifier(forecastId, "forecastId")
        requireSafeIdentifier(endpointId, "endpointId")
        requireSafeIdentifier(endpointVersion, "endpointVersion")
        requireCanonicalSha256(endpointDefinitionSha256, "endpointDefinitionSha256")
        require(targetStartEpochMillis >= 0L)
        require(targetEndEpochMillis > targetStartEpochMillis)
        require(observedAtEpochMillis >= 0L)
        require(observedAtEpochMillis >= targetEndEpochMillis) {
            "Outcome observation cannot precede its declared target-window end"
        }
        require(observedOutcome == null || observedOutcome == 0.0 || observedOutcome == 1.0) {
            "Binary forecast outcomes must be 0, 1, or missing"
        }
        if (observedOutcome == null) {
            require(sourceAssessmentAtEpochMillis == null) {
                "A missing binary outcome cannot carry an assessment timestamp"
            }
        } else {
            val assessmentAt = requireNotNull(sourceAssessmentAtEpochMillis)
            require(assessmentAt in targetStartEpochMillis until targetEndEpochMillis) {
                "Point-assessment source timestamp must be inside the declared target window"
            }
            require(observedAtEpochMillis >= assessmentAt)
        }
        requireCanonicalSha256(outcomeRecordSha256, "outcomeRecordSha256")
    }
}

sealed interface ProspectiveForecastAuditEvent {
    val eventId: String
    val forecastId: String
    val occurredAtEpochMillis: Long
}

data class ForecastCommittedEvent(
    override val eventId: String,
    val forecast: HealthForecast,
    val canonicalFeatureSnapshotSha256: String,
    val committedAtEpochMillis: Long = forecast.createdAtEpochMillis,
) : ProspectiveForecastAuditEvent {
    override val forecastId: String = forecast.id
    override val occurredAtEpochMillis: Long = committedAtEpochMillis

    init {
        requireSafeIdentifier(eventId, "eventId")
        requireSafeIdentifier(forecastId, "forecastId")
        require(forecast.outcomeName.isNotBlank() && forecast.outcomeName.length <= 160)
        requireSafeIdentifier(forecast.endpoint.id, "endpoint.id")
        requireSafeIdentifier(forecast.endpoint.version, "endpoint.version")
        requireCanonicalSha256(forecast.endpoint.definitionSha256, "endpoint.definitionSha256")
        requireSafeIdentifier(forecast.featureSchema.id, "featureSchema.id")
        requireSafeIdentifier(forecast.featureSchema.version, "featureSchema.version")
        requireCanonicalSha256(
            forecast.featureSchema.definitionSha256,
            "featureSchema.definitionSha256",
        )
        require(forecast.modelVersion.isNotBlank() && forecast.modelVersion.length <= 96)
        require(forecast.policyVersion.isNotBlank() && forecast.policyVersion.length <= 96)
        require(forecast.featureSnapshotIds.size in 1..256)
        require(forecast.featureSnapshotIds.all { it.isNotBlank() && it.length <= 160 })
        require(committedAtEpochMillis >= forecast.createdAtEpochMillis) {
            "Forecast commitment cannot be backdated before forecast creation"
        }
        require(committedAtEpochMillis < forecast.targetStartEpochMillis) {
            "Forecast must be committed before its target window starts"
        }
        require(
            committedAtEpochMillis <= forecast.cutoffEpochMillis + forecast.maximumCommitLagMillis,
        ) {
            "Forecast commitment exceeded its frozen cutoff-to-commit latency bound"
        }
        requireCanonicalSha256(
            canonicalFeatureSnapshotSha256,
            "canonicalFeatureSnapshotSha256",
        )
        require(forecast.featureSnapshotHash == canonicalFeatureSnapshotSha256) {
            "The committed forecast must reference the canonical feature snapshot SHA-256"
        }
    }
}

data class PreRevealCheckInStoredEvent(
    val checkIn: PreRevealContextCheckIn,
) : ProspectiveForecastAuditEvent {
    override val eventId: String = checkIn.eventId
    override val forecastId: String = checkIn.forecastId
    override val occurredAtEpochMillis: Long = checkIn.recordedAtEpochMillis
}

data class ForecastRevealedEvent(
    override val eventId: String,
    override val forecastId: String,
    val revealedAtEpochMillis: Long,
) : ProspectiveForecastAuditEvent {
    override val occurredAtEpochMillis: Long = revealedAtEpochMillis

    init {
        requireSafeIdentifier(eventId, "eventId")
        requireSafeIdentifier(forecastId, "forecastId")
        require(revealedAtEpochMillis >= 0L)
    }
}

data class ForecastOutcomeStoredEvent(
    val observation: ForecastOutcomeObservation,
) : ProspectiveForecastAuditEvent {
    override val eventId: String = observation.eventId
    override val forecastId: String = observation.forecastId
    override val occurredAtEpochMillis: Long = observation.observedAtEpochMillis
}

data class ForecastJournalRecord(
    val revision: Long,
    val event: ProspectiveForecastAuditEvent,
) {
    init {
        require(revision > 0L)
    }
}

sealed interface ForecastJournalRecoveryResult {
    data class Recovered(
        val records: List<ForecastJournalRecord>,
    ) : ForecastJournalRecoveryResult

    data class Unreadable(
        val reason: String,
    ) : ForecastJournalRecoveryResult
}

sealed interface ForecastJournalAppendResult {
    data class Appended(val record: ForecastJournalRecord) : ForecastJournalAppendResult
    data class ExactDuplicate(val record: ForecastJournalRecord) : ForecastJournalAppendResult
    data class ConflictingReplay(val reason: String) : ForecastJournalAppendResult
    data class RevisionConflict(val actualRevision: Long) : ForecastJournalAppendResult

    /** A fault known to have happened before any durable mutation. */
    data class NotAppended(val reason: String) : ForecastJournalAppendResult

    /** The caller cannot know whether a durable mutation occurred. */
    data class Unavailable(val reason: String) : ForecastJournalAppendResult
}

interface AppendOnlyForecastAuditJournal {
    fun recover(): ForecastJournalRecoveryResult

    fun append(
        event: ProspectiveForecastAuditEvent,
        expectedRevision: Long,
    ): ForecastJournalAppendResult
}

/**
 * Deterministic journal used by platform-free tests and simulator wiring. It
 * can be shared by successive ledger instances to exercise restart recovery.
 */
class InMemoryForecastAuditJournal(
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) : AppendOnlyForecastAuditJournal {
    private val lock = Any()
    private val records = mutableListOf<ForecastJournalRecord>()
    private var unreadableReason: String? = null
    private var failBeforeNextAppendReason: String? = null

    init {
        require(maximumRecords in 1..HARD_MAXIMUM_RECORDS)
    }

    override fun recover(): ForecastJournalRecoveryResult = synchronized(lock) {
        unreadableReason?.let { return@synchronized ForecastJournalRecoveryResult.Unreadable(it) }
        ForecastJournalRecoveryResult.Recovered(records.toList())
    }

    override fun append(
        event: ProspectiveForecastAuditEvent,
        expectedRevision: Long,
    ): ForecastJournalAppendResult = synchronized(lock) {
        unreadableReason?.let { return@synchronized ForecastJournalAppendResult.Unavailable(it) }

        records.firstOrNull { it.event.eventId == event.eventId }?.let { existing ->
            return@synchronized if (existing.event == event) {
                ForecastJournalAppendResult.ExactDuplicate(existing)
            } else {
                ForecastJournalAppendResult.ConflictingReplay(
                    "Event ID ${event.eventId} was reused with different content",
                )
            }
        }

        failBeforeNextAppendReason?.let { reason ->
            failBeforeNextAppendReason = null
            return@synchronized ForecastJournalAppendResult.NotAppended(reason)
        }

        if (expectedRevision != records.size.toLong()) {
            return@synchronized ForecastJournalAppendResult.RevisionConflict(records.size.toLong())
        }
        if (records.size >= maximumRecords) {
            return@synchronized ForecastJournalAppendResult.NotAppended("Journal capacity reached")
        }

        val record = ForecastJournalRecord(records.size + 1L, event)
        records += record
        ForecastJournalAppendResult.Appended(record)
    }

    fun failNextAppendBeforeWrite(reason: String = "Simulated crash before append") {
        synchronized(lock) {
            failBeforeNextAppendReason = reason
        }
    }

    fun markUnreadable(reason: String = "Simulated unreadable journal") {
        synchronized(lock) {
            unreadableReason = reason
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_RECORDS: Int = 100_000
        const val HARD_MAXIMUM_RECORDS: Int = 1_000_000
    }
}
