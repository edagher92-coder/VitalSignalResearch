package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.AcquisitionOrigin
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyActivityTrendEngineTest {
    private val engine = DailyActivityTrendEngine()

    @Test
    fun finalizedQualifiedDayProducesSevenTwentyEightAndNinetyDayReferences() {
        val current = daily(
            daysAgo = 0,
            steps = 12_000L,
            gapReason = ActivityGapReason.CHARGING,
            gapSeconds = 3_456L,
        )

        val result = engine.assess(history(), current)

        assertEquals(DailyActivityTrendState.DESCRIPTIVE_TREND_AVAILABLE, result.state)
        assertEquals(listOf(7, 28, 90), result.references.map { it.windowDays })
        assertEquals(listOf(7, 28, 90), result.references.map { it.qualifiedDayCount })
        assertEquals(3_456L, result.gapSecondsByReason.getValue(ActivityGapReason.CHARGING))
        assertEquals(12_000.0, result.current!!.steps, 0.0)
        assertEquals(4, result.differencesFrom28DayMedian.size)
        assertTrue(result.reason.contains("does not identify"))
    }

    @Test
    fun partialWearDayAbstainsInsteadOfCallingItLowActivity() {
        val current = daily(
            daysAgo = 0,
            steps = 1_000L,
            gapReason = ActivityGapReason.OFF_WRIST,
            gapSeconds = 8_640L,
        )

        val result = engine.assess(history(), current)

        assertEquals(DailyActivityTrendState.ABSTAINED, result.state)
        assertNull(result.current)
        assertTrue(result.reason.contains("missing time is not inactivity"))
        assertEquals(8_640L, result.gapSecondsByReason.getValue(ActivityGapReason.OFF_WRIST))
    }

    @Test
    fun sourceFirmwareAndAcquisitionProtocolMustMatch() {
        val current = daily(daysAgo = 0, steps = 12_000L).copy(
            source = SensorSource.HEALTH_CONNECT,
        )

        val result = engine.assess(history(), current)

        assertEquals(DailyActivityTrendState.LEARNING, result.state)
        assertTrue(result.references.isEmpty())
    }

    @Test
    fun futureFinalizationCannotLeakIntoPriorReference() {
        val current = daily(daysAgo = 0, steps = 12_000L)
        val futureFinalized = history().map {
            it.copy(finalizedAtEpochMillis = current.observationStartEpochMillis + 1L)
        }

        val result = engine.assess(futureFinalized, current)

        assertEquals(DailyActivityTrendState.LEARNING, result.state)
        assertTrue(result.references.isEmpty())
    }

    @Test
    fun duplicateDailyEvidenceFailsClosed() {
        val current = daily(daysAgo = 0, steps = 12_000L)
        val history = history().toMutableList().apply {
            this[1] = this[1].copy(id = this[0].id)
        }

        val result = engine.assess(history, current)

        assertEquals(DailyActivityTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("duplicate"))
    }

    @Test
    fun currentDailyReplayAcrossAnotherSourceFailsClosed() {
        val current = daily(daysAgo = 0, steps = 12_000L)
        val crossSourceReplay = current.copy(source = SensorSource.SAMSUNG_HEALTH)

        val result = engine.assess(history() + crossSourceReplay, current)

        assertEquals(DailyActivityTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("duplicate"))
    }

    @Test
    fun entireDayMustBeAccountedAsQualifiedOrExplicitGapTime() {
        assertThrows(IllegalArgumentException::class.java) {
            daily(daysAgo = 0, steps = 1_000L).copy(qualifiedObservationSeconds = DAY_SECONDS - 1L)
        }
    }

    @Test
    fun dailyInputsConfigurationAndOutputsAreDefensiveUnmodifiableSnapshots() {
        val original = daily(
            daysAgo = 0,
            steps = 12_000L,
            gapReason = ActivityGapReason.CHARGING,
            gapSeconds = 3_456L,
        )
        val mutableGaps = original.gaps.toMutableList()
        val mutableProvenance = original.provenanceIds.toMutableList()
        val hardened = original.copy(gaps = mutableGaps, provenanceIds = mutableProvenance)
        mutableGaps.clear()
        mutableProvenance.clear()

        assertEquals(1, hardened.gaps.size)
        assertEquals(2, hardened.provenanceIds.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.gaps as MutableList<ActivityDataGap>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.provenanceIds as MutableList<String>).clear()
        }

        val mutableRequirements = linkedMapOf(7 to 5, 28 to 20, 90 to 60)
        val configured = DailyActivityTrendEngine(referenceRequirements = mutableRequirements)
        mutableRequirements.clear()
        val result = configured.assess(history(), hardened)
        assertEquals(DailyActivityTrendState.DESCRIPTIVE_TREND_AVAILABLE, result.state)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.references as MutableList<RollingActivityReference>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.differencesFrom28DayMedian as MutableList<ActivityVolumeDifference>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.gapSecondsByReason as MutableMap<ActivityGapReason, Long>).clear()
        }
    }

    @Test
    fun nonFiniteDailyPercentRatioIsWithheldInsteadOfThrowing() {
        val tinyReference = history().map { it.copy(distanceMeters = Double.MIN_VALUE) }
        val current = daily(daysAgo = 0, steps = 12_000L, distanceMeters = Double.MAX_VALUE)

        val result = engine.assess(tinyReference, current)

        assertEquals(DailyActivityTrendState.DESCRIPTIVE_TREND_AVAILABLE, result.state)
        assertNull(result.differencesFrom28DayMedian.first { it.metric == "distance" }.differencePercent)
    }

    private fun history(): List<DailyActivityObservation> = (1..90).map { daysAgo ->
        daily(
            daysAgo = daysAgo,
            steps = 10_000L + (daysAgo % 5 - 2) * 100L,
            distanceMeters = 7_500.0 + (daysAgo % 5 - 2) * 50.0,
            activeDurationSeconds = 3_600L + (daysAgo % 5 - 2) * 30L,
        )
    }

    private fun daily(
        daysAgo: Int,
        steps: Long,
        distanceMeters: Double = 8_000.0,
        activeDurationSeconds: Long = 3_900L,
        gapReason: ActivityGapReason? = null,
        gapSeconds: Long = 0L,
    ): DailyActivityObservation {
        val start = CURRENT_DAY_START - daysAgo * DAY_MILLIS
        val date = CURRENT_DATE.minusDays(daysAgo.toLong()).toString()
        val gap = if (gapReason == null) {
            emptyList()
        } else {
            listOf(
                ActivityDataGap(
                    startEpochMillis = start + DAY_MILLIS - gapSeconds * 1_000L,
                    endEpochMillis = start + DAY_MILLIS,
                    reason = gapReason,
                    provenanceId = "gap-$date",
                ),
            )
        }
        val provenance = buildList {
            add("daily-$date")
            addAll(gap.map(ActivityDataGap::provenanceId))
        }
        return DailyActivityObservation(
            id = "daily-$date",
            localDateIso = date,
            observationStartEpochMillis = start,
            observationEndEpochMillis = start + DAY_MILLIS,
            finalizedAtEpochMillis = start + DAY_MILLIS,
            source = SensorSource.GALAXY_WATCH_ULTRA_2,
            deviceGeneration = "ultra2",
            firmwareGeneration = "fixture-fw-1",
            acquisitionProtocolVersion = "daily-passive-v1",
            steps = steps,
            distanceMeters = distanceMeters,
            activeDurationSeconds = activeDurationSeconds,
            elevationGainMeters = 35.0,
            qualifiedObservationSeconds = DAY_SECONDS - gapSeconds,
            qualityScore = 0.95,
            gaps = gap,
            provenanceIds = provenance,
        )
    }

    private companion object {
        val CURRENT_DATE: LocalDate = LocalDate.of(2026, 8, 1)
        const val CURRENT_DAY_START = 2_000_000_000_000L
        const val DAY_SECONDS = 86_400L
        const val DAY_MILLIS = DAY_SECONDS * 1_000L
    }
}

