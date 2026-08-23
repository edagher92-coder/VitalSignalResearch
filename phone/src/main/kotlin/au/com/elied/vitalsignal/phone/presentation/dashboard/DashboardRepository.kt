package au.com.elied.vitalsignal.phone.presentation.dashboard

import au.com.elied.vitalsignal.analytics.ForecastModelState
import au.com.elied.vitalsignal.analytics.ForecastDiagnostics
import au.com.elied.vitalsignal.analytics.SafetyDisposition
import au.com.elied.vitalsignal.analytics.canonicalSha256
import au.com.elied.vitalsignal.audit.AppendOnlyHumanConcernJournal
import au.com.elied.vitalsignal.audit.LockedForecastView
import au.com.elied.vitalsignal.audit.ProspectiveForecastState
import au.com.elied.vitalsignal.audit.RevealedForecastView
import au.com.elied.vitalsignal.audit.HumanConcernAction
import au.com.elied.vitalsignal.audit.HumanConcernActorRole
import au.com.elied.vitalsignal.audit.HumanConcernAuditEvent
import au.com.elied.vitalsignal.audit.HumanConcernAuthorityVerifier
import au.com.elied.vitalsignal.audit.HumanConcernLatchState
import au.com.elied.vitalsignal.audit.HumanConcernLedger
import au.com.elied.vitalsignal.audit.HumanConcernMutationResult
import au.com.elied.vitalsignal.audit.HumanConcernQueryResult
import au.com.elied.vitalsignal.audit.InMemoryHumanConcernJournal
import au.com.elied.vitalsignal.model.BaselineDeviation
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.phone.presentation.brand.ProductBrand
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface DashboardRepository {
    val state: StateFlow<DashboardUiState>
    fun setExplanationExpanded(expanded: Boolean)
    fun setQuickLogOpen(open: Boolean)
    fun saveQuickLog(draft: QuickLogDraft)
    fun reportHumanConcern()
    fun resolveHumanConcern()
    fun clearSavedMessage()
    fun setSimulationScenario(scenario: SimulationScenario)
}

/**
 * Deterministic simulator for product, safety-state and interaction testing.
 * It never represents live sensor data and never claims durable persistence.
 */
