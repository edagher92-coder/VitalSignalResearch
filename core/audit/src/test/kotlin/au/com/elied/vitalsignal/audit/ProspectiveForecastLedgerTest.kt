package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.model.HealthForecast
import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProspectiveForecastLedgerTest {
    @Test
    fun lockedViewCannotExposeProbabilityOrBounds() {
        val ledger = ProspectiveForecastLedger(InMemoryForecastAuditJournal())

        val committed = ledger.commit("commit-1", forecast(), FEATURE_HASH)
        val locked = committed.view as LockedForecastView

        assertEquals(ProspectiveForecastState.COMMITTED_HIDDEN, locked.state)
        val fieldNames = LockedForecastView::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { "probability" in it })
        assertFalse(fieldNames.any { "lowerbound" in it || "upperbound" in it })
        assertFalse(locked.toString().contains("0.73123"))
    }

    @Test
    fun commitRequiresMatchingCanonicalFeatureSnapshotHash() {
        val ledger = ProspectiveForecastLedger(InMemoryForecastAuditJournal())

        val malformed = runCatching {
            ledger.commit("commit-malformed", forecast(), "A".repeat(64))
        }
        val mismatched = runCatching {
            ledger.commit("commit-mismatch", forecast(), OTHER_HASH)
        }

        assertTrue(malformed.isFailure)
        assertTrue(mismatched.isFailure)
        assertTrue(ledger.view(FORECAST_ID, 2_000L) is UnavailableForecastView)
    }

    @Test
    fun lateCommitIsRejectedEvenWhenItStillPrecedesTargetStart() {
        val ledger = ProspectiveForecastLedger(InMemoryForecastAuditJournal())
        val latencyBoundForecast = forecast().copy(maximumCommitLagMillis = 1_000L)

        assertThrows(IllegalArgumentException::class.java) {
            ledger.commit(
                eventId = "late-commit",
                forecast = latencyBoundForecast,
                canonicalFeatureSnapshotSha256 = FEATURE_HASH,
                nowEpochMillis = 2_001L,
            )
        }
    }

    @Test
    fun chronologyRequiresPersistedCheckInAndTargetEnd() {
        val ledger = ProspectiveForecastLedger(InMemoryForecastAuditJournal())
        ledger.commit("commit-1", forecast(), FEATURE_HASH)

        assertTrue(
            ledger.reveal("reveal-1", FORECAST_ID, 3_000L) is
                ForecastLedgerMutationResult.Rejected,
        )

        val checkIn = contextCheckIn()
        assertEquals(
            ProspectiveForecastState.PRE_REVEAL_CHECKIN_STORED,
            ledger.storePreRevealCheckIn(checkIn).view.state,
        )
        assertEquals(
            ProspectiveForecastState.REVEALED,
            ledger.reveal("reveal-1", FORECAST_ID, 3_000L).view.state,
        )
        assertEquals(
            ProspectiveForecastState.RESOLUTION_DUE,
            ledger.view(FORECAST_ID, TARGET_END).state,
        )

        assertThrows(IllegalArgumentException::class.java) {
            outcome("outcome-early", TARGET_END - 1L, 1.0)
        }

        val resolved = ledger.recordOutcome(outcome("outcome-1", TARGET_END, 1.0))
        assertEquals(ProspectiveForecastState.RESOLVED, resolved.view.state)
        assertEquals(1.0, (resolved.view as RevealedForecastView).observedOutcome!!)
    }

    @Test
    fun outcomeMustBindExactEndpointDefinitionAndTargetWindow() {
        val ledger = readyToResolveLedger(InMemoryForecastAuditJournal())

        val endpointMismatch = outcome("outcome-endpoint", TARGET_END, 1.0).copy(
            endpointDefinitionSha256 = OTHER_HASH,
        )
        val targetMismatch = outcome("outcome-target", TARGET_END + 1L, 1.0).copy(
            targetStartEpochMillis = TARGET_START + 1L,
            targetEndEpochMillis = TARGET_END + 1L,
            sourceAssessmentAtEpochMillis = TARGET_START + 1L,
        )

        assertTrue(ledger.recordOutcome(endpointMismatch) is ForecastLedgerMutationResult.Rejected)
        assertTrue(ledger.recordOutcome(targetMismatch) is ForecastLedgerMutationResult.Rejected)
        assertEquals(
            ProspectiveForecastState.RESOLUTION_DUE,
            ledger.view(FORECAST_ID, TARGET_END).state,
        )
    }

    @Test
    fun retrospectivePointLabelOutsideTargetWindowIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            outcome("late-label", TARGET_END + 1L, 1.0).copy(
                sourceAssessmentAtEpochMillis = TARGET_END,
            )
        }
    }

    @Test
    fun missingTargetOutcomeRemainsIndeterminate() {
        val ledger = readyToResolveLedger(InMemoryForecastAuditJournal())

        val result = ledger.recordOutcome(outcome("outcome-missing", TARGET_END, null))

        assertEquals(ProspectiveForecastState.INDETERMINATE, result.view.state)
        assertEquals(null, (result.view as RevealedForecastView).observedOutcome)
    }

    @Test
    fun exactDuplicateIsIdempotentAndConflictingReplayIsRejected() {
        val ledger = ProspectiveForecastLedger(InMemoryForecastAuditJournal())
        ledger.commit("commit-1", forecast(), FEATURE_HASH)
        val checkIn = contextCheckIn()

        assertTrue(ledger.storePreRevealCheckIn(checkIn) is ForecastLedgerMutationResult.Applied)
        assertTrue(ledger.storePreRevealCheckIn(checkIn) is ForecastLedgerMutationResult.Idempotent)

        val conflict = checkIn.copy(contextSnapshotSha256 = OTHER_HASH)
        assertTrue(
            ledger.storePreRevealCheckIn(conflict) is ForecastLedgerMutationResult.Rejected,
        )
        assertEquals(
            ProspectiveForecastState.PRE_REVEAL_CHECKIN_STORED,
            ledger.view(FORECAST_ID, 2_500L).state,
        )
    }

    @Test
    fun sharedJournalReconstructsAcrossRestarts() {
        val journal = InMemoryForecastAuditJournal()
        val firstProcess = ProspectiveForecastLedger(journal)
        firstProcess.commit("commit-1", forecast(), FEATURE_HASH)
        firstProcess.storePreRevealCheckIn(contextCheckIn())
        firstProcess.reveal("reveal-1", FORECAST_ID, 3_000L)

        val secondProcess = ProspectiveForecastLedger(journal)
        assertEquals(
            ProspectiveForecastState.REVEALED,
            secondProcess.view(FORECAST_ID, 4_000L).state,
        )
        secondProcess.recordOutcome(outcome("outcome-1", TARGET_END, 0.0))

        val thirdProcess = ProspectiveForecastLedger(journal)
        assertEquals(
            ProspectiveForecastState.RESOLVED,
            thirdProcess.view(FORECAST_ID, TARGET_END + 1L).state,
        )
        val recovered = journal.recover() as ForecastJournalRecoveryResult.Recovered
        assertEquals(4, recovered.records.size)
    }

    @Test
    fun crashBeforeAppendLeavesDurableAndRecoveredStateUnchanged() {
        val journal = InMemoryForecastAuditJournal()
        val firstProcess = ProspectiveForecastLedger(journal)
        firstProcess.commit("commit-1", forecast(), FEATURE_HASH)
        journal.failNextAppendBeforeWrite()

        val failed = firstProcess.storePreRevealCheckIn(contextCheckIn())

        assertTrue(failed is ForecastLedgerMutationResult.Rejected)
        assertEquals(
            ProspectiveForecastState.COMMITTED_HIDDEN,
            firstProcess.view(FORECAST_ID, 2_500L).state,
        )
        val restarted = ProspectiveForecastLedger(journal)
        assertEquals(
            ProspectiveForecastState.COMMITTED_HIDDEN,
            restarted.view(FORECAST_ID, 2_500L).state,
        )
    }

    @Test
    fun unreadableRecoveryMakesLedgerUnavailableAndAbstaining() {
        val journal = InMemoryForecastAuditJournal()
        journal.markUnreadable("AEAD tag mismatch")

        val ledger = ProspectiveForecastLedger(journal)

        assertEquals(ForecastLedgerAvailability.UNAVAILABLE, ledger.status().availability)
        assertTrue(ledger.view(FORECAST_ID, 4_000L) is UnavailableForecastView)
        assertTrue(
            ledger.commit("commit-1", forecast(), FEATURE_HASH) is
                ForecastLedgerMutationResult.Unavailable,
        )
    }

    @Test
    fun corruptRecoveredChronologyMakesLedgerUnavailable() {
        val corruptJournal = object : AppendOnlyForecastAuditJournal {
            override fun recover(): ForecastJournalRecoveryResult =
                ForecastJournalRecoveryResult.Recovered(
                    listOf(
                        ForecastJournalRecord(
                            revision = 1L,
                            event = ForecastOutcomeStoredEvent(
                                outcome("outcome-without-forecast", TARGET_END, 1.0),
                            ),
                        ),
                    ),
                )

            override fun append(
                event: ProspectiveForecastAuditEvent,
                expectedRevision: Long,
            ): ForecastJournalAppendResult =
                ForecastJournalAppendResult.Unavailable("Corrupt fixture")
        }

        val ledger = ProspectiveForecastLedger(corruptJournal)

        assertEquals(ForecastLedgerAvailability.UNAVAILABLE, ledger.status().availability)
        assertTrue(ledger.view(FORECAST_ID, TARGET_END) is UnavailableForecastView)
    }

    private fun readyToResolveLedger(
        journal: InMemoryForecastAuditJournal,
    ): ProspectiveForecastLedger = ProspectiveForecastLedger(journal).also { ledger ->
        ledger.commit("commit-1", forecast(), FEATURE_HASH)
        ledger.storePreRevealCheckIn(contextCheckIn())
        ledger.reveal("reveal-1", FORECAST_ID, 3_000L)
    }

    private fun contextCheckIn() = PreRevealContextCheckIn(
        eventId = "checkin-1",
        forecastId = FORECAST_ID,
        recordedAtEpochMillis = 2_000L,
        contextSnapshotSha256 = CONTEXT_HASH,
    )

    private fun outcome(
        eventId: String,
        observedAtEpochMillis: Long,
        value: Double?,
    ) = ForecastOutcomeObservation(
        eventId = eventId,
        forecastId = FORECAST_ID,
        endpointId = ENDPOINT.id,
        endpointVersion = ENDPOINT.version,
        endpointDefinitionSha256 = ENDPOINT.definitionSha256,
        targetStartEpochMillis = TARGET_START,
        targetEndEpochMillis = TARGET_END,
        sourceAssessmentAtEpochMillis = if (value == null) null else TARGET_START,
        observedAtEpochMillis = observedAtEpochMillis,
        observedOutcome = value,
        outcomeRecordSha256 = OUTCOME_HASH,
    )

    private fun forecast() = HealthForecast(
        id = FORECAST_ID,
        createdAtEpochMillis = 1_000L,
        endpoint = ENDPOINT,
        probability = 0.73123,
        lowerBound = 0.61234,
        upperBound = 0.84567,
        confidence = 0.70,
        modelVersion = "audit-test-v1",
        featureSnapshotIds = listOf("feature-snapshot-1"),
        featureSchema = FEATURE_SCHEMA,
        cutoffEpochMillis = 1_000L,
        targetStartEpochMillis = TARGET_START,
        targetEndEpochMillis = TARGET_END,
        policyVersion = "prospective-test-v1",
        featureSnapshotHash = FEATURE_HASH,
    )

    private companion object {
        const val FORECAST_ID = "forecast-audit-1"
        const val TARGET_START = 10_000L
        const val TARGET_END = 20_000L
        val FEATURE_HASH = "a".repeat(64)
        val CONTEXT_HASH = "b".repeat(64)
        val OUTCOME_HASH = "c".repeat(64)
        val OTHER_HASH = "d".repeat(64)
        val ENDPOINT = ForecastEndpointDefinition.freeze(
            id = "audit-fixture-point",
            version = "1.0.0",
            displayLabel = "Audit fixture point assessment",
            positiveClassDefinition = "Frozen audit fixture binary endpoint.",
            windowSemantics = ForecastWindowSemantics.POINT_ASSESSMENT,
            targetStartOffsetMillis = TARGET_START - 1_000L,
            targetEndOffsetMillis = TARGET_END - 1_000L,
        )
        val FEATURE_SCHEMA = ForecastFeatureSchemaDefinition.freeze(
            id = "audit-fixture-schema",
            version = "1.0.0",
            featureVersions = mapOf("fixture" to "1.0.0"),
            standardizationProtocol = "Deterministic audit test fixture.",
        )
    }
}
