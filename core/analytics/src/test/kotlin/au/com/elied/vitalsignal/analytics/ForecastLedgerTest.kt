package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.ForecastResolution
import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import au.com.elied.vitalsignal.model.HealthForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForecastLedgerTest {
    @Test
    fun prospectiveForecastIsScoredWithBrierLoss() {
        val ledger = ForecastLedger()
        val forecast = HealthForecast(
            id = "forecast-1",
            createdAtEpochMillis = 1L,
            endpoint = endpoint,
            probability = 0.80,
            lowerBound = 0.65,
            upperBound = 0.90,
            confidence = 0.75,
            modelVersion = "test-v1",
            featureSnapshotIds = listOf("features-1"),
            featureSchema = featureSchema,
            featureSnapshotHash = "a".repeat(64),
        )

        val audit = ledger.resolve(
            forecast,
            observedOutcome = 1.0,
            resolvedAtEpochMillis = forecast.targetEndEpochMillis,
        )
        assertEquals(ForecastResolution.CORRECT, audit.resolution)
        assertEquals(0.04, audit.brierScore!!, 0.0001)
    }

    @Test
    fun forecastCannotResolveBeforeTargetWindow() {
        val forecast = sampleForecast()

        assertThrows(IllegalArgumentException::class.java) {
            ForecastLedger().resolve(
                forecast,
                observedOutcome = 1.0,
                resolvedAtEpochMillis = forecast.targetEndEpochMillis - 1,
            )
        }
    }

    @Test
    fun missingOrNonBinaryOutcomeIsIndeterminateNotNegative() {
        val forecast = sampleForecast()
        val ledger = ForecastLedger()

        val missing = ledger.resolve(forecast, null, forecast.targetEndEpochMillis)
        val continuous = ledger.resolve(forecast, 0.5, forecast.targetEndEpochMillis)

        assertEquals(ForecastResolution.INDETERMINATE, missing.resolution)
        assertEquals(ForecastResolution.INDETERMINATE, continuous.resolution)
    }

    @Test
    fun calibrationBinsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            ForecastLedger().expectedCalibrationError(emptyList(), bins = 0)
        }
    }

    @Test
    fun committedForecastCannotBeOverwrittenOrResolvedTwiceDifferently() {
        val ledger = ForecastLedger()
        val forecast = sampleForecast()
        ledger.commit(forecast)

        assertThrows(IllegalArgumentException::class.java) {
            ledger.commit(forecast.copy(probability = 0.60))
        }

        val resolved = ledger.resolveCommitted(
            forecastId = forecast.id,
            observedOutcome = 1.0,
            resolvedAtEpochMillis = forecast.targetEndEpochMillis,
        )
        assertEquals(ForecastResolution.CORRECT, resolved.resolution)
        assertEquals(1, ledger.snapshot().size)

        assertThrows(IllegalArgumentException::class.java) {
            ledger.resolveCommitted(
                forecastId = forecast.id,
                observedOutcome = 0.0,
                resolvedAtEpochMillis = forecast.targetEndEpochMillis,
            )
        }
    }

    private fun sampleForecast() = HealthForecast(
        id = "forecast-sample",
        createdAtEpochMillis = 1_000L,
        endpoint = endpoint,
        probability = 0.70,
        lowerBound = 0.50,
        upperBound = 0.85,
        confidence = 0.60,
        modelVersion = "test-v1",
        featureSnapshotIds = listOf("features-1"),
        featureSchema = featureSchema,
        featureSnapshotHash = "b".repeat(64),
    )

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        val endpoint = ForecastEndpointDefinition.freeze(
            id = "ledger-energy-24h-point",
            version = "1.0.0",
            displayLabel = "24-hour point assessment (+24h to +25h)",
            positiveClassDefinition = "Frozen binary lower-than-personal-usual energy rule.",
            windowSemantics = ForecastWindowSemantics.POINT_ASSESSMENT,
            targetStartOffsetMillis = 24L * HOUR,
            targetEndOffsetMillis = 25L * HOUR,
        )
        val featureSchema = ForecastFeatureSchemaDefinition.freeze(
            id = "ledger-fixture-schema",
            version = "1.0.0",
            featureVersions = mapOf("fixture" to "1.0.0"),
            standardizationProtocol = "Deterministic ledger test fixture.",
        )
    }
}