class DemoDashboardRepository(
    concernJournal: AppendOnlyHumanConcernJournal = InMemoryHumanConcernJournal(),
    private val clock: () -> Long = System::currentTimeMillis,
) : DashboardRepository {
    private val simulatorPipeline = SimulatorHealthPipeline()
    private val concernLedger = HumanConcernLedger(
        journal = concernJournal,
        authorityVerifier = HumanConcernAuthorityVerifier { event ->
            event.authorityReceiptId == "sim-authority-${event.eventId}" &&
                event.actorRole == HumanConcernActorRole.PARTICIPANT &&
                event.actorPrincipalId == SIMULATOR_PARTICIPANT
        },
    )
    private var concernEventCounter = 0L
    private val mutableState = MutableStateFlow(
        simulationState(
            scenario = SimulationScenario.DEVELOPING,
            userConcernReported = activeLedgerConcern(),
        ),
    )
    override val state: StateFlow<DashboardUiState> = mutableState.asStateFlow()

    override fun setExplanationExpanded(expanded: Boolean) {
        mutableState.update { it.copy(explanationExpanded = expanded) }
    }

    override fun setQuickLogOpen(open: Boolean) {
        mutableState.update { it.copy(quickLogOpen = open) }
    }

    override fun setSimulationScenario(scenario: SimulationScenario) {
        val current = mutableState.value
        val concernActive = current.activeHumanConcern || activeLedgerConcern()
        mutableState.value = simulationState(scenario, concernActive).copy(
            activeHumanConcern = concernActive,
            savedMessage = current.savedMessage,
        )
    }

    override fun saveQuickLog(draft: QuickLogDraft) {
        val detail = buildString {
            val responses = listOfNotNull(
                draft.energy?.let { "Energy $it/10" },
                draft.fatigue?.let { "Fatigue $it/10" },
                draft.stress?.let { "Stress $it/10" },
                draft.gastrointestinalSymptoms?.let { "GI $it/10" },
                draft.sleepQuality?.let { "Sleep $it/10" },
            )
            append(if (responses.isEmpty()) "No scale responses reported" else responses.joinToString(" · "))
            val missingCount = 5 - responses.size
            if (missingCount > 0) append(" · $missingCount not reported")
            if (draft.userConcernReported) append(" · USER-REPORTED CONCERN HOLD")
            if (draft.note.isNotBlank()) append(" · ${draft.note.trim()}")
        }
        val reportApplied = if (draft.userConcernReported && !mutableState.value.activeHumanConcern) {
            reportConcern()
        } else {
            true
        }
        mutableState.update { current ->
            val concernActive = current.activeHumanConcern || draft.userConcernReported || activeLedgerConcern()
            val evaluated = simulatorPipeline.evaluate(
                scenario = current.activeSimulationScenario,
                userConcernReported = concernActive,
            )
            val safetyAdjusted = current.withPipelineResult(evaluated)
            val revealOutcome = if (
                safetyAdjusted.forecast.status == ForecastStatus.LOCKED &&
                draft.hasCompleteForecastContext &&
                !concernActive
            ) {
                simulatorPipeline.revealCommittedForecast(
                    result = evaluated,
                    contextSnapshotSha256 = contextSnapshotSha256(detail),
                )
            } else {
                null
            }
            val revealedView = (revealOutcome as? ForecastRevealOutcome.Revealed)?.view
            val diagnostics = evaluated.forecastEstimate.diagnostics
            val revealedForecast = when (revealOutcome) {
                null -> safetyAdjusted.forecast
                is ForecastRevealOutcome.Revealed -> safetyAdjusted.forecast.copy(
                    status = ForecastStatus.AVAILABLE,
                    headline = "Lower-than-personal-usual energy/function at +72h to +73h",
                    summary = "Unvalidated simulator estimate for the frozen binary target-window check-in. It is not a health prediction, diagnosis, or treatment recommendation.",
                    probability = (revealOutcome.view.probability * 100.0).roundToInt(),
                    personalBaseRate =
                        diagnostics?.let { (it.unweightedOutcomeRate * 100.0).roundToInt() },
                    intervalLabel = "Engineering uncertainty band · " +
                        "${floor(revealOutcome.view.lowerBound * 100.0).toInt()}–" +
                        "${ceil(revealOutcome.view.upperBound * 100.0).toInt()}% " +
                        "(nominal 80% model range)",
                    calibrationLabel = "UNVALIDATED",
                    explanation = diagnostics?.let {
                        forecastExplanation(evaluated, revealOutcome.view, it)
                    },
                )

                is ForecastRevealOutcome.Refused -> safetyAdjusted.forecast.copy(
                    status = ForecastStatus.ABSTAINED,
                    headline = "Forecast withheld",
                    summary = "The prospective ledger refused this reveal: ${revealOutcome.reason}",
                    probability = null,
                    personalBaseRate = null,
                    intervalLabel = "No probability displayed",
                    calibrationLabel = "ABSTAINED",
                    explanation = null,
                )
            }
            safetyAdjusted.copy(
                quickLogOpen = false,
                activeHumanConcern = concernActive,
                savedMessage = when {
                    concernActive && reportApplied ->
                        "User-reported concern hold is active for this simulator session. No clinician or emergency service was notified; this app is not attended."
                    concernActive ->
                        "Concern hold remains active, but its simulator audit record was unavailable. No clinician or emergency service was notified."
                    !draft.hasCompleteForecastContext ->
                        "Partial context saved; unanswered items stayed missing and the forecast remains locked."
                    else -> "Pre-reveal context captured in memory for this simulator session"
                },
                forecast = revealedForecast,
                forecastAudit = revealedView
                    ?.let { revealedAuditTrail(safetyAdjusted.forecastAudit, it) }
                    ?: safetyAdjusted.forecastAudit,
                timeline = listOf(
                    TimelineItemUiModel(
                        id = "quick-log-now",
                        timeLabel = "Now",
                        title = if (concernActive) {
                            "User-reported concern hold"
                        } else {
                            "Pre-reveal simulator context"
                        },
                        detail = detail,
                        kind = TimelineKind.CONTEXT,
                    ),
                ) + safetyAdjusted.timeline,
            )
        }
    }

    override fun reportHumanConcern() {
        val current = mutableState.value
        if (current.activeHumanConcern) {
            mutableState.update { it.copy(quickLogOpen = false) }
            return
        }
        val reportApplied = reportConcern()
        mutableState.update { prior ->
            val evaluated = simulatorPipeline.evaluate(
                scenario = prior.activeSimulationScenario,
                userConcernReported = true,
            )
            prior.withPipelineResult(evaluated).copy(
                quickLogOpen = false,
                activeHumanConcern = true,
                savedMessage = if (reportApplied) {
                    "User-reported concern hold started for this simulator session. No clinician or emergency service was notified; this app is not attended."
                } else {
                    "Concern hold is active, but its simulator audit record was unavailable. No clinician or emergency service was notified."
                },
                timeline = listOf(
                    TimelineItemUiModel(
                        id = "concern-now",
                        timeLabel = "Now",
                        title = "User-reported concern hold",
                        detail = "Immediate simulator hold · nobody was notified · not emergency triage",
                        kind = TimelineKind.CONTEXT,
                    ),
                ) + prior.timeline,
            )
        }
    }

    override fun resolveHumanConcern() {
        val current = mutableState.value
        if (!current.activeHumanConcern) return
        val projection = concernLedger.projection(SIMULATOR_CONCERN_ID)
        val now = nextEventTime(projection?.lastChangedAtEpochMillis ?: 0L)
        val event = concernEvent(
            eventId = nextEventId("resolve", now),
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedVersion = projection?.version ?: 0L,
            occurredAtEpochMillis = now,
        )
        val resolved = concernLedger.mutate(event).let {
            it is HumanConcernMutationResult.Applied || it is HumanConcernMutationResult.Idempotent
        }
        if (!resolved) {
            mutableState.update {
                it.copy(savedMessage = "Concern remains active because an auditable human resolution could not be recorded.")
            }
            return
        }
        val reset = simulationState(current.activeSimulationScenario, userConcernReported = false)
        mutableState.value = reset.copy(
            activeHumanConcern = false,
            savedMessage = "Simulator-session concern hold resolved by an explicit person action. This is not medical clearance.",
            timeline = listOf(
                TimelineItemUiModel(
                    id = "concern-resolved-$now",
                    timeLabel = "Now",
                    title = "Simulator concern hold resolved",
                    detail = "Explicit simulator action · not medical clearance",
                    kind = TimelineKind.CONTEXT,
                ),
            ) + reset.timeline,
        )
    }

    override fun clearSavedMessage() {
        mutableState.update { it.copy(savedMessage = null) }
    }

    private fun simulationState(
        scenario: SimulationScenario,
        userConcernReported: Boolean = false,
    ): DashboardUiState {
        val evaluation = simulatorPipeline.evaluate(scenario, userConcernReported)
        val developing = developingState()
        val scenarioState = when (scenario) {
            SimulationScenario.DEVELOPING -> developing
            SimulationScenario.STEADY -> developing.copy(
                activeSimulationScenario = scenario,
                status = PatternStatus.STEADY,
                headline = "Your simulated pattern is close to expected",
                summary = "Qualified overnight domains stayed within the fixture's time- and activity-matched range. This does not rule out a health condition.",
                nextStep = "Record how you feel. Repeat a resting measurement if symptoms or the next fixture state concern you.",
                confidence = 81,
                qualifiedSignalCount = 4,
                recheckLabel = "Tomorrow",
                fiveSecondSummary = FiveSecondSummaryUiModel(
                    whatChanged = "No qualified deviation from the simulated baseline",
                    evidence = "All qualified domains stayed within range",
                    nextStep = "Repeat a measurement if symptoms or a new fixture state concern you",
                ),
                forecast = ForecastUiModel(
                    status = ForecastStatus.LOCKED,
                    horizonLabel = "72-hour point assessment · +72h to +73h",
                    headline = "Check-in required before forecast",
                    summary = "A time-stamped simulator forecast exists, but remains hidden until the pre-forecast check-in is recorded.",
                    probability = null,
                    personalBaseRate = null,
                    intervalLabel = "Probability and interval are absent from the locked UI state",
                    calibrationLabel = "LOCKED",
                ),
                trend = listOf(
                    TrendPointUiModel("Sat", 0.10f, -0.65f, 0.65f),
                    TrendPointUiModel("Sun", -0.18f, -0.65f, 0.65f),
                    TrendPointUiModel("Mon", 0.22f, -0.65f, 0.65f),
                    TrendPointUiModel("Tue", 0.06f, -0.65f, 0.65f),
                    TrendPointUiModel("Wed", 0.14f, -0.65f, 0.65f),
                    TrendPointUiModel("Thu", -0.12f, -0.65f, 0.65f),
                    TrendPointUiModel("Today", 0.20f, -0.65f, 0.65f),
                ),
            )

            SimulationScenario.LEARNING -> developing.copy(
                activeSimulationScenario = scenario,
                status = PatternStatus.LEARNING,
                headline = "Learning your personal pattern",
                summary = "This fixture has not reached the minimum baseline duration and relevant-context sample count. Only baseline progress and measurement quality are shown; no qualified current point is available.",
                nextStep = "Keep wearing the watch consistently and add one check-in; no physiological interpretation is available yet.",
                confidence = 0,
                qualifiedSignalCount = 0,
                recheckLabel = "15 days",
                baselineDays = 13,
                baselineTargetDays = 28,
                forecast = ForecastUiModel(
                    status = ForecastStatus.LEARNING,
                    horizonLabel = "Personal forecast",
                    headline = "Forecast learning is active",
                    summary = "At least 30 prior resolved outcomes are preferred before calibration can be assessed.",
                    probability = null,
                    personalBaseRate = null,
                    intervalLabel = "No probability displayed",
                    calibrationLabel = "LEARNING 0/30",
                ),
                activityResponse = learningActivityResponse(),
                evidence = emptyList(),
                trend = emptyList(),
            )

            SimulationScenario.LOW_QUALITY -> developing.copy(
                activeSimulationScenario = scenario,
                status = PatternStatus.UNAVAILABLE,
                headline = "Not enough reliable data to interpret",
                summary = "Watch contact and motion failed required quality gates in this fixture. Missing or noisy data is not treated as a typical health pattern.",
                nextStep = "Adjust the watch fit, remain still, and repeat a resting capture. How you feel matters more than this unavailable result.",
                confidence = 0,
                qualifiedSignalCount = 0,
                recheckLabel = "Now",
                signalQuality = 41,
                coverageHours = 8.2,
                forecast = ForecastUiModel(
                    status = ForecastStatus.ABSTAINED,
                    horizonLabel = "Personal forecast",
                    headline = "Forecast withheld",
                    summary = "The simulator abstained because measurement quality was below the interpretation threshold.",
                    probability = null,
                    personalBaseRate = null,
                    intervalLabel = "Collect a qualified measurement first",
                    calibrationLabel = "ABSTAINED",
                ),
                activityResponse = abstainedActivityResponse(),
                evidence = emptyList(),
                trend = emptyList(),
                qualitySignals = listOf(
                    QualitySignalUiModel("Wear time", 58, "8.2 hours captured"),
                    QualitySignalUiModel("Sensor contact", 36, "Contact failed overnight"),
                    QualitySignalUiModel(
                        "Motion cleanliness",
                        29,
                        "Only 29/100 clean; too much movement",
                    ),
                ),
            )
        }
        return scenarioState.withPipelineResult(evaluation).copy(
            activeHumanConcern = userConcernReported,
        )
    }

    private fun reportConcern(): Boolean {
        val projection = concernLedger.projection(SIMULATOR_CONCERN_ID)
        if (projection?.state == HumanConcernLatchState.ACTIVE) return true
        val now = nextEventTime(projection?.lastChangedAtEpochMillis ?: 0L)
        val event = concernEvent(
            eventId = nextEventId("report", now),
            action = HumanConcernAction.REPORT,
            expectedVersion = projection?.version ?: 0L,
            occurredAtEpochMillis = now,
        )
        return concernLedger.mutate(event).let {
            it is HumanConcernMutationResult.Applied || it is HumanConcernMutationResult.Idempotent
        }
    }

    private fun activeLedgerConcern(): Boolean = when (
        concernLedger.queryConcern(
            subjectPseudonym = SIMULATOR_SUBJECT,
            sessionId = SIMULATOR_SESSION,
            consentGeneration = SIMULATOR_CONSENT_GENERATION,
        )
    ) {
        is HumanConcernQueryResult.Active,
        is HumanConcernQueryResult.Unavailable,
        -> true
        HumanConcernQueryResult.None -> false
    }

    private fun concernEvent(
        eventId: String,
        action: HumanConcernAction,
        expectedVersion: Long,
        occurredAtEpochMillis: Long,
    ): HumanConcernAuditEvent = HumanConcernAuditEvent(
        eventId = eventId,
        concernId = SIMULATOR_CONCERN_ID,
        subjectPseudonym = SIMULATOR_SUBJECT,
        sessionId = SIMULATOR_SESSION,
        consentGeneration = SIMULATOR_CONSENT_GENERATION,
        expectedConcernVersion = expectedVersion,
        action = action,
        actorPrincipalId = SIMULATOR_PARTICIPANT,
        actorRole = HumanConcernActorRole.PARTICIPANT,
        occurredAtEpochMillis = occurredAtEpochMillis,
        contextSnapshotSha256 = "c".repeat(64),
        authorityReceiptId = "sim-authority-$eventId",
    )

    private fun nextEventTime(lastChangedAtEpochMillis: Long): Long =
        maxOf(clock(), lastChangedAtEpochMillis + 1L, 1L)

    private fun nextEventId(action: String, occurredAtEpochMillis: Long): String {
        concernEventCounter += 1L
        return "sim-$action-$occurredAtEpochMillis-$concernEventCounter"
    }

    private fun forecastExplanation(
        result: SimulatorPipelineResult,
        revealed: RevealedForecastView,
        diagnostics: ForecastDiagnostics,
    ): ForecastExplanationUiModel {
        val targetCardio = result.targetFeatures.featureValues
            .getValue("cardio-autonomic")
            .standardizedValue
        val targetSleep = result.targetFeatures.featureValues
            .getValue("sleep")
            .standardizedValue
        val nearerNeighborhood = if (abs(targetCardio - 7.5) < abs(targetCardio - 0.5)) {
            "higher-deviation fixture neighborhood (7.5)"
        } else {
            "steady fixture neighborhood (0.5)"
        }
        val point = percentOneDecimal(diagnostics.posteriorOutcomeRate)
        val raw = percentOneDecimal(diagnostics.unweightedOutcomeRate)
        val weighted = percentOneDecimal(diagnostics.similarityWeightedOutcomeRate)
        val weightingDelta = signedPoints(
            diagnostics.similarityWeightedOutcomeRate - diagnostics.unweightedOutcomeRate,
        )
        val priorDelta = signedPoints(
            diagnostics.posteriorOutcomeRate - diagnostics.similarityWeightedOutcomeRate,
        )
        val lower = floor(revealed.lowerBound * 100.0).toInt()
        val upper = ceil(revealed.upperBound * 100.0).toInt()

        return ForecastExplanationUiModel(
            meaning = "This generated fixture assigns $point% probability mass to a lower-than-personal-usual energy/function check-in during the single hour 72–73 hours after the cutoff. It does not mean a $point% loss of energy, a diagnosis, or that an outcome will occur.",
            comparison = "${diagnostics.resolvedPositiveCaseCount} of ${diagnostics.resolvedCaseCount} eligible fixture cases had the outcome ($raw%). Similarity and quality weighting move that rate to $weighted% ($weightingDelta points). The Beta(${diagnostics.priorAlpha.toInt()},${diagnostics.priorBeta.toInt()}) regularizing prior then pulls the estimate toward 50%, producing $point% ($priorDelta points).",
            why = listOf(
                "The cardio-autonomic feature is ${formatOneDecimal(targetCardio)} and is nearest the $nearerNeighborhood.",
                "The sleep feature is ${formatOneDecimal(targetSleep)} relative to its generated matched reference; it changes similarity weights but is not a causal explanation.",
                "${diagnostics.resolvedCaseCount} resolved synthetic cases pass prospective, schema, quality, identity, and receipt gates.",
                "The check-in unlocks the ledger projection. It is context, not the later outcome, and does not enter the frozen feature weights.",
            ),
            method = listOf(
                "Freeze cardio-autonomic and sleep values, quality, cutoff, and provenance before the target window.",
                "Weight each eligible past fixture by feature similarity × historical quality × current quality.",
                "Combine weighted outcomes with the named Beta(${diagnostics.priorAlpha.toInt()},${diagnostics.priorBeta.toInt()}) regularizing prior.",
                "Show an outward-rounded $lower–$upper% engineering uncertainty band using a normal posterior approximation plus a quality penalty. It is not validated coverage and excludes real-world model error.",
            ),
            couldChange = listOf(
                "New qualified target features before a future forecast cutoff.",
                "More prospectively resolved outcomes using the same endpoint and schema.",
                "Different quality, missingness, or similarity to eligible history.",
                "A versioned model, endpoint, prior, or policy change that passes offline review.",
            ),
            improvementPlan = listOf(
                "Replace generated cases with consented, independently verified prospective outcomes.",
                "Report held-out calibration, discrimination, subgroup behavior, and interval coverage.",
                "Add sensitivity and ablation views derived from typed diagnostics, never generated prose.",
                "Keep abstention, human-concern override, immutable cutoffs, and ledger auditing as release gates.",
            ),
        )
    }

    private fun percentOneDecimal(rate: Double): String =
        String.format(Locale.US, "%.1f", rate * 100.0)

    private fun signedPoints(deltaRate: Double): String =
        String.format(Locale.US, "%+.1f", deltaRate * 100.0)

    private fun DashboardUiState.withPipelineResult(
        result: SimulatorPipelineResult,
    ): DashboardUiState {
        val generated = result.forecastEstimate.forecast
        val forecastStatus = when {
            result.safetyDecision.disposition == SafetyDisposition.USER_CONCERN_REVIEW ->
                ForecastStatus.ABSTAINED
            result.forecastEstimate.state == ForecastModelState.ABSTAINED -> ForecastStatus.ABSTAINED
            result.safetyDecision.disposition == SafetyDisposition.LEARNING -> ForecastStatus.LEARNING
            else -> forecast.status
        }
        val generatedInterval = generated?.let {
            val lower = floor(it.lowerBound * 100.0).toInt()
            val upper = ceil(it.upperBound * 100.0).toInt()
            "Engineering uncertainty band · $lower–$upper% (nominal 80% model range)"
        } ?: forecast.intervalLabel
        val forecastIsRevealable = forecastStatus == ForecastStatus.AVAILABLE
        val generatedEvidence = result.deviations
            .filter { it.quality.interpretationGrade }
            .map(::toEvidenceUiModel)
        val concernOverridesSensors =
            result.safetyDecision.disposition == SafetyDisposition.USER_CONCERN_REVIEW
        val wearableEvidenceWithheld = concernOverridesSensors ||
            result.safetyDecision.disposition in setOf(
                SafetyDisposition.LEARNING,
                SafetyDisposition.MEASUREMENT_UNAVAILABLE,
                SafetyDisposition.ABSTAINED,
                SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS,
            )
        val assistantForDisposition = when (result.safetyDecision.disposition) {
            SafetyDisposition.USER_CONCERN_REVIEW,
            SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS,
            -> researchAssistant.copy(
                status = ResearchAssistantStatus.BLOCKED,
                providerLabel = "Safety policy · no model call",
                templateId = ResearchAssistantTemplateId.SAFETY_BLOCKED,
            )

            SafetyDisposition.LEARNING,
            SafetyDisposition.MEASUREMENT_UNAVAILABLE,
            SafetyDisposition.ABSTAINED,
            -> researchAssistant.copy(
                status = ResearchAssistantStatus.ABSTAINED,
                providerLabel = "Evidence gate · no model call",
                templateId = ResearchAssistantTemplateId.EVIDENCE_ABSTAINED,
            )

            SafetyDisposition.TYPICAL -> researchAssistant.copy(
                templateId = ResearchAssistantTemplateId.WITHIN_PATTERN,
            )

            SafetyDisposition.SINGLE_SIGNAL_REMEASURE -> researchAssistant

            SafetyDisposition.PATTERN_ELIGIBLE -> researchAssistant.copy(
                templateId = ResearchAssistantTemplateId.PATTERN_REVIEW,
            )
        }

        return copy(
            status = when (result.safetyDecision.disposition) {
                SafetyDisposition.USER_CONCERN_REVIEW -> PatternStatus.CHECK
                SafetyDisposition.LEARNING -> PatternStatus.LEARNING
                SafetyDisposition.MEASUREMENT_UNAVAILABLE,
                SafetyDisposition.ABSTAINED,
                -> PatternStatus.UNAVAILABLE

                SafetyDisposition.TYPICAL -> PatternStatus.STEADY
                SafetyDisposition.SINGLE_SIGNAL_REMEASURE -> PatternStatus.DEVELOPING
                SafetyDisposition.PATTERN_ELIGIBLE -> PatternStatus.CHECK
                SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS -> PatternStatus.CHECK
            },
            headline = if (concernOverridesSensors) {
                "Your concern takes priority"
            } else {
                headline
            },
            summary = if (concernOverridesSensors) {
                result.safetyDecision.userMessage
            } else {
                summary
            },
            nextStep = if (concernOverridesSensors) {
                "No clinician or emergency service was notified. Follow your care plan or contact appropriate help yourself; seek urgent help if you believe this is an emergency."
            } else {
                nextStep
            },
            recheckLabel = if (concernOverridesSensors) "User concern" else recheckLabel,
            confidence = when {
                wearableEvidenceWithheld -> 0
                result.insight != null -> ((result.insight.confidence) * 100.0).toInt()
                else -> confidence
            },
            qualifiedSignalCount = if (wearableEvidenceWithheld) {
                0
            } else {
                result.deviations.count { it.quality.interpretationGrade }
            },
            baselineDays = result.effectiveDays,
            signalQuality = (result.quality.score * 100.0).toInt(),
            evidence = if (wearableEvidenceWithheld) emptyList() else generatedEvidence,
            trend = if (wearableEvidenceWithheld) emptyList() else trend,
            researchAssistant = assistantForDisposition,
            activityResponse = if (concernOverridesSensors) {
                concernHeldActivityResponse()
            } else {
                activityResponse
            },
            fiveSecondSummary = when {
                concernOverridesSensors -> FiveSecondSummaryUiModel(
                    whatChanged = "Human concern takes priority",
                    evidence = "Wearable analytics withheld",
                    nextStep = "Follow your own care plan; this app did not notify anyone",
                )
                wearableEvidenceWithheld -> FiveSecondSummaryUiModel(
                    whatChanged = "Wearable interpretation is withheld",
                    evidence = "Missing, immature, or low-quality evidence stays unavailable",
                    nextStep = "Record how you feel, then collect a qualified measurement",
                )
                else -> fiveSecondSummary
            },
            conflictDesk = if (wearableEvidenceWithheld) {
                emptyList()
            } else if (result.safetyDecision.disposition == SafetyDisposition.SINGLE_SIGNAL_REMEASURE) {
                conflictDesk
            } else {
                emptyList()
            },
            featureInspector = if (wearableEvidenceWithheld) {
                emptyList()
            } else {
                inspectorRows(result)
            },
            forecastAudit = if (wearableEvidenceWithheld) emptyList() else forecastAuditTrail(result),
            forecast = forecast.copy(
                status = forecastStatus,
                probability = if (forecastIsRevealable) {
                    generated?.let { (it.probability * 100.0).roundToInt() }
                } else {
                    null
                },
                personalBaseRate = if (forecastIsRevealable) {
                    result.forecastEstimate.diagnostics
                        ?.let { (it.unweightedOutcomeRate * 100.0).roundToInt() }
                } else null,
                headline = when {
                    concernOverridesSensors -> "Forecast withheld"
                    forecastStatus == ForecastStatus.ABSTAINED -> "Forecast unavailable"
                    else -> forecast.headline
                },
                summary = when {
                    concernOverridesSensors ->
                        "A reported human concern overrides the wearable forecast. No probability is displayed."
                    forecastStatus == ForecastStatus.ABSTAINED ->
                        "The forecast path abstained. No estimate or prior explanation is retained."
                    else -> forecast.summary
                },
                intervalLabel = when {
                    concernOverridesSensors -> "Withheld because concern overrides wearable output"
                    forecastStatus == ForecastStatus.LOCKED ->
                        "Probability and interval are absent from the locked UI state"
                    forecastStatus != ForecastStatus.AVAILABLE -> "No probability displayed"
                    else -> generatedInterval
                },
                calibrationLabel = if (concernOverridesSensors) {
                    "CONCERN HOLD"
                } else when (result.forecastEstimate.state) {
                    ForecastModelState.READY -> if (forecastStatus == ForecastStatus.LOCKED) {
                        "LOCKED"
                    } else {
                        "UNVALIDATED"
                    }
                    ForecastModelState.LEARNING ->
                        "LEARNING ${result.forecastEstimate.validCaseCount}/30"
                    ForecastModelState.ABSTAINED -> "ABSTAINED"
                },
                explanation = if (forecastIsRevealable) forecast.explanation else null,
            ),
        )
    }

    private fun toEvidenceUiModel(deviation: BaselineDeviation): EvidenceUiModel {
        val magnitude = abs(deviation.robustZ)
        val direction = when {
            deviation.metric == SensorMetric.SKIN_TEMPERATURE -> EvidenceDirection.CONTEXT_ONLY
            magnitude >= 1.5 -> EvidenceDirection.CONTRIBUTES_TO_CHANGE
            else -> EvidenceDirection.SUPPORTS_STEADY
        }
        val comparison = if (magnitude < 1.5) {
            "Within the simulated matched range"
        } else {
            val relative = if (deviation.robustZ > 0.0) "above" else "below"
            "${formatOneDecimal(magnitude)} baseline-adjusted units $relative the fixture reference"
        }
        return EvidenceUiModel(
            id = deviation.windowId,
            label = displayName(deviation.metric),
            value = "${formatOneDecimal(deviation.observed)} ${deviation.metric.unit}",
            comparison = comparison,
            direction = direction,
            quality = (deviation.quality.score * 100.0).toInt(),
            provenance = deviation.provenanceIds.joinToString(),
        )
    }

    private fun displayName(metric: SensorMetric): String = metric.name
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun developingState() = DashboardUiState(
        greeting = "Good morning, Elz",
        dataModeLabel = "SIMULATED DATA · NOT YOUR HEALTH DATA",
        isSimulated = true,
        activeSimulationScenario = SimulationScenario.DEVELOPING,
        status = PatternStatus.DEVELOPING,
        headline = "A simulated recovery shift is developing",
        summary = "One cardio-autonomic fixture moved overnight, with a small thermal change as context. It needs persistence and a second independent domain before this is treated as a meaningful pattern change.",
        nextStep = "Record how you feel, then repeat a high-quality resting measurement tomorrow. Seek clinical advice if real symptoms concern you.",
        confidence = 72,
        qualifiedSignalCount = 3,
        recheckLabel = "1 night",
        baselineDays = 30,
        baselineTargetDays = 28,
        signalQuality = 91,
        coverageHours = 22.4,
        connectedDevice = "Wrist wearable simulator",
        lastSyncLabel = "Fixture loaded",
        forecast = ForecastUiModel(
            status = ForecastStatus.LOCKED,
            horizonLabel = "72-hour point assessment · +72h to +73h",
            headline = "Check-in required before forecast",
            summary = "A time-stamped simulator forecast exists, but remains hidden until the pre-forecast check-in is recorded.",
            probability = null,
            personalBaseRate = null,
            intervalLabel = "Probability and interval are absent from the locked UI state",
            calibrationLabel = "LOCKED",
        ),
        researchAssistant = ResearchAssistantUiModel(
            status = ResearchAssistantStatus.REVIEWED_SIMULATOR_EXPLANATION,
            title = ProductBrand.SCIENTIST_TITLE,
            providerLabel = "Reviewed template · no model or cloud call",
            templateId = ResearchAssistantTemplateId.DEVELOPING_REMEASURE,
            policyLabel = "Advisory only · cannot diagnose, recommend treatment, change medication, or clear an emergency",
        ),
        activityResponse = qualifiedActivityResponse(),
        dataPlane = DataPlaneUiModel(
            activeMode = "Memory-only simulator",
            pilotGateLabel = "REAL DATA LOCKED",
            receiptState = "No watch batch received",
            forecastAuditState = "Simulator commitment only",
            integrityDetail = "Encrypted storage, authenticated transport, and restart recovery are tested core components; this screen is not yet wired to personal data.",
        ),
        evidence = listOf(
            EvidenceUiModel(
                id = "hrv",
                label = "Overnight HRV",
                value = "31 ms",
                comparison = "18% below the simulated matched baseline",
                direction = EvidenceDirection.CONTRIBUTES_TO_CHANGE,
                quality = 94,
                provenance = "Simulator IBI · 01:12–05:48 · fixture window 04",
            ),
            EvidenceUiModel(
                id = "resting-heart-rate",
                label = "Resting heart rate",
                value = "67 bpm",
                comparison = "6 bpm above the simulated overnight reference",
                direction = EvidenceDirection.CONTRIBUTES_TO_CHANGE,
                quality = 96,
                provenance = "Simulator PPG · motion-filtered fixture",
            ),
            EvidenceUiModel(
                id = "sleep",
                label = "Sleep continuity",
                value = "82%",
                comparison = "Within the simulated expected range",
                direction = EvidenceDirection.SUPPORTS_STEADY,
                quality = 89,
                provenance = "Simulator sleep session · 7 h 04 min",
            ),
            EvidenceUiModel(
                id = "skin-temp",
                label = "Skin temperature",
                value = "+0.3°C",
                comparison = "Context only · slightly above fixture baseline",
                direction = EvidenceDirection.CONTEXT_ONLY,
                quality = 81,
                provenance = "Simulator wrist temperature · ambient adjusted",
            ),
        ),
        trend = listOf(
            TrendPointUiModel("Sat", 0.10f, -0.65f, 0.65f),
            TrendPointUiModel("Sun", -0.18f, -0.65f, 0.65f),
            TrendPointUiModel("Mon", 0.22f, -0.65f, 0.65f),
            TrendPointUiModel("Tue", 0.36f, -0.65f, 0.65f),
            TrendPointUiModel("Wed", 0.44f, -0.65f, 0.65f),
            TrendPointUiModel("Thu", 0.73f, -0.65f, 0.65f),
            TrendPointUiModel("Today", 1.04f, -0.65f, 0.65f),
        ),
        qualitySignals = listOf(
            QualitySignalUiModel("Wear time", 96, "22.4 hours captured"),
            QualitySignalUiModel("Sensor contact", 93, "Consistent overnight"),
            QualitySignalUiModel("Motion cleanliness", 87, "87/100 clean after filtering"),
        ),
        timeline = listOf(
            TimelineItemUiModel(
                id = "insight-1",
                timeLabel = "07:18",
                title = "Simulator interpretation updated",
                detail = "Fixture only · internal evidence score 72/100 · no live health inference",
                kind = TimelineKind.INSIGHT,
            ),
            TimelineItemUiModel(
                id = "measurement-1",
                timeLabel = "06:51",
                title = "Simulated overnight analysis",
                detail = "4.1 hours high-quality fixture data",
                kind = TimelineKind.MEASUREMENT,
            ),
            TimelineItemUiModel(
                id = "context-1",
                timeLabel = "Yesterday",
                title = "Example context event",
                detail = "Synthetic medication timing · not personal data",
                kind = TimelineKind.CONTEXT,
            ),
            TimelineItemUiModel(
                id = "system-1",
                timeLabel = "Mon",
                title = "Simulator baseline fixture",
                detail = "30 effective days · generated test history",
                kind = TimelineKind.SYSTEM,
            ),
        ),
        fiveSecondSummary = FiveSecondSummaryUiModel(
            whatChanged = "One simulated sensor family moved",
            evidence = "72 / 100 internal evidence score",
            nextStep = "Record context, then remeasure",
        ),
        conflictDesk = listOf(
            ConflictDeskItemUiModel(
                id = "conflict-seq-1",
                title = "Same-sequence native-version conflict",
                detail = "Simulator Health Connect fixture rejected delete (sequence 1, opaque delete-other) against live record (sequence 1, opaque native-1). The live record was retained.",
                action = "CONFLICT REJECTED · record retained",
            ),
        ),
        featureInspector = emptyList(),
        forecastAudit = listOf(
            ForecastAuditEventUiModel(
                id = "audit-committed",
                state = "COMMITTED HIDDEN",
                timeLabel = "Mon 21:00",
                detail = "Time-stamped simulator forecast sealed before check-in. Probability is absent from the locked view.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-context",
                state = "PRE-REVEAL CONTEXT",
                timeLabel = "Now",
                detail = "Complete check-in would store context against the sealed commitment. Partial answers stay missing.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-reveal",
                state = "REVEAL",
                timeLabel = "After check-in",
                detail = "Reveal can show only the already-committed unvalidated fixture. It cannot rewrite the snapshot.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-outcome",
                state = "OUTCOME DUE",
                timeLabel = "+72h to +73h",
                detail = "The binary target-window check-in is not yet knowable. Missing outcomes stay missing.",
            ),
        ),
    )

    private fun qualifiedActivityResponse() = ActivityResponseUiModel(
        status = ActivityResponseStatus.QUALIFIED_DESCRIPTIVE,
        protocolLabel = "SIMULATED MATCHED WALK · RESEARCH ONLY",
        steps = 1_000L,
        distanceKilometres = 0.9,
        activeMinutes = 10,
        averageHeartRateBpm = 125,
        persistentPeakHeartRateBpm = 130,
        recoveryDropAt60SecondsBpm = 20,
        matchedWorkloadCardiacCost = 65.0,
        personalBandDurationLabel = "6m 40s middle · 3m 20s upper",
        coverageLabel = "HR 100% · workload 100% · recovery 3/3 points",
        gapLabel = "No gaps in the simulated active + recovery interval",
        comparisonLabel = "Within the qualified fixture's personal range · no cross-family response-change rule met",
        reason = "Descriptive workload response only. It does not establish fitness, illness, readiness, cause, or medical clearance.",
    )

    private fun learningActivityResponse() = qualifiedActivityResponse().copy(
        status = ActivityResponseStatus.LEARNING,
        comparisonLabel = "Learning · 11/12 matched fixture sessions; the 28-day span gate is also required",
        reason = "Current fixture features are shown descriptively, but personal response comparison is withheld until the reference matures.",
    )

    private fun abstainedActivityResponse() = ActivityResponseUiModel(
        status = ActivityResponseStatus.ABSTAINED,
        protocolLabel = "SIMULATED MATCHED WALK · RESEARCH ONLY",
        steps = null,
        distanceKilometres = null,
        activeMinutes = null,
        averageHeartRateBpm = null,
        persistentPeakHeartRateBpm = null,
        recoveryDropAt60SecondsBpm = null,
        matchedWorkloadCardiacCost = null,
        personalBandDurationLabel = null,
        coverageLabel = "HR 58% · workload 72% · fixed recovery incomplete",
        gapLabel = "Off-wrist and motion-contaminated fixture time is explicitly missing",
        comparisonLabel = "ABSTAINED · no workload-response comparison",
        reason = "Missing time is not inactivity or recovery. Low-quality values are absent rather than displayed as zero or normal.",
    )

    private fun concernHeldActivityResponse() = ActivityResponseUiModel(
        status = ActivityResponseStatus.HUMAN_CONCERN_HOLD,
        protocolLabel = "SIMULATOR-SESSION HUMAN PRIORITY",
        steps = null,
        distanceKilometres = null,
        activeMinutes = null,
        averageHeartRateBpm = null,
        persistentPeakHeartRateBpm = null,
        recoveryDropAt60SecondsBpm = null,
        matchedWorkloadCardiacCost = null,
        personalBandDurationLabel = null,
        coverageLabel = "Wearable analytics withheld",
        gapLabel = "Coverage and gap detail is deprioritized while the concern hold is active",
        comparisonLabel = "HUMAN CONCERN HOLD",
        reason = "How the person feels takes priority. Exercise data cannot reassure, provide medical clearance, or resolve this hold.",
    )


    private fun contextSnapshotSha256(detail: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(detail.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** Rewrites only the rows the ledger actually advanced; later rows stay pending. */
    private fun revealedAuditTrail(
        base: List<ForecastAuditEventUiModel>,
        view: RevealedForecastView,
    ): List<ForecastAuditEventUiModel> = base.map { row ->
        when (row.id) {
            "audit-context" -> row.copy(
                timeLabel = "Stored",
                detail = "The complete pre-reveal check-in is durably appended against the sealed commitment.",
            )

            "audit-reveal" -> row.copy(
                timeLabel = "Revealed",
                detail = "Ledger state ${view.state.name.replace('_', ' ')} released the already-committed payload for ${view.forecastId}. The snapshot was not recomputed.",
            )

            else -> row
        }
    }

    private fun forecastAuditTrail(result: SimulatorPipelineResult): List<ForecastAuditEventUiModel> {
        val view = result.prospectiveView as? LockedForecastView ?: return emptyList()
        require(view.state == ProspectiveForecastState.COMMITTED_HIDDEN ||
            view.state == ProspectiveForecastState.PRE_REVEAL_CHECKIN_STORED)
        return listOf(
            ForecastAuditEventUiModel(
                id = "audit-committed",
                state = "COMMITTED HIDDEN",
                timeLabel = "At commitment",
                detail = "Simulator forecast ${view.forecastId} is sealed on the prospective ledger. Probability is absent from this locked view. Snapshot ${view.canonicalFeatureSnapshotSha256.take(12)}.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-context",
                state = "PRE-REVEAL CONTEXT",
                timeLabel = "Not stored",
                detail = "A complete check-in has not been appended. Partial answers stay missing and cannot rewrite the sealed snapshot.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-reveal",
                state = "REVEAL",
                timeLabel = "Blocked",
                detail = "Reveal is refused until a durable pre-reveal check-in exists. The operator desk cannot display an uncommitted recomputation.",
            ),
            ForecastAuditEventUiModel(
                id = "audit-outcome",
                state = "OUTCOME DUE",
                timeLabel = "+72h to +73h",
                detail = "The binary target-window check-in is not yet knowable. Missing outcomes stay missing.",
            ),
        )
    }

    private fun inspectorRows(result: SimulatorPipelineResult): List<FeatureInspectorRowUiModel> {
        val digestPrefix = result.targetFeatures.canonicalSha256().take(12)
        val snapshotQuality = (result.targetFeatures.quality * 100.0).toInt().coerceIn(0, 100)
        return result.targetFeatures.featureValues.toSortedMap().map { (featureId, feature) ->
            FeatureInspectorRowUiModel(
                featureId = featureId,
                version = feature.featureVersion,
                windowLabel = "Cutoff-anchored source window",
                quality = snapshotQuality,
                snapshotSha256Prefix = digestPrefix,
                provenanceLabel = feature.provenanceIds.joinToString() +
                    " · same sealed snapshot digest",
            )
        }
    }

    private companion object {
        const val SIMULATOR_CONCERN_ID = "sim-concern-1"
        const val SIMULATOR_SUBJECT = "sim-subject-1"
        const val SIMULATOR_SESSION = "sim-session-1"
        const val SIMULATOR_PARTICIPANT = "sim-participant-1"
        const val SIMULATOR_CONSENT_GENERATION = 1L
    }
}
