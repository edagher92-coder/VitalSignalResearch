package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardizedResponseEngineTest {
    private val engine = StandardizedResponseEngine(
        eligibilityVerifier = StandardizedResponseEligibilityVerifier { episode ->
            episode.eligibilityReceiptId ==
                "fixture-eligibility-${episode.id.removePrefix("episode-")}"
        },
        minimumScaleByFeature = mapOf(
            "peak-hr-delta" to 1.0,
            "recovery-half-life" to 2.0,
            "ppg-amplitude-change" to 0.02,
        ),
    )

    @Test
    fun immatureReferenceStaysLearning() {
        val result = engine.assess(history().take(11), episode(100, dateDay = 29))

        assertEquals(ResponseAssessmentState.LEARNING, result.state)
        assertEquals(11, result.referenceEpisodeCount)
    }

    @Test
    fun repeatedEpisodesMustSpanEnoughDays() {
        val repeatedDay = (1..28).map { episode(it, dateDay = 1) }

        val result = engine.assess(repeatedDay, episode(100, dateDay = 29))

        assertEquals(ResponseAssessmentState.LEARNING, result.state)
        assertEquals(1, result.effectiveReferenceDays)
    }

    @Test
    fun poorCurrentQualityAbstains() {
        val result = engine.assess(
            history(),
            episode(100, dateDay = 29).copy(quality = 0.84),
        )

        assertEquals(ResponseAssessmentState.ABSTAINED, result.state)
    }

    @Test
    fun callerSuppliedOrMismatchedReceiptCannotEnterComparison() {
        val forged = episode(100, dateDay = 29).copy(
            eligibilityReceiptId = "fixture-eligibility-forged",
        )

        val result = engine.assess(history(), forged)

        assertEquals(ResponseAssessmentState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("verified", ignoreCase = true))
    }

    @Test
    fun unverifiedHistoryIsExcludedFromPersonalReference() {
        val historyWithForgedReceipts = history().mapIndexed { index, episode ->
            if (index < 20) episode.copy(eligibilityReceiptId = "forged-$index") else episode
        }

        val result = engine.assess(historyWithForgedReceipts, episode(100, dateDay = 29))

        assertEquals(ResponseAssessmentState.LEARNING, result.state)
        assertEquals(8, result.referenceEpisodeCount)
    }

    @Test
    fun humanConcernAlwaysHoldsAssessmentEvenWhenSensorsAreHighQuality() {
        val result = engine.assess(
            history(),
            episode(100, dateDay = 29).copy(humanConcern = HumanConcernState.CONCERN_REPORTED),
        )

        assertEquals(ResponseAssessmentState.HUMAN_CONCERN_REVIEW, result.state)
        assertTrue(result.reason.contains("cannot override"))
    }

    @Test
    fun futureReferenceCannotLeakIntoAssessment() {
        val future = episode(500, dateDay = 30).copy(completedAtEpochMillis = 200_000L)
        val current = episode(100, dateDay = 29).copy(completedAtEpochMillis = 100_000L)

        val result = engine.assess(history() + future, current)

        assertEquals(28, result.referenceEpisodeCount)
    }

    @Test
    fun firmwareAndProtocolChangesRequireASeparateReference() {
        val mismatched = history().map {
            it.copy(firmwareGeneration = "new-firmware")
        }

        val result = engine.assess(mismatched, episode(100, dateDay = 29))

        assertEquals(ResponseAssessmentState.LEARNING, result.state)
        assertEquals(0, result.referenceEpisodeCount)
    }

    @Test
    fun changedPhysicalConfigurationRequiresASeparateReference() {
        val result = engine.assess(
            history(),
            episode(100, dateDay = 29).copy(standardizationFingerprint = "b".repeat(64)),
        )

        assertEquals(ResponseAssessmentState.LEARNING, result.state)
        assertEquals(0, result.referenceEpisodeCount)
    }

    @Test
    fun oneCorrelatedFamilyCannotCreateAChangeSignal() {
        val current = episode(
            index = 100,
            dateDay = 29,
            peakHrDelta = 35.0,
            recoveryHalfLife = 120.0,
            ppgAmplitude = 0.98,
        )

        val result = engine.assess(history(), current)

        assertEquals(ResponseAssessmentState.WITHIN_PERSONAL_RANGE, result.state)
        assertEquals(setOf(ResponseFeatureFamily.CARDIAC_KINETICS), result.changedIndependentFamilies)
    }

    @Test
    fun independentFamiliesCanCreateResearchLevelResponseSignal() {
        val current = episode(
            index = 100,
            dateDay = 29,
            peakHrDelta = 35.0,
            recoveryHalfLife = 70.0,
            ppgAmplitude = 0.70,
        )

        val result = engine.assess(history(), current)

        assertEquals(ResponseAssessmentState.POSSIBLE_RESPONSE_CHANGE, result.state)
        assertEquals(
            setOf(ResponseFeatureFamily.CARDIAC_KINETICS, ResponseFeatureFamily.PPG_MORPHOLOGY),
            result.changedIndependentFamilies,
        )
        assertTrue(result.reason.contains("cause is unknown"))
    }

    @Test
    fun unitsMustMatchBeforeAFeatureCanBeCompared() {
        val incompatible = episode(100, dateDay = 29).copy(
            features = episode(100, dateDay = 29).features.map {
                if (it.id == "peak-hr-delta") it.copy(unit = "Hz") else it
            },
        )

        val result = engine.assess(history(), incompatible)

        assertEquals(ResponseAssessmentState.ABSTAINED, result.state)
        assertEquals(2, result.deviations.size)
    }

    private fun history(): List<StandardizedResponseEpisode> = (1..28).map { day ->
        episode(
            index = day,
            dateDay = day,
            peakHrDelta = 20.0 + (day % 3 - 1) * 0.5,
            recoveryHalfLife = 70.0 + (day % 3 - 1),
            ppgAmplitude = 0.98 + (day % 3 - 1) * 0.005,
        )
    }

    private fun episode(
        index: Int,
        dateDay: Int,
        peakHrDelta: Double = 20.0,
        recoveryHalfLife: Double = 70.0,
        ppgAmplitude: Double = 0.98,
    ) = StandardizedResponseEpisode(
        id = "episode-$index",
        protocolId = "research-fixed-walk",
        protocolVersion = "v1",
        deviceGeneration = "ultra2",
        firmwareGeneration = "fixture-fw-1",
        completedAtEpochMillis = index * 1_000L,
        localDateIso = "2026-07-${dateDay.toString().padStart(2, '0')}",
        quality = 0.95,
        eligibilityReceiptId = "fixture-eligibility-$index",
        standardizationFingerprint = "a".repeat(64),
        humanConcern = HumanConcernState.NO_CONCERN_REPORTED,
        features = listOf(
            ResponseFeature(
                "peak-hr-delta",
                ResponseFeatureFamily.CARDIAC_KINETICS,
                peakHrDelta,
                "bpm",
            ),
            ResponseFeature(
                "recovery-half-life",
                ResponseFeatureFamily.CARDIAC_KINETICS,
                recoveryHalfLife,
                "s",
            ),
            ResponseFeature(
                "ppg-amplitude-change",
                ResponseFeatureFamily.PPG_MORPHOLOGY,
                ppgAmplitude,
                "ratio",
            ),
        ),
        provenanceIds = listOf("fixture-$index"),
    )
}
