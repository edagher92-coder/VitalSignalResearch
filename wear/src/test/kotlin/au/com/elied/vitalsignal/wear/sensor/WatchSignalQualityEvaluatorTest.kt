package au.com.elied.vitalsignal.wear.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSignalQualityEvaluatorTest {
    @Test
    fun receivedSamplesCannotExceedExpectedAcquisitionWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            RawQualitySignals(
                expectedSamples = 999,
                receivedSamples = 1_000,
                contactConfidence = 1.0,
                motionRmsG = 0.0,
                clippedSampleFraction = 0.0,
            )
        }
    }

    @Test
    fun noContactHardFailsEvenWithCompleteCoverage() {
        val quality = WatchSignalQualityEvaluator.evaluate(
            RawQualitySignals(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                contactConfidence = 0.0,
                motionRmsG = 0.0,
                clippedSampleFraction = 0.0,
            ),
        )

        assertEquals(0.0, quality.score, 0.0)
        assertFalse(quality.usable)
        assertTrue(quality.reasons.any { it.contains("required quality gate") })
    }

    @Test
    fun clippingAndTrackerWarningsCannotRemainInterpretationGrade() {
        val clipped = WatchSignalQualityEvaluator.evaluate(
            RawQualitySignals(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                contactConfidence = 1.0,
                motionRmsG = 0.0,
                clippedSampleFraction = 0.25,
            ),
        )
        val warned = WatchSignalQualityEvaluator.evaluate(
            RawQualitySignals(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                contactConfidence = 1.0,
                motionRmsG = 0.0,
                clippedSampleFraction = 0.0,
                trackerWarnings = setOf("simulated critical warning"),
            ),
        )

        assertFalse(clipped.interpretationGrade)
        assertFalse(warned.interpretationGrade)
    }
}