class ActivityExerciseTrendEngineTest {
    private val engine = ActivityExerciseTrendEngine(
        eligibilityVerifier = StandardizedResponseEligibilityVerifier { episode ->
            episode.eligibilityReceiptId == "eligible:${episode.id}"
        },
    )

    @Test
    fun extractsDoseTimeWeightedHeartRateZonesCardiacCostRecoveryAndDrift() {
        val result = engine.assess(emptyList(), session(100))

        assertEquals(ExerciseTrendState.LEARNING, result.state)
        val features = result.featureSet!!
        assertEquals(1_000L, features.steps)
        assertEquals(0.9, features.distanceKilometres, 1e-9)
        assertEquals(10.0, features.activeMinutes, 1e-9)
        assertEquals(125.0, features.timeWeightedAverageHeartRateBpm, 1e-9)
        assertEquals(130.0, features.persistentPeakHeartRateBpm, 1e-9)
        assertEquals(65.0, features.matchedWorkloadCardiacCost, 1e-9)
        assertEquals(20.0, features.recoveryDropBpmByOffsetSeconds.getValue(60), 1e-9)
        assertEquals(30.0, features.recoveryDropBpmByOffsetSeconds.getValue(120), 1e-9)
        assertEquals(40.0, features.recoveryDropBpmByOffsetSeconds.getValue(300), 1e-9)
        assertEquals(16.666666, features.cardiacDriftPercent, 1e-5)
        assertEquals(600.0, features.zoneDurations.sumOf { it.durationSeconds }, 1e-9)
        assertEquals(0.0, features.unclassifiedZoneDurationSeconds, 1e-9)
        assertEquals(1.0, features.cadenceCoverage, 1e-9)
        assertEquals(400.0, features.zoneDurations.first { it.bandId == "personal-middle" }.durationSeconds, 1e-9)
        assertEquals(200.0, features.zoneDurations.first { it.bandId == "personal-upper" }.durationSeconds, 1e-9)
    }

