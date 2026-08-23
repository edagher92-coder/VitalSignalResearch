package au.com.elied.vitalsignal.phone.presentation.dashboard

import au.com.elied.vitalsignal.analytics.ForecastModelState
import au.com.elied.vitalsignal.analytics.PersistenceEvidenceStatus
import au.com.elied.vitalsignal.analytics.SafetyDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorHealthPipelineTest {
    private val pipeline = SimulatorHealthPipeline()

    @Test
    fun fourSeededScenariosExerciseDistinctSafetyStates() {
        assertEquals(
            SafetyDisposition.SINGLE_SIGNAL_REMEASURE,
            pipeline.evaluate(SimulationScenario.DEVELOPING).safetyDecision.disposition,
        )
        assertEquals(
            SafetyDisposition.TYPICAL,
            pipeline.evaluate(SimulationScenario.STEADY).safetyDecision.disposition,
        )
        assertEquals(
            SafetyDisposition.LEARNING,
            pipeline.evaluate(SimulationScenario.LEARNING).safetyDecision.disposition,
        )
        assertEquals(
            SafetyDisposition.MEASUREMENT_UNAVAILABLE,
            pipeline.evaluate(SimulationScenario.LOW_QUALITY).safetyDecision.disposition,
        )
    }

    @Test
    fun developingFixtureFlowsThroughBaselineInterpretationAndForecast() {
        val result = pipeline.evaluate(SimulationScenario.DEVELOPING)

        assertEquals(5, result.baselines.size)
        assertEquals(5, result.deviations.size)
        assertNotNull(result.insight)
        assertEquals(ForecastModelState.READY, result.forecastEstimate.state)
        assertTrue(result.forecastEstimate.validCaseCount >= 30)
        assertNotNull(result.forecastEstimate.forecast)
        assertTrue(result.quality.interpretationGrade)
        assertEquals(
            PersistenceEvidenceStatus.VERIFIED_PRIOR_CHAIN,
            result.interpretationAssessment.persistence.status,
        )
        assertEquals(3, result.interpretationAssessment.persistence.qualifiedWindowCount)
    }

    @Test
    fun lowQualityFixtureCannotProduceInsightOrForecast() {
        val result = pipeline.evaluate(SimulationScenario.LOW_QUALITY)

        assertTrue(!result.quality.interpretationGrade)
        assertNull(result.insight)
        assertEquals(ForecastModelState.ABSTAINED, result.forecastEstimate.state)
    }

    @Test
    fun directHumanConcernOverridesEveryFixtureSensorState() {
        SimulationScenario.entries.forEach { scenario ->
            val result = pipeline.evaluate(scenario, userConcernReported = true)
            assertEquals(
                SafetyDisposition.USER_CONCERN_REVIEW,
                result.safetyDecision.disposition,
            )
        }
    }
}
