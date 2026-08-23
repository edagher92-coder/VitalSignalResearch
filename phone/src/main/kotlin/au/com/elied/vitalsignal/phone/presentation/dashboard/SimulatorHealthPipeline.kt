package au.com.elied.vitalsignal.phone.presentation.dashboard

import au.com.elied.vitalsignal.analytics.ForecastEstimate
import au.com.elied.vitalsignal.analytics.ForecastFeatureSnapshot
import au.com.elied.vitalsignal.analytics.ForecastFeatureValue
import au.com.elied.vitalsignal.analytics.ForecastTrainingCase
import au.com.elied.vitalsignal.analytics.ForecastTrainingCaseReceiptVerifier
import au.com.elied.vitalsignal.analytics.IndependentEvidenceFamily
import au.com.elied.vitalsignal.analytics.InterpretationAssessment
import au.com.elied.vitalsignal.analytics.InterpretationEngine
import au.com.elied.vitalsignal.analytics.NormalizedContributionDirection
import au.com.elied.vitalsignal.analytics.PersonalForecastEngine
import au.com.elied.vitalsignal.analytics.PersistenceEpisodeEvidence
import au.com.elied.vitalsignal.analytics.PersistenceEvidenceEvaluator
import au.com.elied.vitalsignal.analytics.PersistenceEvidenceVerifier
import au.com.elied.vitalsignal.analytics.QualityInputs
import au.com.elied.vitalsignal.analytics.RobustBaselineEngine
import au.com.elied.vitalsignal.analytics.SafetyDecision
import au.com.elied.vitalsignal.analytics.SafetyGateInput
import au.com.elied.vitalsignal.analytics.SafetyPolicyEngine
import au.com.elied.vitalsignal.analytics.SignalQualityEngine
import au.com.elied.vitalsignal.analytics.canonicalSha256
import au.com.elied.vitalsignal.audit.ForecastLedgerMutationResult
import au.com.elied.vitalsignal.audit.InMemoryForecastAuditJournal
import au.com.elied.vitalsignal.audit.LockedForecastView
import au.com.elied.vitalsignal.audit.PreRevealContextCheckIn
import au.com.elied.vitalsignal.audit.ProspectiveForecastLedger
import au.com.elied.vitalsignal.audit.ProspectiveForecastView
import au.com.elied.vitalsignal.audit.RevealedForecastView
import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.AcquisitionOrigin
import au.com.elied.vitalsignal.model.conservativeAcquisitionProfile
import au.com.elied.vitalsignal.model.BaselineDeviation
import au.com.elied.vitalsignal.model.BaselineContextKey
import au.com.elied.vitalsignal.model.BaselineKey
import au.com.elied.vitalsignal.model.HealthInsight
import au.com.elied.vitalsignal.model.MetricWindow
import au.com.elied.vitalsignal.model.PersonalBaseline
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

data class SimulatorPipelineResult(
    val safetyDecision: SafetyDecision,
    val quality: SignalQuality,
    val baselines: List<PersonalBaseline>,
    val deviations: List<BaselineDeviation>,
    val interpretationAssessment: InterpretationAssessment,
    val insight: HealthInsight?,
    val forecastEstimate: ForecastEstimate,
    val effectiveDays: Int,
    val targetFeatures: ForecastFeatureSnapshot,
    val prospectiveView: ProspectiveForecastView? = null,
    val ledgerReason: String? = null,
)

/**
 * A reveal can only ever return the payload the ledger already committed. It is
 * refused rather than recomputed when the prospective chronology is not met.
 */
sealed interface ForecastRevealOutcome {
    data class Revealed(val view: RevealedForecastView) : ForecastRevealOutcome
    data class Refused(val reason: String) : ForecastRevealOutcome
}

/** Exact-value allowlist for deterministic simulator fixtures only; never a production verifier. */
private class SimulatorFixturePersistenceVerifier(
    approvedEvidence: Set<PersistenceEpisodeEvidence>,
) : PersistenceEvidenceVerifier {
    private val approvedEvidence = approvedEvidence.toSet()

    override fun verify(evidence: PersistenceEpisodeEvidence): Boolean = evidence in approvedEvidence
}

