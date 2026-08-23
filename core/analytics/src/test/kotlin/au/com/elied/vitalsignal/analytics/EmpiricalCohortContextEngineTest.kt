package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmpiricalCohortContextEngineTest {
    private val engine = EmpiricalCohortContextEngine(
        referenceVerifier = EmpiricalCohortReferenceVerifier { reference ->
            reference.validationReceiptId == "population-reference-validation-1" &&
                reference.sourceContentSha256 == "c".repeat(64)
        },
    )

    @Test
    fun exactValidatedCohortProducesContextOnlyPosition() {
        val result = engine.describe(request(), listOf(reference()))

        assertEquals(EmpiricalContextState.CONTEXT_ONLY, result.state)
        assertEquals(EmpiricalPosition.P50_TO_P75, result.position)
        assertTrue(result.advisoryOnly)
    }

    @Test
    fun moreSpecificAgeAndStudyStratumWins() {
        val pooled = reference().copy(referenceId = "pooled", sexStratum = "ALL", sampleSize = 1_000)
        val broad = reference().copy(referenceId = "broad", minimumAgeYears = 18, maximumAgeYears = 80)
        val exact = reference().copy(referenceId = "exact")

        val result = engine.describe(request(), listOf(pooled, broad, exact))

        assertEquals("exact", result.referenceId)
    }

    @Test
    fun deviceMismatchCannotBePresentedAsNormativeContext() {
        val result = engine.describe(request(), listOf(reference().copy(deviceGeneration = "different-watch")))

        assertEquals(EmpiricalContextState.UNAVAILABLE, result.state)
        assertNull(result.position)
    }

    @Test
    fun smallOrStaleCohortFailsClosed() {
        val small = reference().copy(sampleSize = 20)
        val stale = reference().copy(verifiedAtEpochMillis = NOW - THREE_YEARS)

        assertEquals(EmpiricalContextState.UNAVAILABLE, engine.describe(request(), listOf(small)).state)
        assertEquals(EmpiricalContextState.UNAVAILABLE, engine.describe(request(), listOf(stale)).state)
    }

    @Test
    fun forgedOrContentMismatchedReviewAuthorityFailsClosed() {
        val forgedReceipt = reference().copy(validationReceiptId = "caller-supplied")
        val changedContent = reference().copy(sourceContentSha256 = "d".repeat(64))

        assertEquals(
            EmpiricalContextState.UNAVAILABLE,
            engine.describe(request(), listOf(forgedReceipt)).state,
        )
        assertEquals(
            EmpiricalContextState.UNAVAILABLE,
            engine.describe(request(), listOf(changedContent)).state,
        )
    }

    @Test
    fun unitAndProtocolMustMatchExactly() {
        assertEquals(
            EmpiricalContextState.UNAVAILABLE,
            engine.describe(request().copy(unit = "ms"), listOf(reference())).state,
        )
        assertEquals(
            EmpiricalContextState.UNAVAILABLE,
            engine.describe(request().copy(protocolVersion = "walk-v2"), listOf(reference())).state,
        )
    }

    @Test
    fun quantileEdgesAreDeterministic() {
        val reference = reference()

        assertEquals(EmpiricalPosition.BELOW_P10, describe(49.0, reference))
        assertEquals(EmpiricalPosition.P10_TO_P25, describe(50.0, reference))
        assertEquals(EmpiricalPosition.P75_TO_P90, describe(70.0, reference))
        assertEquals(EmpiricalPosition.ABOVE_P90, describe(71.0, reference))
    }

    private fun describe(value: Double, reference: EmpiricalCohortReference) = engine.describe(
        request().copy(observedValue = value),
        listOf(reference),
    ).position

    private fun request() = EmpiricalContextRequest(
        featureId = "recovery-half-life",
        observedValue = 63.0,
        unit = "seconds",
        deviceGeneration = "ultra2",
        protocolVersion = "walk-v1",
        ageYears = 47,
        sexStratum = "MALE",
        evaluatedAtEpochMillis = NOW,
    )

    private fun reference() = EmpiricalCohortReference(
        referenceId = "reference-47-55-male-ultra2",
        featureId = "recovery-half-life",
        unit = "seconds",
        deviceGeneration = "ultra2",
        protocolVersion = "walk-v1",
        minimumAgeYears = 45,
        maximumAgeYears = 55,
        sexStratum = "MALE",
        geographyAndSeason = "Australia; all seasons",
        sampleSize = 320,
        quantiles = EmpiricalQuantiles(50.0, 55.0, 60.0, 65.0, 70.0),
        sourceId = "curated-study-doi",
        sourceContentSha256 = "c".repeat(64),
        validationReceiptId = "population-reference-validation-1",
        verifiedAtEpochMillis = NOW - 1_000L,
    )

    private companion object {
        const val NOW = 2_000_000_000_000L
        const val THREE_YEARS = 3L * 365 * 24 * 60 * 60 * 1_000L
    }
}