    @Test
    fun heartRateMeanIsDurationWeightedAndPeakRequiresPersistentDuration() {
        val base = session(100)
        val start = base.startedAtEpochMillis
        val hr = listOf(
            heartInterval(100, 0, start, 540, 120.0),
            heartInterval(100, 1, start + 540_000L, 60, 180.0),
        )
        val adjusted = base.copy(
            heartRateIntervals = hr,
            provenanceIds = base.provenanceIds
                .filterNot { it.startsWith("hr-100-") }
                .plus(hr.map(ExerciseHeartRateInterval::provenanceId)),
        )

        val features = engine.assess(emptyList(), adjusted).featureSet!!

        assertEquals(126.0, features.timeWeightedAverageHeartRateBpm, 1e-9)
        assertEquals(180.0, features.persistentPeakHeartRateBpm, 1e-9)
        assertFalse(abs(features.timeWeightedAverageHeartRateBpm - 150.0) < 1e-9)
    }

    @Test
    fun baselineRequiresTwelveEpisodesAcrossTwentyEightDistinctDays() {
        val result = engine.assess(history().take(11), session(100))

        assertEquals(ExerciseTrendState.LEARNING, result.state)
        assertEquals(11, result.responseAssessment!!.referenceEpisodeCount)
    }

    @Test
    fun twentyEightEpisodesOnOnlyTwentySevenDistinctDaysRemainLearning() {
        val baseHistory = history()
        val duplicatedDay = baseHistory.mapIndexed { index, session ->
            if (index == baseHistory.lastIndex) {
                session.copy(localDateIso = baseHistory[index - 1].localDateIso)
            } else {
                session
            }
        }

        val result = engine.assess(duplicatedDay, session(100))

        assertEquals(ExerciseTrendState.LEARNING, result.state)
        val response = result.responseAssessment!!
        assertEquals(28, response.referenceEpisodeCount)
        assertEquals(27, response.effectiveReferenceDays)
    }

    @Test
    fun historyMustFinishRecoveryBeforeCurrentSessionStarts() {
        val current = session(100)
        val overlappingRecovery = history().map { candidate ->
            candidate.copy(recoveryEndedAtEpochMillis = current.startedAtEpochMillis + 1L)
        }

        val result = engine.assess(overlappingRecovery, current)

        assertEquals(ExerciseTrendState.LEARNING, result.state)
        assertEquals(0, result.responseAssessment!!.referenceEpisodeCount)
    }

    @Test
    fun futureLocalDatesCannotLeakIntoAReferenceDespiteEarlierTimestamps() {
        val current = session(100)
        val futureDated = history().mapIndexed { index, candidate ->
            candidate.copy(
                localDateIso = LocalDate.parse(current.localDateIso).plusDays(index.toLong() + 1L).toString(),
            )
        }

        val result = engine.assess(futureDated, current)

        assertEquals(ExerciseTrendState.LEARNING, result.state)
        assertEquals(0, result.responseAssessment!!.referenceEpisodeCount)
    }

    @Test
    fun matchedPersonalHistoryProducesConservativeWithinRangeAssessment() {
        val result = engine.assess(history(), session(100))

        assertEquals(ExerciseTrendState.WITHIN_PERSONAL_RANGE, result.state)
        assertEquals(28, result.responseAssessment!!.referenceEpisodeCount)
        assertTrue(result.reason.contains("cross-family"))
    }

    @Test
    fun cardiacAndMovementFamiliesMustBothChangeBeforeResearchSignal() {
        val current = session(100, heartRateShiftBpm = 15.0, cadenceSpm = 110.0)

        val result = engine.assess(history(), current)

        assertEquals(ExerciseTrendState.POSSIBLE_RESPONSE_CHANGE, result.state)
        assertEquals(
            setOf(ResponseFeatureFamily.CARDIAC_KINETICS, ResponseFeatureFamily.MOVEMENT),
            result.responseAssessment!!.changedIndependentFamilies,
        )
        assertTrue(result.reason.contains("cause is unknown"))
        assertTrue(result.reason.contains("not a diagnosis"))
    }

    @Test
    fun cardiacChangeAloneCannotCreateBroadResponseSignal() {
        val result = engine.assess(history(), session(100, heartRateShiftBpm = 15.0))

        assertEquals(ExerciseTrendState.WITHIN_PERSONAL_RANGE, result.state)
        assertEquals(
            setOf(ResponseFeatureFamily.CARDIAC_KINETICS),
            result.responseAssessment!!.changedIndependentFamilies,
        )
    }

