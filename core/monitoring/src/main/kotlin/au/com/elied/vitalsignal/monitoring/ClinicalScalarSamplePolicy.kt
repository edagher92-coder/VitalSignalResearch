package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource

/**
 * Shared fail-closed gate for numeric values shown on a clinician observer
 * surface or projected into a clinical draft. These are broad ingestion safety
 * bounds, not diagnostic reference ranges and not evidence that a value is
 * normal.
 */
internal object ClinicalScalarSamplePolicy {
    fun rejectionCode(sample: LiveScalarSample): String? = when {
        sample.source == SensorSource.SIMULATOR -> "simulator-source-blocked"
        sample.source == SensorSource.USER_REPORTED -> "user-reported-source-blocked"
        !sample.value.isFinite() -> "non-finite-value-blocked"
        !isWithinReviewedBounds(sample.metric, sample.value) ->
            "value-outside-reviewed-display-bounds"
        else -> null
    }

    fun isWithinReviewedBounds(metric: SensorMetric, value: Double): Boolean = when {
        !value.isFinite() -> false
        else -> when (metric) {
        SensorMetric.HEART_RATE -> value in 30.0..240.0
        SensorMetric.RESPIRATORY_RATE -> value in 5.0..60.0
        SensorMetric.OXYGEN_SATURATION -> value in 50.0..100.0
        else -> false
        }
    }
}