/** Seeded end-to-end simulator using the same core quality and model classes as the pilot. */
class SimulatorHealthPipeline(
    private val qualityEngine: SignalQualityEngine = SignalQualityEngine(),
    private val baselineEngine: RobustBaselineEngine = RobustBaselineEngine(),
    private val interpretationEngine: InterpretationEngine = InterpretationEngine(
        persistenceEvaluator = PersistenceEvidenceEvaluator(
            verifier = SimulatorFixturePersistenceVerifier(DEVELOPING_PERSISTENCE_EVIDENCE.toSet()),
        ),
    ),
    private val safetyPolicy: SafetyPolicyEngine = SafetyPolicyEngine(),
    private val forecastEngine: PersonalForecastEngine = PersonalForecastEngine(
        trainingCaseReceiptVerifier = ForecastTrainingCaseReceiptVerifier { trainingCase ->
            trainingCase.verificationReceiptId ==
                simulatorBoundReceipt(trainingCase.caseBindingSha256)
        },
    ),
    private val forecastLedger: ProspectiveForecastLedger = ProspectiveForecastLedger(
        InMemoryForecastAuditJournal(),
    ),
) {
    fun evaluate(
        scenario: SimulationScenario,
        userConcernReported: Boolean = false,
    ): SimulatorPipelineResult {
        val effectiveDays = if (scenario == SimulationScenario.LEARNING) 13 else 30
        val quality = qualityEngine.score(qualityInputsFor(scenario))
        val now = simulatorNow(effectiveDays)
        val baselines = metrics.mapNotNull { metric ->
            baselineEngine.fit(
                key = BaselineKey(metric, MATCHED_HOUR, ActivityState.RESTING, SIMULATOR_CONTEXT),
                windows = baselineWindows(metric, effectiveDays),
                nowEpochMillis = now,
            )
        }
        val baselineByMetric = baselines.associateBy { it.key.metric }
        val currentWindows = currentWindows(scenario, quality, now)
        val deviations = currentWindows.mapNotNull { window ->
            baselineByMetric[window.metric]?.let { baselineEngine.compare(window, it) }
        }
        val interpretationAssessment = interpretationEngine.interpret(
            deviations = deviations,
            coverageWindows = currentWindows,
            nowEpochMillis = now,
            priorPersistenceEvidence = if (scenario == SimulationScenario.DEVELOPING) {
                DEVELOPING_PERSISTENCE_EVIDENCE
            } else {
                emptyList()
            },
        )
        val insight = interpretationAssessment.insight
        val baselineMaturity = baselines.minOfOrNull { it.maturity } ?: 0.0
        val baselineSamples = baselines.minOfOrNull { it.sampleCount } ?: 0
        val safetyDecision = safetyPolicy.evaluate(
            SafetyGateInput(
                dataQuality = quality.score,
                baselineMaturity = baselineMaturity,
                baselineSampleCount = baselineSamples,
                independentCoherentFamilies = interpretationAssessment.independentCoherentFamilyCount,
                independentCoherentAcquisitionGroups =
                    interpretationAssessment.independentCoherentAcquisitionGroupCount,
                expectedQualifiedFamilies = EXPECTED_SIMULATOR_FAMILIES,
                availableQualifiedFamilies = interpretationAssessment.availableQualifiedFamilies,
                conflictingFamilies = interpretationAssessment.conflictingFamilies,
                intervalWidth = null,
                userConcernReported = userConcernReported,
            ),
        )
        val targetFeatures = ForecastFeatureSnapshot(
            id = "sim-features-${scenario.name.lowercase()}",
            cutoffEpochMillis = now,
            featureSchema = PersonalForecastEngine.SIMULATOR_FEATURE_SCHEMA,
            featureValues = mapOf(
                "cardio-autonomic" to simulatorFeature(
                    id = "cardio-autonomic",
                    value = deviations
                        .filter { it.metric in cardioMetrics }
                        .maxOfOrNull { abs(it.robustZ) }
                        .orZero(),
                    cutoff = now,
                    provenanceId = "sim:current:cardio",
                ),
                "sleep" to simulatorFeature(
                    id = "sleep",
                    value = deviations
                        .firstOrNull { it.metric == SensorMetric.SLEEP_DURATION }
                        ?.robustZ
                        .orZero(),
                    cutoff = now,
                    provenanceId = "sim:current:sleep",
                ),
            ),
            quality = quality.score,
        )
        val rawEstimate = if (userConcernReported) {
            ForecastEstimate(
                state = au.com.elied.vitalsignal.analytics.ForecastModelState.ABSTAINED,
                forecast = null,
                validCaseCount = 0,
                effectiveCaseWeight = 0.0,
                reason = "Human concern overrides wearable forecast",
            )
        } else {
            forecastEngine.forecast(
                history = forecastHistory(
                    now = now,
                    count = if (scenario == SimulationScenario.LEARNING) 12 else 40,
                ),
                target = targetFeatures,
                createdAtEpochMillis = now,
                endpoint = PersonalForecastEngine.SIMULATOR_72_HOUR_POINT_ENDPOINT,
            )
        }
        val sealed = sealThroughLedger(rawEstimate, targetFeatures, now)

        return SimulatorPipelineResult(
            safetyDecision = safetyDecision,
            quality = quality,
            baselines = baselines,
            deviations = deviations,
            interpretationAssessment = interpretationAssessment,
            insight = insight,
            forecastEstimate = sealed.estimate,
            effectiveDays = effectiveDays,
            targetFeatures = targetFeatures,
            prospectiveView = sealed.view,
            ledgerReason = sealed.reason,
        )
    }


    /**
     * Stores the pre-reveal context check-in and then reveals, so the displayed
     * probability comes from the committed record instead of a fresh estimate.
     */
    fun revealCommittedForecast(
        result: SimulatorPipelineResult,
        contextSnapshotSha256: String,
    ): ForecastRevealOutcome {
        result.prospectiveView as? LockedForecastView
            ?: return ForecastRevealOutcome.Refused(
                result.ledgerReason ?: "No committed forecast is available to reveal",
            )
        val forecast = result.forecastEstimate.forecast
            ?: return ForecastRevealOutcome.Refused("No committed forecast payload is available")

        val checkInAtEpochMillis = forecast.createdAtEpochMillis + 1L
        val stored = forecastLedger.storePreRevealCheckIn(
            PreRevealContextCheckIn(
                eventId = "k-${forecast.id}-${contextSnapshotSha256.take(12)}",
                forecastId = forecast.id,
                recordedAtEpochMillis = checkInAtEpochMillis,
                contextSnapshotSha256 = contextSnapshotSha256,
            ),
        )
        mutationRefusal(stored)?.let { return ForecastRevealOutcome.Refused(it) }

        val revealed = forecastLedger.reveal(
            eventId = "r-${forecast.id}",
            forecastId = forecast.id,
            revealedAtEpochMillis = checkInAtEpochMillis + 1L,
        )
        mutationRefusal(revealed)?.let { return ForecastRevealOutcome.Refused(it) }

        val view = revealed.view as? RevealedForecastView
            ?: return ForecastRevealOutcome.Refused(
                "The ledger did not return a revealed projection",
            )
        return ForecastRevealOutcome.Revealed(view)
    }

    private fun mutationRefusal(result: ForecastLedgerMutationResult): String? = when (result) {
        is ForecastLedgerMutationResult.Applied,
        is ForecastLedgerMutationResult.Idempotent,
        -> null
        is ForecastLedgerMutationResult.Rejected -> result.reason
        is ForecastLedgerMutationResult.Unavailable -> result.reason
    }

    private data class SealedForecast(
        val estimate: ForecastEstimate,
        val view: ProspectiveForecastView?,
        val reason: String?,
    )

    private fun sealThroughLedger(
        estimate: ForecastEstimate,
        targetFeatures: ForecastFeatureSnapshot,
        nowEpochMillis: Long,
    ): SealedForecast {
        val forecast = estimate.forecast
        if (forecast == null) {
            return SealedForecast(estimate, view = null, reason = estimate.reason)
        }
        val digest = targetFeatures.canonicalSha256()
        if (forecast.featureSnapshotHash != digest) {
            return SealedForecast(
                estimate = estimate.copy(
                    state = au.com.elied.vitalsignal.analytics.ForecastModelState.ABSTAINED,
                    forecast = null,
                    validCaseCount = 0,
                    effectiveCaseWeight = 0.0,
                    reason = "Forecast feature digest did not match the committed snapshot",
                ),
                view = null,
                reason = "Forecast feature digest did not match the committed snapshot",
            )
        }
        val mutation = forecastLedger.commit(
            eventId = "c-${forecast.id}",
            forecast = forecast,
            canonicalFeatureSnapshotSha256 = digest,
            nowEpochMillis = nowEpochMillis,
        )
        return when (mutation) {
            is ForecastLedgerMutationResult.Applied,
            is ForecastLedgerMutationResult.Idempotent,
            -> {
                val view = mutation.view
                if (view !is LockedForecastView) {
                    SealedForecast(
                        estimate = estimate.copy(
                            state = au.com.elied.vitalsignal.analytics.ForecastModelState.ABSTAINED,
                            forecast = null,
                            validCaseCount = 0,
                            effectiveCaseWeight = 0.0,
                            reason = "Committed forecast must remain locked until check-in and reveal",
                        ),
                        view = view,
                        reason = "Committed forecast must remain locked until check-in and reveal",
                    )
                } else {
                    SealedForecast(estimate = estimate, view = view, reason = null)
                }
            }
            is ForecastLedgerMutationResult.Rejected -> SealedForecast(
                estimate = estimate.copy(
                    state = au.com.elied.vitalsignal.analytics.ForecastModelState.ABSTAINED,
                    forecast = null,
                    validCaseCount = 0,
                    effectiveCaseWeight = 0.0,
                    reason = mutation.reason,
                ),
                view = mutation.view,
                reason = mutation.reason,
            )
            is ForecastLedgerMutationResult.Unavailable -> SealedForecast(
                estimate = estimate.copy(
                    state = au.com.elied.vitalsignal.analytics.ForecastModelState.ABSTAINED,
                    forecast = null,
                    validCaseCount = 0,
                    effectiveCaseWeight = 0.0,
                    reason = mutation.reason,
                ),
                view = mutation.view,
                reason = mutation.reason,
            )
        }
    }

    private fun qualityInputsFor(scenario: SimulationScenario): QualityInputs =
        if (scenario == SimulationScenario.LOW_QUALITY) {
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 610,
                validSamples = 520,
                onBodyFraction = 0.42,
                motionFraction = 0.61,
                clippingFraction = 0.09,
                timestampContinuity = 0.58,
            )
        } else {
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 970,
                validSamples = 960,
                onBodyFraction = 0.96,
                motionFraction = 0.04,
                clippingFraction = 0.01,
                timestampContinuity = 0.98,
            )
        }

    private fun baselineWindows(metric: SensorMetric, days: Int): List<MetricWindow> =
        (0 until days).map { day ->
            val start = SIMULATOR_BASE_EPOCH_MILLIS +
                day * DAY_MILLIS + MATCHED_HOUR * HOUR_MILLIS
            val end = start + 30L * 60L * 1_000L
            MetricWindow(
                id = "sim-baseline-${metric.name}-$day",
                metric = metric,
                source = SensorSource.SIMULATOR,
                startEpochMillis = start,
                endEpochMillis = end,
                value = baselineValue(metric, day),
                quality = GOOD_QUALITY,
                activityState = ActivityState.RESTING,
                localHourBucket = MATCHED_HOUR,
                localDateIso = localDateAt(end),
                localOffsetMinutes = LOCAL_OFFSET_MINUTES,
                baselineContext = SIMULATOR_CONTEXT,
                provenanceIds = listOf("sim-source-${metric.name}-$day"),
                acquisitionProfile = conservativeAcquisitionProfile(
                    metric,
                    SensorSource.SIMULATOR,
                    listOf("sim-source-${metric.name}-$day"),
                ),
            )
        }

    private fun currentWindows(
        scenario: SimulationScenario,
        quality: SignalQuality,
        now: Long,
    ): List<MetricWindow> = metrics.map { metric ->
        MetricWindow(
            id = "sim-current-${scenario.name.lowercase()}-${metric.name}",
            metric = metric,
            source = SensorSource.SIMULATOR,
            startEpochMillis = now - 30L * 60L * 1_000L,
            endEpochMillis = now,
            value = currentValue(metric, scenario),
            quality = quality,
            activityState = ActivityState.RESTING,
            localHourBucket = MATCHED_HOUR,
            localDateIso = localDateAt(now),
            localOffsetMinutes = LOCAL_OFFSET_MINUTES,
            baselineContext = SIMULATOR_CONTEXT,
            provenanceIds = listOf("sim-fixture-${scenario.name.lowercase()}-${metric.name}"),
            acquisitionProfile = conservativeAcquisitionProfile(
                metric,
                SensorSource.SIMULATOR,
                listOf("sim-fixture-${scenario.name.lowercase()}-${metric.name}"),
            ),
        )
    }

    private fun simulatorNow(effectiveDays: Int): Long =
        SIMULATOR_BASE_EPOCH_MILLIS +
            (effectiveDays + 2L) * DAY_MILLIS +
            MATCHED_HOUR * HOUR_MILLIS +
            30L * 60L * 1_000L

    private fun localDateAt(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atOffset(SIMULATOR_OFFSET)
            .toLocalDate()
            .toString()

    private fun baselineValue(metric: SensorMetric, day: Int): Double = when (metric) {
        SensorMetric.HEART_RATE -> 60.0 + (day % 3 - 1)
        SensorMetric.HRV_RMSSD -> 45.0 + (day % 5 - 2)
        SensorMetric.SKIN_TEMPERATURE -> 33.0 + (day % 3 - 1) * 0.05
        SensorMetric.RESPIRATORY_RATE -> 14.0 + (day % 3 - 1) * 0.2
        SensorMetric.SLEEP_DURATION -> 420.0 + (day % 5 - 2) * 5.0
        else -> error("Unsupported simulator metric: $metric")
    }

    private fun currentValue(metric: SensorMetric, scenario: SimulationScenario): Double {
        val steady = baselineValue(metric, 1)
        if (scenario != SimulationScenario.DEVELOPING) return steady
        return when (metric) {
            SensorMetric.HEART_RATE -> 69.0
            SensorMetric.HRV_RMSSD -> 30.0
            else -> steady
        }
    }

    private fun forecastHistory(now: Long, count: Int = 40): List<ForecastTrainingCase> =
        (1..count).map { index ->
        // Six-hour spacing keeps all deterministic fixture windows non-negative,
        // unique and prospectively resolved even in the 13-day learning scenario.
        val cutoff = now - (index + 12L) * 6L * HOUR_MILLIS
        val unsigned = ForecastTrainingCase(
            caseId = "sim-case-$index",
            endpoint = PersonalForecastEngine.SIMULATOR_72_HOUR_POINT_ENDPOINT,
            features = ForecastFeatureSnapshot(
                id = "sim-history-$index",
                cutoffEpochMillis = cutoff,
                featureSchema = PersonalForecastEngine.SIMULATOR_FEATURE_SCHEMA,
                featureValues = mapOf(
                    // Two supported neighborhoods exercise both the steady and
                    // developing fixtures without pretending to be user data.
                    "cardio-autonomic" to simulatorFeature(
                        id = "cardio-autonomic",
                        value = if (index % 2 == 0) 7.5 else 0.5,
                        cutoff = cutoff,
                        provenanceId = "sim:history:$index:cardio",
                    ),
                    "sleep" to simulatorFeature(
                        id = "sleep",
                        value = (index % 3 - 1) * 0.3,
                        cutoff = cutoff,
                        provenanceId = "sim:history:$index:sleep",
                    ),
                ),
                quality = 0.92,
            ),
            observedOutcome = if (index % 3 == 0) 1.0 else 0.0,
            resolvedAtEpochMillis = cutoff + 73L * HOUR_MILLIS,
            outcomeObservationId = "sim-outcome-$index",
            outcomeRecordSha256 = "%064x".format(index),
            verificationReceiptId = "sim-receipt:unsigned",
        )
        unsigned.copy(verificationReceiptId = simulatorBoundReceipt(unsigned.caseBindingSha256))
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun simulatorFeature(
        id: String,
        value: Double,
        cutoff: Long,
        provenanceId: String,
    ) = ForecastFeatureValue(
        featureId = id,
        featureVersion = "sim-v1",
        standardizedValue = value,
        sourceWindowStartEpochMillis = cutoff - DAY_MILLIS,
        sourceWindowEndEpochMillis = cutoff,
        provenanceIds = listOf(provenanceId),
    )

    private companion object {
        const val MATCHED_HOUR = 7
        const val LOCAL_OFFSET_MINUTES = 600
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        val SIMULATOR_OFFSET: ZoneOffset = ZoneOffset.ofTotalSeconds(LOCAL_OFFSET_MINUTES * 60)
        val SIMULATOR_BASE_EPOCH_MILLIS: Long = LocalDate.of(2026, 7, 1)
            .atStartOfDay(SIMULATOR_OFFSET)
            .toInstant()
            .toEpochMilli()
        val DEVELOPING_NOW_MILLIS: Long = SIMULATOR_BASE_EPOCH_MILLIS +
            32L * DAY_MILLIS +
            MATCHED_HOUR * HOUR_MILLIS +
            30L * 60L * 1_000L
        val SIMULATOR_CONTEXT = BaselineContextKey(
            deviceGeneration = "fixture-watch-v1",
            firmwareGeneration = "fixture-firmware-v1",
            acquisitionProtocolVersion = "simulator-passive-v1",
            environmentFingerprintSha256 = "a".repeat(64),
        )
        val GOOD_QUALITY = SignalQuality(
            score = 0.96,
            coverage = 0.97,
            contact = 0.96,
            motionContamination = 0.04,
            validity = 0.96,
            clipping = 0.01,
            timestampContinuity = 0.98,
            evaluatorVersion = "simulator-quality-v2",
        )
        val metrics = listOf(
            SensorMetric.HEART_RATE,
            SensorMetric.HRV_RMSSD,
            SensorMetric.SKIN_TEMPERATURE,
            SensorMetric.RESPIRATORY_RATE,
            SensorMetric.SLEEP_DURATION,
        )
        val cardioMetrics = setOf(
            SensorMetric.HEART_RATE,
            SensorMetric.INTER_BEAT_INTERVAL,
            SensorMetric.HRV_RMSSD,
            SensorMetric.HRV_SDNN,
        )
        val EXPECTED_SIMULATOR_FAMILIES = setOf(
            IndependentEvidenceFamily.CARDIO_AUTONOMIC,
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
            IndependentEvidenceFamily.THERMAL_EXERTIONAL,
            IndependentEvidenceFamily.SLEEP_RESTORATION,
        )
        val DEVELOPING_PERSISTENCE_EVIDENCE = listOf(
            PersistenceEpisodeEvidence(
                episodeId = "sim-developing-prior-1",
                observedAtEpochMillis = DEVELOPING_NOW_MILLIS - 4L * 60L * 60L * 1_000L,
                familyDirections = mapOf(
                    IndependentEvidenceFamily.CARDIO_AUTONOMIC to
                        NormalizedContributionDirection.POSITIVE,
                ),
                acquisitionOriginsByFamily = mapOf(
                    IndependentEvidenceFamily.CARDIO_AUTONOMIC to setOf(
                        AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE,
                    ),
                ),
                quality = GOOD_QUALITY,
                provenanceIds = listOf("sim-persistence-source-1"),
                verificationArtifactId = "sim-fixture-verification-1",
            ),
            PersistenceEpisodeEvidence(
                episodeId = "sim-developing-prior-2",
                observedAtEpochMillis = DEVELOPING_NOW_MILLIS - 2L * 60L * 60L * 1_000L,
                familyDirections = mapOf(
                    IndependentEvidenceFamily.CARDIO_AUTONOMIC to
                        NormalizedContributionDirection.POSITIVE,
                ),
                acquisitionOriginsByFamily = mapOf(
                    IndependentEvidenceFamily.CARDIO_AUTONOMIC to setOf(
                        AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE,
                    ),
                ),
                quality = GOOD_QUALITY,
                provenanceIds = listOf("sim-persistence-source-2"),
                verificationArtifactId = "sim-fixture-verification-2",
            ),
        )
    }
}

internal fun simulatorBoundReceipt(caseBindingSha256: String): String =
    "sim-receipt:${caseBindingSha256.take(32)}"
