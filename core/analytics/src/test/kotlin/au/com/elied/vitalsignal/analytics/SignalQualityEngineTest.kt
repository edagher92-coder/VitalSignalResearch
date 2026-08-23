package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalQualityEngineTest {
    private val engine = SignalQualityEngine()

    @Test
    fun excellentCoverageCannotCompensateForNoWatchContact() {
        val quality = engine.score(
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                validSamples = 1_000,
                onBodyFraction = 0.0,
                motionFraction = 0.0,
                clippingFraction = 0.0,
                timestampContinuity = 1.0,
            ),
        )

        assertEquals(0.0, quality.score, 0.0)
        assertFalse(quality.usable)
        assertTrue(quality.reasons.any { it.contains("required quality gate") })
    }

    @Test
    fun cleanStationaryWindowIsInterpretationGrade() {
        val quality = engine.score(
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 980,
                validSamples = 970,
                onBodyFraction = 0.98,
                motionFraction = 0.03,
                clippingFraction = 0.0,
                timestampContinuity = 0.99,
            ),
        )

        assertTrue(quality.interpretationGrade)
    }

    @Test
    fun halfInvalidSamplesCannotBecomeInterpretationGrade() {
        val quality = engine.score(
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                validSamples = 500,
                onBodyFraction = 1.0,
                motionFraction = 0.0,
                clippingFraction = 0.0,
                timestampContinuity = 1.0,
            ),
        )

        assertFalse(quality.interpretationGrade)
        assertEquals(0.0, quality.score, 0.0)
    }

    @Test
    fun clippedWindowCannotBecomeInterpretationGrade() {
        val quality = engine.score(
            QualityInputs(
                expectedSamples = 1_000,
                receivedSamples = 1_000,
                validSamples = 1_000,
                onBodyFraction = 1.0,
                motionFraction = 0.0,
                clippingFraction = 0.25,
                timestampContinuity = 1.0,
            ),
        )

        assertFalse(quality.interpretationGrade)
    }

    @Test
    fun invalidFractionsAreRejectedInsteadOfClamped() {
        assertThrows(IllegalArgumentException::class.java) {
            engine.score(
                QualityInputs(
                    expectedSamples = 10,
                    receivedSamples = 10,
                    validSamples = 10,
                    onBodyFraction = 1.2,
                    motionFraction = 0.0,
                    clippingFraction = 0.0,
                    timestampContinuity = 1.0,
                ),
            )
        }
    }
}