    @Test
    fun sharedUnknownDataPipelineCannotManufactureIndependentCorroboration() {
        val baseSource = KEY.sourceMap
        val sharedSource = ExerciseSourceMap(
            heartRate = SensorSource.HEALTH_CONNECT,
            heartRateOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
            workload = SensorSource.HEALTH_CONNECT,
            workloadOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
            recoveryHeartRate = SensorSource.HEALTH_CONNECT,
            recoveryHeartRateOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
            dailyTotals = SensorSource.HEALTH_CONNECT,
            dailyTotalsOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
        )
        assertTrue(baseSource.hasIndependentCardiacAndWorkloadOrigins)
        assertFalse(sharedSource.hasIndependentCardiacAndWorkloadOrigins)
        val sharedKey = KEY.copy(sourceMap = sharedSource)
        val sharedHistory = history().map { it.copy(comparabilityKey = sharedKey) }
        val sharedCurrent = session(100, heartRateShiftBpm = 15.0, cadenceSpm = 110.0).copy(
            comparabilityKey = sharedKey,
        )

        val result = engine.assess(sharedHistory, sharedCurrent)

        assertEquals(ExerciseTrendState.WITHIN_PERSONAL_RANGE, result.state)
        assertEquals(
            setOf(ResponseFeatureFamily.CARDIAC_KINETICS),
            result.responseAssessment!!.changedIndependentFamilies,
        )
    }

    @Test
    fun forgedSourceOriginPairsAndDistinctSimulatorFixturesCannotManufactureCorroboration() {
        assertThrows(IllegalArgumentException::class.java) {
            KEY.sourceMap.copy(
                heartRate = SensorSource.HEALTH_CONNECT,
                heartRateOrigin = AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
            )
        }

        val simulator = ExerciseSourceMap(
            heartRate = SensorSource.SIMULATOR,
            heartRateOrigin = AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE,
            workload = SensorSource.SIMULATOR,
            workloadOrigin = AcquisitionOrigin.SIMULATOR_INERTIAL_FIXTURE,
            recoveryHeartRate = SensorSource.SIMULATOR,
            recoveryHeartRateOrigin = AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE,
            dailyTotals = SensorSource.SIMULATOR,
            dailyTotalsOrigin = AcquisitionOrigin.SIMULATOR_INERTIAL_FIXTURE,
        )

        assertFalse(simulator.hasIndependentCardiacAndWorkloadOrigins)
    }

    @Test
    fun offWristChargingAndBatteryGapsAreExplicitAndAlwaysAbstain() {
        ActivityGapReason.entries
            .filter { it in setOf(ActivityGapReason.OFF_WRIST, ActivityGapReason.CHARGING, ActivityGapReason.BATTERY_DEPLETED) }
            .forEach { reason ->
                val result = engine.assess(history(), session(100, gapReason = reason))

                assertEquals(reason.name, ExerciseTrendState.ABSTAINED, result.state)
                assertEquals(setOf(reason), result.gapReasons)
                assertNull(result.featureSet)
            }
    }

    @Test
    fun humanConcernAndMissingConcernCaptureOverrideOtherwiseCompleteSensorEvidence() {
        listOf(HumanConcernState.CONCERN_REPORTED, HumanConcernState.NOT_CAPTURED).forEach { concern ->
            val result = engine.assess(
                history(),
                session(100).copy(humanConcern = concern),
            )

            assertEquals(concern.name, ExerciseTrendState.HUMAN_CONCERN_REVIEW, result.state)
            assertNull(result.featureSet)
            assertTrue(result.reason.contains("cannot clear"))
        }
    }

    @Test
    fun unverifiedEligibilityReceiptCannotEnterAssessment() {
        val current = session(100).copy(eligibilityReceiptId = "caller-supplied")

        val result = engine.assess(history(), current)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertNotNull(result.featureSet)
        assertTrue(result.reason.contains("verified"))
    }

    @Test
    fun lowCoverageAndMisalignedRecoveryAbstain() {
        val base = session(100)
        val lowCoverage = base.copy(
            heartRateIntervals = base.heartRateIntervals.take(2),
            provenanceIds = base.provenanceIds.filterNot {
                it == base.heartRateIntervals.last().provenanceId
            },
        )
        assertEquals(ExerciseTrendState.ABSTAINED, engine.assess(history(), lowCoverage).state)

        val misalignedPoints = base.recoveryHeartRatePoints.map {
            if (it.offsetSeconds == 60) it.copy(observedAtEpochMillis = it.observedAtEpochMillis + 6_000L) else it
        }
        val misaligned = base.copy(recoveryHeartRatePoints = misalignedPoints)
        assertEquals(ExerciseTrendState.ABSTAINED, engine.assess(history(), misaligned).state)
    }

