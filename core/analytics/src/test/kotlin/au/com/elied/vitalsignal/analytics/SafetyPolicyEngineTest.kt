package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyEngineTest {
    private val engine = SafetyPolicyEngine()

    @Test
    fun lowQualityIsADataMessageNotAHealthMessage() {
        val decision = engine.evaluate(readyInput().copy(dataQuality = 0.79))

        assertEquals(SafetyDisposition.MEASUREMENT_UNAVAILABLE, decision.disposition)
        assertFalse(decision.userMessage.contains("typical", ignoreCase = true))
    }

    @Test
    fun baselineMustBeFullyMatureWithTwentyMatchedSamples() {
        assertEquals(
            SafetyDisposition.LEARNING,
            engine.evaluate(readyInput().copy(baselineMaturity = 0.99)).disposition,
        )
        assertEquals(
            SafetyDisposition.LEARNING,
            engine.evaluate(readyInput().copy(baselineSampleCount = 19)).disposition,
        )
    }

    @Test
    fun oneIndependentFamilyCanOnlyRequestRemeasurement() {
        val decision = engine.evaluate(
            readyInput().copy(
                independentCoherentFamilies = 1,
                independentCoherentAcquisitionGroups = 1,
            ),
        )

        assertEquals(SafetyDisposition.SINGLE_SIGNAL_REMEASURE, decision.disposition)
    }

    @Test
    fun severalDomainsFromOneAcquisitionPathCannotBecomePatternEligible() {
        val decision = engine.evaluate(
            readyInput().copy(independentCoherentAcquisitionGroups = 1),
        )

        assertEquals(SafetyDisposition.ABSTAINED, decision.disposition)
        assertTrue(decision.reasonCodes.contains("shared-acquisition-dependency"))
        assertTrue(decision.userMessage.contains("one sensor acquisition path"))
    }

    @Test
    fun zeroDeviatingFamiliesIsExplicitTypicalNotMissingData() {
        val decision = engine.evaluate(
            readyInput().copy(
                independentCoherentFamilies = 0,
                independentCoherentAcquisitionGroups = 0,
            ),
        )

        assertEquals(SafetyDisposition.TYPICAL, decision.disposition)
        assertTrue(decision.userMessage.contains("does not rule out", ignoreCase = true))
    }

    @Test
    fun missingRequiredFamilyCannotSilentlyBecomeTypical() {
        val decision = engine.evaluate(
            readyInput().copy(
                independentCoherentFamilies = 0,
                independentCoherentAcquisitionGroups = 0,
                availableQualifiedFamilies = setOf(IndependentEvidenceFamily.CARDIO_AUTONOMIC),
            ),
        )

        assertEquals(SafetyDisposition.MEASUREMENT_UNAVAILABLE, decision.disposition)
        assertTrue(decision.reasonCodes.contains("required-qualified-family-unavailable"))
        assertFalse(decision.userMessage.contains("within expected", ignoreCase = true))
    }

    @Test
    fun opposingDirectionsWithinQualifiedFamilyForceAbstention() {
        val decision = engine.evaluate(
            readyInput().copy(
                independentCoherentFamilies = 1,
                independentCoherentAcquisitionGroups = 1,
                conflictingFamilies = setOf(IndependentEvidenceFamily.CARDIO_AUTONOMIC),
            ),
        )

        assertEquals(SafetyDisposition.ABSTAINED, decision.disposition)
        assertTrue(decision.reasonCodes.contains("opposing-qualified-evidence-within-family"))
        assertTrue(decision.userMessage.contains("repeat", ignoreCase = true))
    }

    @Test
    fun wideUncertaintyAndFirmwareTransitionsForceAbstention() {
        assertEquals(
            SafetyDisposition.ABSTAINED,
            engine.evaluate(readyInput().copy(intervalWidth = 0.66)).disposition,
        )
        assertEquals(
            SafetyDisposition.ABSTAINED,
            engine.evaluate(readyInput().copy(firmwareOrDeviceChanged = true)).disposition,
        )
    }

    @Test
    fun urgentRouteRequiresSeparateReviewedSymptomFlag() {
        assertEquals(SafetyDisposition.PATTERN_ELIGIBLE, engine.evaluate(readyInput()).disposition)
        assertEquals(
            SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS,
            engine.evaluate(readyInput().copy(reviewedUrgentSymptomFlag = true)).disposition,
        )
    }

    @Test
    fun directUserConcernOverridesReassuringOrPoorSensorStates() {
        val ready = engine.evaluate(readyInput().copy(userConcernReported = true))
        val poorData = engine.evaluate(
            readyInput().copy(
                userConcernReported = true,
                dataQuality = 0.10,
                baselineMaturity = 0.0,
                baselineSampleCount = 0,
                independentCoherentFamilies = 0,
                independentCoherentAcquisitionGroups = 0,
            ),
        )

        assertEquals(SafetyDisposition.USER_CONCERN_REVIEW, ready.disposition)
        assertEquals(SafetyDisposition.USER_CONCERN_REVIEW, poorData.disposition)
        assertTrue(ready.reasonCodes.contains("user-concern-overrides-sensors"))
        assertTrue(ready.userMessage.contains("Do not rely", ignoreCase = true))
    }

    @Test
    fun reviewedUrgentSymptomRouteTakesPriorityOverGeneralConcern() {
        val decision = engine.evaluate(
            readyInput().copy(
                reviewedUrgentSymptomFlag = true,
                userConcernReported = true,
            ),
        )

        assertEquals(SafetyDisposition.ROUTE_REVIEWED_SYMPTOMS, decision.disposition)
    }

    private fun readyInput() = SafetyGateInput(
        dataQuality = 0.95,
        baselineMaturity = 1.0,
        baselineSampleCount = 30,
        independentCoherentFamilies = 2,
        independentCoherentAcquisitionGroups = 2,
        expectedQualifiedFamilies = setOf(
            IndependentEvidenceFamily.CARDIO_AUTONOMIC,
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
        ),
        availableQualifiedFamilies = setOf(
            IndependentEvidenceFamily.CARDIO_AUTONOMIC,
            IndependentEvidenceFamily.RESPIRATORY_OXYGENATION,
        ),
        intervalWidth = 0.30,
    )
}
