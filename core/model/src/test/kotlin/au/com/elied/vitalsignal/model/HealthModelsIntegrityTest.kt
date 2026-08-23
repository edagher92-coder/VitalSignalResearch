package au.com.elied.vitalsignal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthModelsIntegrityTest {
    @Test
    fun sensorObservationRejectsNonFiniteValues() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SensorObservation(
                    id = "observation-1",
                    metric = SensorMetric.HEART_RATE,
                    epochMillis = 1L,
                    value = value,
                    quality = SignalQuality(score = 0.95),
                    source = SensorSource.GALAXY_WATCH_ULTRA_2,
                    provenanceIds = listOf("raw-1"),
                )
            }
        }
    }

    @Test
    fun qualityReasonsAreAnImmutableEvidenceSnapshot() {
        val mutableReasons = mutableListOf("stable contact")
        val quality = SignalQuality(score = 0.95, reasons = mutableReasons)

        mutableReasons[0] = "changed after validation"

        assertEquals(listOf("stable contact"), quality.reasons)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (quality.reasons as MutableList<String>).clear()
        }
    }
}
