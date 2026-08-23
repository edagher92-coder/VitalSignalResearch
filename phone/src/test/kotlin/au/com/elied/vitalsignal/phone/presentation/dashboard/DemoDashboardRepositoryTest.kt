package au.com.elied.vitalsignal.phone.presentation.dashboard

import au.com.elied.vitalsignal.audit.InMemoryHumanConcernJournal
import au.com.elied.vitalsignal.audit.AppendOnlyHumanConcernJournal
import au.com.elied.vitalsignal.audit.HumanConcernAuditEvent
import au.com.elied.vitalsignal.audit.HumanConcernJournalAppendResult
import au.com.elied.vitalsignal.audit.HumanConcernJournalRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDashboardRepositoryTest {
    @Test
    fun quickLog_isAddedAsUserContext_withoutChangingInterpretation() {
        val repository = DemoDashboardRepository()
        val originalHeadline = repository.state.value.headline
        assertEquals(ForecastStatus.LOCKED, repository.state.value.forecast.status)
        assertNull(repository.state.value.forecast.probability)
        assertNull(repository.state.value.forecast.personalBaseRate)
        assertNull(repository.state.value.forecast.explanation)

        repository.setQuickLogOpen(true)
        repository.saveQuickLog(
            QuickLogDraft(
                energy = 4,
                fatigue = 7,
                stress = 5,
                gastrointestinalSymptoms = 6,
                sleepQuality = 5,
                note = "Tired after lunch",
            ),
        )

        val state = repository.state.value
        assertFalse(state.quickLogOpen)
        assertEquals(originalHeadline, state.headline)
        assertEquals(TimelineKind.CONTEXT, state.timeline.first().kind)
        assertTrue(state.timeline.first().detail.contains("Fatigue 7/10"))
        assertTrue(state.timeline.first().detail.contains("Stress 5/10"))
        assertTrue(state.timeline.first().detail.contains("GI 6/10"))
        assertTrue(state.timeline.first().detail.contains("Sleep 5/10"))
        assertEquals(ForecastStatus.AVAILABLE, state.forecast.status)
        assertNotNull(state.forecast.probability)
        val explanation = requireNotNull(state.forecast.explanation)
        assertEquals(36, state.forecast.probability)
        assertEquals("Simulated 80% interval · 22–50%", state.forecast.intervalLabel)
        assertTrue(explanation.meaning.contains("36 out of 100 probability mass"))
        assertTrue(explanation.comparison.contains("33%"))
        assertTrue(explanation.why.any { it.contains("40 resolved synthetic cases") })
        assertTrue(explanation.method.any { it.contains("similarity") })
        assertTrue(explanation.improvementPlan.any { it.contains("held-out") })
        assertEquals("UNVALIDATED", state.forecast.calibrationLabel)
        assertEquals(
            "Pre-reveal context captured in memory for this simulator session",
            state.savedMessage,
        )
    }

    @Test
    fun evidenceExpansion_isExplicitUserState() {
        val repository = DemoDashboardRepository()

        repository.setExplanationExpanded(true)

        assertTrue(repository.state.value.explanationExpanded)
    }

    @Test
    fun directHumanConcernWithholdsForecastAndOverridesSensorReassurance() {
        val repository = DemoDashboardRepository()
        repository.setSimulationScenario(SimulationScenario.STEADY)

        repository.saveQuickLog(
            QuickLogDraft(
                userConcernReported = true,
                note = "Something feels wrong",
            ),
        )

        val state = repository.state.value
        assertEquals(PatternStatus.CHECK, state.status)
        assertEquals("Your concern takes priority", state.headline)
        assertEquals(0, state.confidence)
        assertTrue(state.evidence.isEmpty())
        assertEquals(ForecastStatus.ABSTAINED, state.forecast.status)
        assertNull(state.forecast.probability)
        assertNull(state.forecast.personalBaseRate)
        assertNull(state.forecast.explanation)
        assertEquals("CONCERN HOLD", state.forecast.calibrationLabel)
        assertTrue(state.conflictDesk.isEmpty())
        assertTrue(state.featureInspector.isEmpty())
        assertTrue(state.forecastAudit.isEmpty())
        assertEquals("Human concern takes priority", state.fiveSecondSummary.whatChanged)
        assertEquals(ResearchAssistantStatus.BLOCKED, state.researchAssistant.status)
        assertTrue(state.researchAssistant.narrative.contains("medical clearance"))
        assertTrue(state.savedMessage.orEmpty().contains("concern hold is active", ignoreCase = true))
        assertTrue(state.savedMessage.orEmpty().contains("No clinician or emergency service was notified"))
        assertTrue(state.timeline.first().detail.contains("USER-REPORTED CONCERN HOLD"))
    }

    @Test
    fun explicitConcernActionAppliesImmediatelyWithoutSavingTheDraft() {
        val repository = DemoDashboardRepository(clock = { 1_000L })
        repository.setQuickLogOpen(true)

        repository.reportHumanConcern()

        val state = repository.state.value
        assertFalse(state.quickLogOpen)
        assertTrue(state.activeHumanConcern)
        assertEquals(PatternStatus.CHECK, state.status)
        assertEquals(0, state.confidence)
        assertTrue(state.evidence.isEmpty())
        assertEquals(ForecastStatus.ABSTAINED, state.forecast.status)
        assertTrue(state.savedMessage.orEmpty().contains("No clinician or emergency service was notified"))
    }

    @Test
    fun partialCheckInKeepsUnansweredValuesMissingAndForecastLocked() {
        val repository = DemoDashboardRepository()

        repository.saveQuickLog(QuickLogDraft(energy = 4, note = "Only energy answered"))

        val state = repository.state.value
        assertEquals(ForecastStatus.LOCKED, state.forecast.status)
        assertNull(state.forecast.probability)
        assertTrue(state.timeline.first().detail.contains("4 not reported"))
        assertTrue(state.savedMessage.orEmpty().contains("remains locked"))
    }

    @Test
    fun concernSurvivesScenarioAndOrdinaryLogsUntilExplicitHumanResolution() {
        val journal = InMemoryHumanConcernJournal()
        val first = DemoDashboardRepository(journal, clock = { 1_000L })
        first.saveQuickLog(QuickLogDraft(userConcernReported = true))
        assertTrue(first.state.value.activeHumanConcern)

        first.setSimulationScenario(SimulationScenario.STEADY)
        first.saveQuickLog(
            QuickLogDraft(
                energy = 8,
                fatigue = 1,
                stress = 1,
                gastrointestinalSymptoms = 0,
                sleepQuality = 9,
            ),
        )
        assertTrue(first.state.value.activeHumanConcern)
        assertEquals(ForecastStatus.ABSTAINED, first.state.value.forecast.status)

        val restarted = DemoDashboardRepository(journal, clock = { 2_000L })
        assertTrue(restarted.state.value.activeHumanConcern)
        assertEquals("Your concern takes priority", restarted.state.value.headline)

        restarted.resolveHumanConcern()
        assertFalse(restarted.state.value.activeHumanConcern)
        assertEquals(ForecastStatus.LOCKED, restarted.state.value.forecast.status)
        assertTrue(restarted.state.value.savedMessage.orEmpty().contains("not medical clearance"))
    }

    @Test
    fun unavailableConcernJournalFailsSafeToHumanPriorityHold() {
        val unavailable = object : AppendOnlyHumanConcernJournal {
            override fun recover() = HumanConcernJournalRecovery.Unavailable("fixture-unavailable")

            override fun append(
                event: HumanConcernAuditEvent,
                expectedJournalRevision: Long,
            ) = HumanConcernJournalAppendResult.Unavailable("fixture-unavailable")
        }

        val repository = DemoDashboardRepository(unavailable)

        assertTrue(repository.state.value.activeHumanConcern)
        assertEquals(PatternStatus.CHECK, repository.state.value.status)
        assertEquals(ForecastStatus.ABSTAINED, repository.state.value.forecast.status)
    }

    @Test
    fun simulator_isAlwaysExplicitAndNeverClaimsCalibration() {
        val repository = DemoDashboardRepository()
        val state = repository.state.value

        assertTrue(state.isSimulated)
        assertTrue(state.dataModeLabel.contains("NOT YOUR HEALTH DATA"))
        assertFalse(state.forecast.calibrationLabel.contains("CALIBRATED"))
        assertEquals("Memory-only simulator", state.dataPlane.activeMode)
        assertEquals("REAL DATA LOCKED", state.dataPlane.pilotGateLabel)
        assertEquals(
            ResearchAssistantStatus.REVIEWED_SIMULATOR_EXPLANATION,
            state.researchAssistant.status,
        )
        assertTrue(state.researchAssistant.providerLabel.contains("no model or cloud call"))
        assertTrue(state.researchAssistant.policyLabel.contains("cannot diagnose"))
        assertEquals("One simulated sensor family moved", state.fiveSecondSummary.whatChanged)
        assertEquals("CONFLICT REJECTED · record retained", state.conflictDesk.single().action)
        assertTrue(state.featureInspector.any { it.featureId == "cardio-autonomic" })
        assertTrue(state.featureInspector.all { it.snapshotSha256Prefix.matches(Regex("[a-f0-9]{12}")) })
        assertFalse(state.featureInspector.any { it.snapshotSha256Prefix == "a1b2c3d4e5f6" })
        assertTrue(state.forecastAudit.any { it.state == "COMMITTED HIDDEN" })
        assertTrue(state.forecastAudit.any { it.state == "OUTCOME DUE" })
    }

    @Test
    fun learningAndLowQualityStatesWithholdInterpretationAndForecast() {
        val repository = DemoDashboardRepository()

        repository.setSimulationScenario(SimulationScenario.LEARNING)
        val learning = repository.state.value
        assertEquals(PatternStatus.LEARNING, learning.status)
        assertEquals(0, learning.confidence)
        assertEquals(ForecastStatus.LEARNING, learning.forecast.status)
        assertEquals(ResearchAssistantStatus.ABSTAINED, learning.researchAssistant.status)
        assertTrue(learning.evidence.isEmpty())
        assertTrue(learning.conflictDesk.isEmpty())
        assertTrue(learning.featureInspector.isEmpty())
        assertTrue(learning.forecastAudit.isEmpty())
        assertTrue(learning.fiveSecondSummary.whatChanged.contains("withheld"))
        repository.saveQuickLog(QuickLogDraft())
        assertEquals(ForecastStatus.LEARNING, repository.state.value.forecast.status)

        repository.setSimulationScenario(SimulationScenario.LOW_QUALITY)
        val unavailable = repository.state.value
        assertEquals(PatternStatus.UNAVAILABLE, unavailable.status)
        assertEquals(ForecastStatus.ABSTAINED, unavailable.forecast.status)
        assertEquals(ResearchAssistantStatus.ABSTAINED, unavailable.researchAssistant.status)
        assertTrue(unavailable.evidence.isEmpty())
    }

    @Test
    fun steadyScenarioKeepsHonestFiveSecondCopyAndAComputedSnapshotDigest() {
        val repository = DemoDashboardRepository()
        repository.setSimulationScenario(SimulationScenario.STEADY)
        val steady = repository.state.value
        assertEquals(PatternStatus.STEADY, steady.status)
        assertTrue(steady.confidence > 0)
        assertEquals("No qualified deviation from the simulated baseline", steady.fiveSecondSummary.whatChanged)
        assertTrue(steady.conflictDesk.isEmpty())
        assertTrue(steady.featureInspector.isNotEmpty())
        assertFalse(steady.featureInspector.any { it.snapshotSha256Prefix == "a1b2c3d4e5f6" })
    }

    @Test
    fun assistantNarrativeTracksTheSafetyDisposition() {
        val repository = DemoDashboardRepository()

        repository.setSimulationScenario(SimulationScenario.STEADY)

        val assistant = repository.state.value.researchAssistant
        assertEquals(ResearchAssistantStatus.REVIEWED_SIMULATOR_EXPLANATION, assistant.status)
        assertTrue(assistant.narrative.contains("cannot rule out"))
        assertFalse(assistant.narrative.contains("differs from its matched fixture"))
    }

    @Test
    fun qualifiedExerciseFixtureShowsDoseResponseRecoveryAndProvenanceBoundary() {
        val activity = DemoDashboardRepository().state.value.activityResponse

        assertEquals(ActivityResponseStatus.QUALIFIED_DESCRIPTIVE, activity.status)
        assertEquals(1_000L, activity.steps)
        assertEquals(0.9, activity.distanceKilometres!!, 0.0)
        assertEquals(10, activity.activeMinutes)
        assertEquals(125, activity.averageHeartRateBpm)
        assertEquals(130, activity.persistentPeakHeartRateBpm)
        assertEquals(20, activity.recoveryDropAt60SecondsBpm)
        assertEquals(65.0, activity.matchedWorkloadCardiacCost!!, 0.0)
        assertTrue(activity.coverageLabel.contains("HR 100%"))
        assertTrue(activity.gapLabel.contains("No gaps"))
        assertTrue(activity.protocolLabel.contains("SIMULATED"))
        assertTrue(activity.protocolLabel.contains("RESEARCH ONLY"))
        assertTrue(activity.reason.contains("does not establish fitness"))
        assertTrue(activity.comparisonLabel.contains("no cross-family"))
    }

    @Test
    fun exerciseLearningLowQualityAndConcernStatesNeverManufactureAConclusion() {
        val repository = DemoDashboardRepository()

        repository.setSimulationScenario(SimulationScenario.LEARNING)
        val learning = repository.state.value.activityResponse
        assertEquals(ActivityResponseStatus.LEARNING, learning.status)
        assertNotNull(learning.steps)
        assertTrue(learning.comparisonLabel.contains("11/12"))
        assertTrue(learning.reason.contains("comparison is withheld"))

        repository.setSimulationScenario(SimulationScenario.LOW_QUALITY)
        val unavailable = repository.state.value.activityResponse
        assertEquals(ActivityResponseStatus.ABSTAINED, unavailable.status)
        assertNull(unavailable.steps)
        assertNull(unavailable.averageHeartRateBpm)
        assertNull(unavailable.matchedWorkloadCardiacCost)
        assertTrue(unavailable.gapLabel.contains("explicitly missing"))
        assertTrue(unavailable.reason.contains("not inactivity or recovery"))

        repository.reportHumanConcern()
        val held = repository.state.value.activityResponse
        assertEquals(ActivityResponseStatus.HUMAN_CONCERN_HOLD, held.status)
        assertNull(held.steps)
        assertNull(held.recoveryDropAt60SecondsBpm)
        assertEquals("HUMAN CONCERN HOLD", held.comparisonLabel)
        assertTrue(held.reason.contains("cannot reassure"))
        assertTrue(held.reason.contains("medical clearance"))
    }

    @Test
    fun completeCheckInAdvancesTheLedgerAuditTrailAndRevealsTheCommittedPayload() {
        val repository = DemoDashboardRepository()

        repository.saveQuickLog(
            QuickLogDraft(
                energy = 4,
                fatigue = 7,
                stress = 5,
                gastrointestinalSymptoms = 6,
                sleepQuality = 5,
            ),
        )

        val state = repository.state.value
        assertEquals(ForecastStatus.AVAILABLE, state.forecast.status)
        assertNotNull(state.forecast.probability)
        assertEquals("UNVALIDATED", state.forecast.calibrationLabel)
        val context = state.forecastAudit.single { it.id == "audit-context" }
        val reveal = state.forecastAudit.single { it.id == "audit-reveal" }
        assertEquals("Stored", context.timeLabel)
        assertEquals("Revealed", reveal.timeLabel)
        assertTrue(reveal.detail.contains("not recomputed"))
    }

    @Test
    fun partialCheckInLeavesTheLedgerRevealBlocked() {
        val repository = DemoDashboardRepository()

        repository.saveQuickLog(QuickLogDraft(energy = 4))

        val state = repository.state.value
        assertEquals(ForecastStatus.LOCKED, state.forecast.status)
        assertNull(state.forecast.probability)
        assertEquals("Blocked", state.forecastAudit.single { it.id == "audit-reveal" }.timeLabel)
    }
}