    @Test
    fun sparseCadenceCannotSatisfyCadenceMatchedWorkload() {
        val base = session(100)
        val sparseCadence = base.copy(
            workloadIntervals = base.workloadIntervals.mapIndexed { index, interval ->
                if (index == 0) interval else interval.copy(cadenceStepsPerMinute = null)
            },
        )

        val result = engine.assess(history(), sparseCadence)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("cadence coverage"))
    }

    @Test
    fun qualityCoverageContributesToDurationWeightingAndReportedCoverage() {
        val base = session(100)
        val partialCoverageQuality = QUALITY.copy(coverage = 0.95)
        val current = base.copy(
            heartRateIntervals = base.heartRateIntervals.map {
                it.copy(quality = partialCoverageQuality)
            },
            workloadIntervals = base.workloadIntervals.map {
                it.copy(quality = partialCoverageQuality)
            },
        )

        val features = engine.assess(emptyList(), current).featureSet!!

        assertEquals(0.95, features.heartRateCoverage, 1e-9)
        assertEquals(0.95, features.workloadCoverage, 1e-9)
        assertEquals(0.95, features.cadenceCoverage, 1e-9)
        assertEquals(570.0, features.zoneDurations.sumOf { it.durationSeconds }, 1e-9)
    }

    @Test
    fun interpretationGradeIntervalsStillAbstainBelowTheResearchCoverageGate() {
        val base = session(100)
        val insufficientCoverageQuality = QUALITY.copy(coverage = 0.85)
        assertTrue(insufficientCoverageQuality.interpretationGrade)
        val current = base.copy(
            heartRateIntervals = base.heartRateIntervals.map {
                it.copy(quality = insufficientCoverageQuality)
            },
        )

        val result = engine.assess(history(), current)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("coverage"))
    }

    @Test
    fun separatelyAdequateStreamsStillAbstainWhenTheirQualifiedTimesDoNotAlign() {
        val base = session(100)
        val start = base.startedAtEpochMillis
        val hr = listOf(heartInterval(100, 0, start + 60_000L, 540L, 125.0))
        val workload = listOf(
            base.workloadIntervals.first().copy(
                startEpochMillis = start,
                endEpochMillis = start + 540_000L,
            ),
        )
        val current = base.copy(
            heartRateIntervals = hr,
            workloadIntervals = workload,
            provenanceIds = buildList {
                add(base.summaryProvenanceId)
                add(base.restingHeartRateProvenanceId)
                add(base.ambientTemperatureProvenanceId!!)
                addAll(hr.map(ExerciseHeartRateInterval::provenanceId))
                addAll(workload.map(ExerciseWorkloadInterval::provenanceId))
                addAll(base.recoveryHeartRatePoints.map(ExerciseRecoveryHeartRatePoint::provenanceId))
                addAll(base.heartRateBands.provenanceIds)
            },
        )

        val result = engine.assess(history(), current)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("aligned cardiac-cost"))
    }

    @Test
    fun aggregateDistanceAndStepTotalsMustAgreeWithIntervalEvidence() {
        val distanceMismatch = engine.assess(history(), session(100).copy(distanceMeters = 780.0))
        val stepMismatch = engine.assess(history(), session(100).copy(steps = 800L))

        assertEquals(ExerciseTrendState.ABSTAINED, distanceMismatch.state)
        assertEquals(ExerciseTrendState.ABSTAINED, stepMismatch.state)
        assertTrue(distanceMismatch.reason.contains("matched-workload"))
        assertTrue(stepMismatch.reason.contains("matched-workload"))
    }

    @Test
    fun workloadEnvironmentSourceFirmwareAndProtocolDriftCannotBorrowReference() {
        val base = session(100)
        val variants = listOf(
            base.copy(distanceMeters = 700.0),
            base.copy(
                comparabilityKey = base.comparabilityKey.copy(
                    environmentBandFingerprintSha256 = "e".repeat(64),
                ),
            ),
            base.copy(
                comparabilityKey = base.comparabilityKey.copy(
                    sourceMap = base.comparabilityKey.sourceMap.copy(
                        heartRate = SensorSource.SAMSUNG_HEALTH,
                        heartRateOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
                    ),
                ),
            ),
            base.copy(
                comparabilityKey = base.comparabilityKey.copy(firmwareGeneration = "fixture-fw-2"),
            ),
            base.copy(
                comparabilityKey = base.comparabilityKey.copy(protocolVersion = "v2"),
            ),
            base.copy(sessionQualityEvaluatorVersion = "exercise-session-quality-v2"),
        )

        variants.forEachIndexed { index, variant ->
            val result = engine.assess(history(), variant)
            if (index == 0) {
                assertEquals(ExerciseTrendState.ABSTAINED, result.state)
            } else {
                assertEquals(ExerciseTrendState.LEARNING, result.state)
                assertEquals(0, result.responseAssessment!!.referenceEpisodeCount)
            }
        }
    }

    @Test
    fun duplicateMeasurementEvidenceCannotInflateReference() {
        val history = history().toMutableList()
        val first = history[0]
        val second = history[1]
        val duplicatedHr = second.heartRateIntervals.toMutableList().apply {
            this[0] = this[0].copy(provenanceId = first.heartRateIntervals[0].provenanceId)
        }
        history[1] = second.copy(
            heartRateIntervals = duplicatedHr,
            provenanceIds = second.provenanceIds
                .filterNot { it == second.heartRateIntervals[0].provenanceId }
                .plus(first.heartRateIntervals[0].provenanceId),
        )

        val result = engine.assess(history, session(100))

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("duplicate"))
    }

    @Test
    fun currentReplayAcrossAChangedFirmwareStillFailsClosed() {
        val current = session(100)
        val crossStratumReplay = current.copy(
            comparabilityKey = current.comparabilityKey.copy(firmwareGeneration = "other-firmware"),
        )

        val result = engine.assess(history() + crossStratumReplay, current)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("duplicate"))
    }

    @Test
    fun replayedSummaryOrRestingReferenceEvidenceFailsClosed() {
        val history = history()
        val first = history.first()
        val base = session(100)
        val variants = listOf(
            base.copy(
                summaryProvenanceId = first.summaryProvenanceId,
                provenanceIds = base.provenanceIds
                    .filterNot { it == base.summaryProvenanceId }
                    .plus(first.summaryProvenanceId),
            ),
            base.copy(
                restingHeartRateProvenanceId = first.restingHeartRateProvenanceId,
                provenanceIds = base.provenanceIds
                    .filterNot { it == base.restingHeartRateProvenanceId }
                    .plus(first.restingHeartRateProvenanceId),
            ),
        )

        variants.forEach { current ->
            val result = engine.assess(history, current)
            assertEquals(ExerciseTrendState.ABSTAINED, result.state)
            assertTrue(result.reason.contains("duplicate"))
        }
    }

    @Test
    fun extremeFiniteWorkloadInputAbstainsInsteadOfThrowingFromDerivedInfinity() {
        val base = session(100)
        val current = base.copy(
            workloadIntervals = base.workloadIntervals.map {
                it.copy(protocolWorkloadUnits = Double.MIN_VALUE)
            },
        )

        val result = engine.assess(history(), current)

        assertEquals(ExerciseTrendState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("cardiac-cost"))
    }

    @Test
    fun unboundOrDuplicateProvenanceAndUnboundedDurationAreRejectedAtConstruction() {
        val base = session(100)

        assertThrows(IllegalArgumentException::class.java) {
            base.copy(provenanceIds = base.provenanceIds + "unbound-evidence")
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(summaryProvenanceId = base.restingHeartRateProvenanceId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(activeDurationSeconds = 86_401L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                heartRateIntervals = base.heartRateIntervals.mapIndexed { index, interval ->
                    if (index == 0) {
                        interval.copy(quality = interval.quality.copy(evaluatorVersion = "quality-v3"))
                    } else {
                        interval
                    }
                },
            )
        }
    }

    @Test
    fun bandSessionQualityAndAssessmentCollectionsAreDefensiveUnmodifiableSnapshots() {
        val mutableBands = BANDS.bands.toMutableList()
        val mutableBandProvenance = BANDS.provenanceIds.toMutableList()
        val bandSet = PersonalHeartRateBandSet(
            definitionId = BANDS.definitionId,
            version = BANDS.version,
            bands = mutableBands,
            provenanceIds = mutableBandProvenance,
        )
        val bandDigest = bandSet.digestSha256
        mutableBands.clear()
        mutableBandProvenance.clear()
        assertEquals(3, bandSet.bands.size)
        assertEquals(bandDigest, bandSet.digestSha256)
        assertThrows(UnsupportedOperationException::class.java) {
            (bandSet.bands as MutableList<PersonalHeartRateBand>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (bandSet.provenanceIds as MutableList<String>).clear()
        }

        val base = session(100)
        val mutableQualityReasons = mutableListOf("qualified-fixture")
        val mutableHeartRate = base.heartRateIntervals.toMutableList().apply {
            this[0] = this[0].copy(quality = this[0].quality.copy(reasons = mutableQualityReasons))
        }
        val mutableWorkload = base.workloadIntervals.toMutableList()
        val mutableRecovery = base.recoveryHeartRatePoints.toMutableList()
        val mutableProvenance = base.provenanceIds.toMutableList()
        val hardened = base.copy(
            heartRateBands = bandSet,
            heartRateIntervals = mutableHeartRate,
            workloadIntervals = mutableWorkload,
            recoveryHeartRatePoints = mutableRecovery,
            provenanceIds = mutableProvenance,
        )
        mutableHeartRate.clear()
        mutableWorkload.clear()
        mutableRecovery.clear()
        mutableProvenance.clear()
        mutableQualityReasons += "post-validation-mutation"

        assertEquals(3, hardened.heartRateIntervals.size)
        assertEquals(3, hardened.workloadIntervals.size)
        assertEquals(3, hardened.recoveryHeartRatePoints.size)
        assertEquals(listOf("qualified-fixture"), hardened.heartRateIntervals.first().quality.reasons)
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.heartRateIntervals as MutableList<ExerciseHeartRateInterval>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.workloadIntervals as MutableList<ExerciseWorkloadInterval>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.recoveryHeartRatePoints as MutableList<ExerciseRecoveryHeartRatePoint>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.provenanceIds as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (hardened.heartRateIntervals.first().quality.reasons as MutableList<String>).clear()
        }

        val mutableOffsets = linkedSetOf(60, 120, 300)
        val configuredEngine = ActivityExerciseTrendEngine(
            eligibilityVerifier = StandardizedResponseEligibilityVerifier { episode ->
                episode.eligibilityReceiptId == "eligible:${episode.id}"
            },
            requiredRecoveryOffsetsSeconds = mutableOffsets,
        )
        mutableOffsets.clear()
        val result = configuredEngine.assess(history(), hardened)
        assertEquals(ExerciseTrendState.WITHIN_PERSONAL_RANGE, result.state)
        val features = result.featureSet!!
        assertThrows(UnsupportedOperationException::class.java) {
            (features.zoneDurations as MutableList<ExerciseZoneDuration>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (features.recoveryDropBpmByOffsetSeconds as MutableMap<Int, Double>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (features.provenanceIds as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.responseAssessment!!.deviations as MutableList<ResponseFeatureDeviation>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.responseAssessment!!.changedIndependentFamilies as MutableSet<ResponseFeatureFamily>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.gapReasons as MutableSet<ActivityGapReason>).clear()
        }
    }

    private fun history(): List<ExerciseSessionObservation> = (1..28).map { index ->
        session(
            index = index,
            heartRateShiftBpm = (index % 3 - 1) * 0.4,
            cadenceSpm = 100.0 + (index % 3 - 1) * 0.5,
        )
    }

    private fun session(
        index: Int,
        heartRateShiftBpm: Double = 0.0,
        cadenceSpm: Double = 100.0,
        gapReason: ActivityGapReason? = null,
    ): ExerciseSessionObservation {
        val start = EXERCISE_BASE + index * DAY_MILLIS
        val activeEnd = start + ACTIVE_SECONDS * 1_000L
        val hr = listOf(
            heartInterval(index, 0, start, 200, 120.0 + heartRateShiftBpm),
            heartInterval(index, 1, start + 200_000L, 200, 125.0 + heartRateShiftBpm),
            heartInterval(index, 2, start + 400_000L, 200, 130.0 + heartRateShiftBpm),
        )
        val workload = (0..2).map { segment ->
            ExerciseWorkloadInterval(
                startEpochMillis = start + segment * 200_000L,
                endEpochMillis = start + (segment + 1) * 200_000L,
                speedMetersPerSecond = 1.5,
                gradePercent = 1.0,
                cadenceStepsPerMinute = cadenceSpm,
                protocolWorkloadUnits = 1.0,
                quality = QUALITY,
                provenanceId = "work-$index-$segment",
            )
        }
        val recovery = listOf(
            recoveryPoint(index, activeEnd, 60, 110.0 + heartRateShiftBpm),
            recoveryPoint(index, activeEnd, 120, 100.0 + heartRateShiftBpm),
            recoveryPoint(index, activeEnd, 300, 90.0 + heartRateShiftBpm),
        )
        val gaps = if (gapReason == null) {
            emptyList()
        } else {
            listOf(
                ActivityDataGap(
                    startEpochMillis = start + 100_000L,
                    endEpochMillis = start + 110_000L,
                    reason = gapReason,
                    provenanceId = "gap-$index",
                ),
            )
        }
        val provenance = buildList {
            add("summary-$index")
            add("resting-hr-$index")
            add("ambient-temperature-$index")
            addAll(hr.map(ExerciseHeartRateInterval::provenanceId))
            addAll(workload.map(ExerciseWorkloadInterval::provenanceId))
            addAll(recovery.map(ExerciseRecoveryHeartRatePoint::provenanceId))
            addAll(gaps.map(ActivityDataGap::provenanceId))
            addAll(BANDS.provenanceIds)
        }
        return ExerciseSessionObservation(
            id = "exercise-$index",
            localDateIso = LocalDate.of(2026, 1, 1).plusDays(index.toLong()).toString(),
            startedAtEpochMillis = start,
            activeEndedAtEpochMillis = activeEnd,
            recoveryEndedAtEpochMillis = activeEnd + 300_000L,
            comparabilityKey = KEY,
            workloadTarget = TARGET,
            heartRateBands = BANDS,
            eligibilityReceiptId = "eligible:exercise-$index",
            humanConcern = HumanConcernState.NO_CONCERN_REPORTED,
            sessionQualityScore = 0.95,
            sessionQualityEvaluatorVersion = "exercise-session-quality-v1",
            steps = 1_000L,
            distanceMeters = 900.0,
            activeDurationSeconds = ACTIVE_SECONDS,
            elevationGainMeters = 10.0,
            restingHeartRateBpm = 60.0,
            observedAmbientTemperatureC = 22.0,
            summaryQuality = QUALITY,
            restingHeartRateQuality = QUALITY,
            ambientTemperatureQuality = QUALITY,
            summaryProvenanceId = "summary-$index",
            restingHeartRateProvenanceId = "resting-hr-$index",
            ambientTemperatureProvenanceId = "ambient-temperature-$index",
            heartRateIntervals = hr,
            workloadIntervals = workload,
            recoveryHeartRatePoints = recovery,
            gaps = gaps,
            provenanceIds = provenance,
        )
    }

    private fun heartInterval(
        index: Int,
        segment: Int,
        start: Long,
        durationSeconds: Long,
        heartRateBpm: Double,
    ) = ExerciseHeartRateInterval(
        startEpochMillis = start,
        endEpochMillis = start + durationSeconds * 1_000L,
        heartRateBpm = heartRateBpm,
        quality = QUALITY,
        provenanceId = "hr-$index-$segment",
    )

    private fun recoveryPoint(index: Int, activeEnd: Long, offset: Int, bpm: Double) =
        ExerciseRecoveryHeartRatePoint(
            offsetSeconds = offset,
            observedAtEpochMillis = activeEnd + offset * 1_000L,
            averagingWindowSeconds = 10,
            heartRateBpm = bpm,
            quality = QUALITY,
            provenanceId = "recovery-$index-$offset",
        )

    private companion object {
        const val EXERCISE_BASE = 1_900_000_000_000L
        const val DAY_MILLIS = 86_400_000L
        const val ACTIVE_SECONDS = 600L

        val QUALITY = SignalQuality(
            score = 0.97,
            coverage = 1.0,
            contact = 0.99,
            motionContamination = 0.05,
            validity = 0.99,
            clipping = 0.0,
            timestampContinuity = 0.99,
        )
        val BANDS = PersonalHeartRateBandSet(
            definitionId = "reviewed-personal-bands",
            version = "v1",
            bands = listOf(
                PersonalHeartRateBand("personal-lower", 0.0, 100.0),
                PersonalHeartRateBand("personal-middle", 100.0, 130.0),
                PersonalHeartRateBand("personal-upper", 130.0, 300.0),
            ),
            provenanceIds = listOf("reviewed-band-definition-v1"),
        )
        val TARGET = MatchedWorkloadTarget(
            targetDistanceMeters = 900.0,
            distanceToleranceFraction = 0.15,
            targetActiveDurationSeconds = ACTIVE_SECONDS,
            durationToleranceFraction = 0.05,
            targetMeanSpeedMps = 1.5,
            speedToleranceFraction = 0.10,
            targetElevationGainMeters = 10.0,
            elevationToleranceMeters = 3.0,
            targetMeanCadenceSpm = 100.0,
            cadenceToleranceFraction = 0.15,
            minimumAmbientTemperatureC = 18.0,
            maximumAmbientTemperatureC = 26.0,
        )
        val KEY = ExerciseComparabilityKey(
            exerciseType = "fixed-walk",
            protocolId = "reviewed-fixed-walk",
            protocolVersion = "v1",
            deviceGeneration = "ultra2",
            firmwareGeneration = "fixture-fw-1",
            sourceMap = ExerciseSourceMap(
                heartRate = SensorSource.GALAXY_WATCH_ULTRA_2,
                heartRateOrigin = AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
                workload = SensorSource.GALAXY_WATCH_ULTRA_2,
                workloadOrigin = AcquisitionOrigin.WRIST_INERTIAL_MOTION,
                recoveryHeartRate = SensorSource.GALAXY_WATCH_ULTRA_2,
                recoveryHeartRateOrigin = AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
                dailyTotals = SensorSource.GALAXY_WATCH_ULTRA_2,
                dailyTotalsOrigin = AcquisitionOrigin.WRIST_INERTIAL_MOTION,
            ),
            routeOrEquipmentFingerprintSha256 = "a".repeat(64),
            environmentBandFingerprintSha256 = "b".repeat(64),
            workloadModelId = "fixed-walk-workload",
            workloadModelVersion = "v1",
            recoveryProtocolId = "passive-seated-recovery",
            recoveryProtocolVersion = "v1",
        )
    }
}
