package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.BaselineDeviation
import au.com.elied.vitalsignal.model.AcquisitionDependencyProfile
import au.com.elied.vitalsignal.model.AcquisitionOrigin
import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.BaselineContextKey
import au.com.elied.vitalsignal.model.InsightSeverity
import au.com.elied.vitalsignal.model.InsightState
import au.com.elied.vitalsignal.model.MetricWindow
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.model.conservativeAcquisitionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationEngineTest {
    @Test
    fun heartAndRespiratoryProxiesSharingOpticalPathFailClosed() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(
            InterpretationAssessmentStatus.ACQUISITION_DEPENDENCY_LIMITED,
            assessment.status,
        )
        assertEquals(2, assessment.independentCoherentFamilyCount)
        assertEquals(1, assessment.independentCoherentAcquisitionGroupCount)
        assertEquals(null, assessment.insight)
        assertEquals(1, assessment.acquisitionComponents.size)
        assertEquals(
            setOf(
                IndependentEvidenceFamily.CARDIO_AUTONOMIC,
                IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
            ),
            assessment.acquisitionComponents.single().families,
        )

        val safety = SafetyPolicyEngine().evaluate(safetyInputFor(assessment))
        assertEquals(SafetyDisposition.ABSTAINED, safety.disposition)
        assertTrue(safety.reasonCodes.contains("shared-acquisition-dependency"))
    }

    @Test
    fun opticalAndThermalPathsCanCorroborateAsIndependentAcquisitions() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                deviation(SensorMetric.SKIN_TEMPERATURE, robustZ = 4.0),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.INTERPRETED, assessment.status)
        assertEquals(2, assessment.independentCoherentFamilyCount)
        assertEquals(2, assessment.independentCoherentAcquisitionGroupCount)
        assertEquals(InsightSeverity.WATCH, assessment.insight?.severity)
    }

    @Test
    fun explicitlyBoundReferenceRespirationCanIndependentlyCorroborateOpticalHeartRate() {
        val heartRate = deviation(SensorMetric.HEART_RATE, robustZ = 4.0)
        val respiration = deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0)
        val referenceProfile = AcquisitionDependencyProfile(
            primaryOrigin = AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE,
            evidenceProvenanceIds = respiration.provenanceIds,
            mappingVersion = "validated-reference-respiration-v1",
        )
        val approvedProfiles = mapOf(
            SensorMetric.HEART_RATE to conservativeAcquisitionProfile(
                SensorMetric.HEART_RATE,
                SensorSource.GALAXY_WATCH_ULTRA_2,
                heartRate.provenanceIds,
            ),
            SensorMetric.RESPIRATORY_RATE to referenceProfile,
        )
        val assessment = interpret(
            deviations = listOf(
                heartRate,
                respiration,
            ),
            nowEpochMillis = CURRENT_TIME,
            acquisitionProfiles = mapOf(
                SensorMetric.RESPIRATORY_RATE to referenceProfile,
            ),
            engine = InterpretationEngine(
                acquisitionProfileVerifier = AcquisitionDependencyProfileVerifier { window ->
                    window.acquisitionProfile == approvedProfiles[window.metric]
                },
            ),
        )

        assertEquals(InterpretationAssessmentStatus.INTERPRETED, assessment.status)
        assertEquals(2, assessment.independentCoherentAcquisitionGroupCount)
        assertEquals(InsightSeverity.WATCH, assessment.insight?.severity)
    }

    @Test
    fun unverifiedAcquisitionOverrideIsExcludedFromQualifiedCoverage() {
        val respiration = deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0)
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                respiration,
            ),
            nowEpochMillis = CURRENT_TIME,
            acquisitionProfiles = mapOf(
                SensorMetric.RESPIRATORY_RATE to AcquisitionDependencyProfile(
                    primaryOrigin = AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE,
                    evidenceProvenanceIds = respiration.provenanceIds,
                    mappingVersion = "unverified-override-v1",
                ),
            ),
        )

        assertEquals(
            setOf(IndependentEvidenceFamily.CARDIO_AUTONOMIC),
            assessment.availableQualifiedFamilies,
        )
        assertEquals(1, assessment.independentCoherentFamilyCount)
        assertEquals(
            SafetyDisposition.MEASUREMENT_UNAVAILABLE,
            SafetyPolicyEngine().evaluate(safetyInputFor(assessment)).disposition,
        )
    }

    @Test
    fun acquisitionDeclarationMustBeBoundToWindowProvenance() {
        val heartRate = deviation(SensorMetric.HEART_RATE, robustZ = 4.0)
        val unbound = AcquisitionDependencyProfile(
            primaryOrigin = AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE,
            evidenceProvenanceIds = listOf("not-in-window-provenance"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            coverageWindow(heartRate, CURRENT_TIME, unbound)
        }
    }

    @Test
    fun persistenceCannotCrossAnAcquisitionDependencyChange() {
        val directions = threeFamilyDirections()
        val currentOrigins = acquisitionOriginsForDirections(directions)
        val mismatchedOrigins = currentOrigins + (
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION to
                setOf(AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE)
            )
        val episode = persistenceEpisode(
            index = 1,
            observedAtEpochMillis = CURRENT_TIME - TWO_HOURS,
            directions = directions,
        ).copy(acquisitionOriginsByFamily = mismatchedOrigins)
        val result = PersistenceEvidenceEvaluator(PersistenceEvidenceVerifier { true }).evaluate(
            currentObservedAtEpochMillis = CURRENT_TIME,
            currentFamilyDirections = directions,
            currentAcquisitionOriginsByFamily = currentOrigins,
            currentProvenanceIds = listOf("current-a", "current-b", "current-c"),
            priorEpisodes = listOf(episode),
        )

        assertEquals(PersistenceEvidenceStatus.REJECTED, result.status)
        assertTrue(
            result.rejectionReasonCodes.contains(
                "persistence-acquisition-dependency-inconsistent",
            ),
        )
    }

    @Test
    fun alignedHeartRateAndHrvRemainOneIndependentFamily() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                deviation(SensorMetric.HRV_RMSSD, robustZ = -4.0),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.INTERPRETED, assessment.status)
        assertEquals(1, assessment.independentCoherentFamilyCount)
        assertEquals(InsightSeverity.INFORMATIONAL, assessment.insight?.severity)
    }

    @Test
    fun opposingHeartRateAndHrvDirectionsConflictAndSecondFamilyCannotCorroborate() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                // Higher HRV normalizes opposite to higher HR.
                deviation(SensorMetric.HRV_RMSSD, robustZ = 4.0),
                deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.CONFLICTING_EVIDENCE, assessment.status)
        assertEquals(setOf(IndependentEvidenceFamily.CARDIO_AUTONOMIC), assessment.conflictingFamilies)
        assertEquals(1, assessment.independentCoherentFamilyCount)
        assertEquals(null, assessment.insight)
        val cardio = assessment.familyAssessments.single {
            it.family == IndependentEvidenceFamily.CARDIO_AUTONOMIC
        }
        assertEquals(FamilyEvidenceDirection.CONFLICTING, cardio.direction)
        assertEquals(0.0, cardio.representativeContribution, 0.0)

        val safety = SafetyPolicyEngine().evaluate(
            SafetyGateInput(
                dataQuality = 0.95,
                baselineMaturity = 1.0,
                baselineSampleCount = 30,
                independentCoherentFamilies = assessment.independentCoherentFamilyCount,
                independentCoherentAcquisitionGroups =
                    assessment.independentCoherentAcquisitionGroupCount,
                expectedQualifiedFamilies = setOf(
                    IndependentEvidenceFamily.CARDIO_AUTONOMIC,
                    IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
                ),
                availableQualifiedFamilies = assessment.availableQualifiedFamilies,
                conflictingFamilies = assessment.conflictingFamilies,
                intervalWidth = 0.30,
            ),
        )
        assertEquals(SafetyDisposition.ABSTAINED, safety.disposition)
        assertTrue(safety.reasonCodes.contains("opposing-qualified-evidence-within-family"))
        assertTrue(safety.userMessage.contains("repeat", ignoreCase = true))
    }

    @Test
    fun normalQualifiedFamiliesCanProduceTypicalOnlyWhenCoverageIsComplete() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 0.5),
                deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 0.4),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.NO_QUALIFIED_DEVIATION, assessment.status)
        assertEquals(0, assessment.independentCoherentFamilyCount)
        assertEquals(2, assessment.availableQualifiedFamilies.size)
        val decision = SafetyPolicyEngine().evaluate(
            safetyInputFor(assessment),
        )
        assertEquals(SafetyDisposition.TYPICAL, decision.disposition)
    }

    @Test
    fun lowQualityAndPartialCoverageCannotBecomeTypical() {
        val poor = SignalQuality(
            score = 0.70,
            coverage = 0.70,
            contact = 0.70,
            motionContamination = 0.40,
            validity = 0.80,
            clipping = 0.08,
            timestampContinuity = 0.75,
        )
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 0.5),
                deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 0.4).copy(quality = poor),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(setOf(IndependentEvidenceFamily.CARDIO_AUTONOMIC), assessment.availableQualifiedFamilies)
        val decision = SafetyPolicyEngine().evaluate(
            safetyInputFor(assessment).copy(dataQuality = 0.95),
        )
        assertEquals(SafetyDisposition.MEASUREMENT_UNAVAILABLE, decision.disposition)
        assertTrue(decision.reasonCodes.contains("required-qualified-family-unavailable"))
    }

    @Test
    fun immatureBaselineCannotCreateVisibleInterpretation() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0).copy(baselineMaturity = 0.99),
                deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0).copy(baselineMaturity = 0.99),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.NO_QUALIFIED_DEVIATION, assessment.status)
        assertEquals(null, assessment.insight)
    }

    @Test
    fun opposingIndependentFamiliesDoNotBecomeCorroboratedPattern() {
        val assessment = interpret(
            deviations = listOf(
                deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
                deviation(SensorMetric.SLEEP_DURATION, robustZ = 4.0),
            ),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.INTERPRETED, assessment.status)
        assertTrue(assessment.conflictingFamilies.isEmpty())
        assertEquals(1, assessment.independentCoherentFamilyCount)
        assertEquals(InsightSeverity.INFORMATIONAL, assessment.insight?.severity)
    }

    @Test
    fun contextualMetricCannotVoteInInterpretationOrQualifiedCoverage() {
        val assessment = interpret(
            deviations = listOf(deviation(SensorMetric.AMBIENT_TEMPERATURE, robustZ = 8.0)),
            nowEpochMillis = CURRENT_TIME,
        )

        assertEquals(InterpretationAssessmentStatus.NO_QUALIFIED_DEVIATION, assessment.status)
        assertTrue(assessment.availableQualifiedFamilies.isEmpty())
        assertEquals(null, assessment.insight)
    }

    @Test
    fun verifiedChronologicalPersistenceCanBoostOnlyAConsistentChain() {
        val directions = threeFamilyDirections()
        val episodes = listOf(
            persistenceEpisode(1, CURRENT_TIME - FOUR_HOURS, directions),
            persistenceEpisode(2, CURRENT_TIME - TWO_HOURS, directions),
        )
        val engine = InterpretationEngine(
            PersistenceEvidenceEvaluator(PersistenceEvidenceVerifier { it in episodes }),
        )

        val assessment = interpret(
            engine = engine,
            deviations = threePositiveFamilies(),
            nowEpochMillis = CURRENT_TIME,
            priorPersistenceEvidence = episodes,
        )

        assertEquals(PersistenceEvidenceStatus.VERIFIED_PRIOR_CHAIN, assessment.persistence.status)
        assertEquals(3, assessment.persistence.qualifiedWindowCount)
        assertEquals(InsightSeverity.CHECK, assessment.insight?.severity)
    }

    @Test
    fun persistenceOnOneAcquisitionPathCannotCreateAnEscalatedInsightState() {
        val directions = mapOf(
            IndependentEvidenceFamily.CARDIO_AUTONOMIC to
                NormalizedContributionDirection.POSITIVE,
        )
        val episodes = listOf(
            persistenceEpisode(1, CURRENT_TIME - FOUR_HOURS, directions),
            persistenceEpisode(2, CURRENT_TIME - TWO_HOURS, directions),
        )
        val assessment = interpret(
            engine = InterpretationEngine(
                PersistenceEvidenceEvaluator(PersistenceEvidenceVerifier { it in episodes }),
            ),
            deviations = listOf(deviation(SensorMetric.HEART_RATE, robustZ = 4.0)),
            nowEpochMillis = CURRENT_TIME,
            priorPersistenceEvidence = episodes,
        )

        assertEquals(PersistenceEvidenceStatus.VERIFIED_PRIOR_CHAIN, assessment.persistence.status)
        assertEquals(1, assessment.independentCoherentAcquisitionGroupCount)
        assertEquals(InsightSeverity.INFORMATIONAL, assessment.insight?.severity)
        assertEquals(InsightState.PRELIMINARY, assessment.insight?.state)
    }

    @Test
    fun futureAndDuplicateEpisodesAreRejectedWithoutPersistenceBoost() {
        val directions = threeFamilyDirections()
        val future = persistenceEpisode(1, CURRENT_TIME, directions)
        val duplicate = persistenceEpisode(2, CURRENT_TIME - TWO_HOURS, directions)
        val evaluator = PersistenceEvidenceEvaluator(PersistenceEvidenceVerifier { true })

        val futureResult = evaluator.evaluate(
            CURRENT_TIME,
            directions,
            acquisitionOriginsForDirections(directions),
            listOf("current-a", "current-b", "current-c"),
            listOf(future),
        )
        val duplicateResult = evaluator.evaluate(
            CURRENT_TIME,
            directions,
            acquisitionOriginsForDirections(directions),
            listOf("current-a", "current-b", "current-c"),
            listOf(duplicate, duplicate),
        )

        assertEquals(PersistenceEvidenceStatus.REJECTED, futureResult.status)
        assertEquals(1, futureResult.qualifiedWindowCount)
        assertTrue(futureResult.rejectionReasonCodes.contains("persistence-not-strictly-prior"))
        assertEquals(PersistenceEvidenceStatus.REJECTED, duplicateResult.status)
        assertEquals(1, duplicateResult.qualifiedWindowCount)
        assertTrue(duplicateResult.rejectionReasonCodes.contains("persistence-duplicate-episode"))
    }

    @Test
    fun inconsistentOrUnverifiableEpisodesCannotBoostSeverity() {
        val directions = threeFamilyDirections()
        val inconsistent = persistenceEpisode(
            1,
            CURRENT_TIME - TWO_HOURS,
            directions + (
                IndependentEvidenceFamily.CARDIO_AUTONOMIC to
                    NormalizedContributionDirection.NEGATIVE
                ),
        )
        val validShapeButUnverified = listOf(
            persistenceEpisode(1, CURRENT_TIME - FOUR_HOURS, directions),
            persistenceEpisode(2, CURRENT_TIME - TWO_HOURS, directions),
        )
        val evaluator = PersistenceEvidenceEvaluator(PersistenceEvidenceVerifier { true })
        val inconsistentResult = evaluator.evaluate(
            CURRENT_TIME,
            directions,
            acquisitionOriginsForDirections(directions),
            listOf("current-a", "current-b", "current-c"),
            listOf(inconsistent),
        )

        val unverifiedAssessment = interpret(
            deviations = threePositiveFamilies(),
            nowEpochMillis = CURRENT_TIME,
            priorPersistenceEvidence = validShapeButUnverified,
        )

        assertEquals(PersistenceEvidenceStatus.REJECTED, inconsistentResult.status)
        assertTrue(
            inconsistentResult.rejectionReasonCodes.contains(
                "persistence-direction-or-family-inconsistent",
            ),
        )
        assertEquals(PersistenceEvidenceStatus.REJECTED, unverifiedAssessment.persistence.status)
        assertEquals(1, unverifiedAssessment.persistence.qualifiedWindowCount)
        assertEquals(InsightSeverity.WATCH, unverifiedAssessment.insight?.severity)
        assertFalse(unverifiedAssessment.insight?.severity == InsightSeverity.CHECK)
    }

    private fun interpret(
        deviations: List<BaselineDeviation>,
        nowEpochMillis: Long,
        priorPersistenceEvidence: List<PersistenceEpisodeEvidence> = emptyList(),
        engine: InterpretationEngine = InterpretationEngine(),
        acquisitionProfiles: Map<SensorMetric, AcquisitionDependencyProfile> = emptyMap(),
    ): InterpretationAssessment = engine.interpret(
        deviations = deviations,
        coverageWindows = deviations.map {
            coverageWindow(it, nowEpochMillis, acquisitionProfiles[it.metric])
        },
        nowEpochMillis = nowEpochMillis,
        priorPersistenceEvidence = priorPersistenceEvidence,
    )

    private fun coverageWindow(
        deviation: BaselineDeviation,
        nowEpochMillis: Long,
        acquisitionProfile: AcquisitionDependencyProfile? = null,
    ) = MetricWindow(
        id = deviation.windowId,
        metric = deviation.metric,
        source = if (acquisitionProfile?.primaryOrigin == AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE) {
            SensorSource.REFERENCE_DEVICE
        } else {
            SensorSource.GALAXY_WATCH_ULTRA_2
        },
        startEpochMillis = nowEpochMillis - 30L * 60L * 1_000L,
        endEpochMillis = nowEpochMillis,
        value = deviation.observed,
        quality = deviation.quality,
        activityState = ActivityState.RESTING,
        localHourBucket = 7,
        localDateIso = "2026-08-01",
        localOffsetMinutes = 600,
        baselineContext = TEST_CONTEXT,
        provenanceIds = deviation.provenanceIds,
        acquisitionProfile = acquisitionProfile ?: conservativeAcquisitionProfile(
            deviation.metric,
            SensorSource.GALAXY_WATCH_ULTRA_2,
            deviation.provenanceIds,
        ),
    )

    private fun safetyInputFor(assessment: InterpretationAssessment) = SafetyGateInput(
        dataQuality = 0.95,
        baselineMaturity = 1.0,
        baselineSampleCount = 30,
        independentCoherentFamilies = assessment.independentCoherentFamilyCount,
        independentCoherentAcquisitionGroups =
            assessment.independentCoherentAcquisitionGroupCount,
        expectedQualifiedFamilies = setOf(
            IndependentEvidenceFamily.CARDIO_AUTONOMIC,
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
        ),
        availableQualifiedFamilies = assessment.availableQualifiedFamilies,
        conflictingFamilies = assessment.conflictingFamilies,
        intervalWidth = 0.30,
    )

    private fun threePositiveFamilies() = listOf(
        deviation(SensorMetric.HEART_RATE, robustZ = 4.0),
        deviation(SensorMetric.RESPIRATORY_RATE, robustZ = 4.0),
        deviation(SensorMetric.SKIN_TEMPERATURE, robustZ = 4.0),
    )

    private fun threeFamilyDirections() = mapOf(
        IndependentEvidenceFamily.CARDIO_AUTONOMIC to NormalizedContributionDirection.POSITIVE,
        IndependentEvidenceFamily.RESPIRATORY_OXYGENATION to NormalizedContributionDirection.POSITIVE,
        IndependentEvidenceFamily.THERMAL_EXERTIONAL to NormalizedContributionDirection.POSITIVE,
    )

    private fun persistenceEpisode(
        index: Int,
        observedAtEpochMillis: Long,
        directions: Map<IndependentEvidenceFamily, NormalizedContributionDirection>,
    ) = PersistenceEpisodeEvidence(
        episodeId = "episode-$index",
        observedAtEpochMillis = observedAtEpochMillis,
        familyDirections = directions,
        acquisitionOriginsByFamily = acquisitionOriginsForDirections(directions),
        quality = GOOD_QUALITY,
        provenanceIds = listOf("prior-source-$index"),
        verificationArtifactId = "verification-$index",
    )

    private fun acquisitionOriginsForDirections(
        directions: Map<IndependentEvidenceFamily, NormalizedContributionDirection>,
    ): Map<IndependentEvidenceFamily, Set<AcquisitionOrigin>> = directions.keys.associateWith { family ->
        when (family) {
            IndependentEvidenceFamily.CARDIO_AUTONOMIC,
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
            -> setOf(AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION)

            IndependentEvidenceFamily.THERMAL_EXERTIONAL ->
                setOf(AcquisitionOrigin.WRIST_THERMAL_CONTACT)
            IndependentEvidenceFamily.SLEEP_RESTORATION -> setOf(
                AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
                AcquisitionOrigin.WRIST_INERTIAL_MOTION,
            )
            IndependentEvidenceFamily.ACTIVITY_LOAD ->
                setOf(AcquisitionOrigin.WRIST_INERTIAL_MOTION)
            IndependentEvidenceFamily.BODY_COMPOSITION ->
                setOf(AcquisitionOrigin.WRIST_ELECTRICAL_CONTACT)
            IndependentEvidenceFamily.CONTEXT -> setOf(AcquisitionOrigin.USER_REPORTED)
        }
    }

    private fun deviation(metric: SensorMetric, robustZ: Double) = BaselineDeviation(
        windowId = "window-$metric",
        metric = metric,
        observed = 1.0,
        expected = 0.0,
        robustZ = robustZ,
        direction = if (robustZ > 0) {
            au.com.elied.vitalsignal.model.DeviationDirection.HIGHER
        } else {
            au.com.elied.vitalsignal.model.DeviationDirection.LOWER
        },
        quality = GOOD_QUALITY,
        baselineMaturity = 1.0,
        baselineSampleCount = 30,
        provenanceIds = listOf("source-$metric"),
    )

    private companion object {
        // 2026-08-01 07:30 at the declared +10:00 local offset.
        const val CURRENT_TIME = 1_785_533_400_000L
        const val TWO_HOURS = 2L * 60L * 60L * 1_000L
        const val FOUR_HOURS = 4L * 60L * 60L * 1_000L
        val TEST_CONTEXT = BaselineContextKey(
            deviceGeneration = "fixture-watch-v1",
            firmwareGeneration = "fixture-firmware-v1",
            acquisitionProtocolVersion = "fixture-passive-v1",
            environmentFingerprintSha256 = "a".repeat(64),
        )
        val GOOD_QUALITY = SignalQuality(
            score = 0.95,
            coverage = 0.95,
            contact = 0.95,
            motionContamination = 0.04,
            validity = 0.96,
            clipping = 0.01,
            timestampContinuity = 0.98,
        )
    }
}
