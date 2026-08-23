package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.ForecastAudit
import au.com.elied.vitalsignal.model.ForecastResolution
import au.com.elied.vitalsignal.model.HealthForecast
import kotlin.math.abs

/**
 * Append-only in-memory prospective ledger for simulator and unit testing.
 * A durable encrypted pilot store must preserve the same commit-before-outcome semantics.
 */
class ForecastLedger {
    private val records = linkedMapOf<String, ForecastAudit>()

    @Synchronized
    fun commit(forecast: HealthForecast): ForecastAudit {
        val existing = records[forecast.id]
        require(existing == null || existing.forecast == forecast) {
            "A committed forecast ID cannot be overwritten with different content"
        }
        if (existing != null) return existing
        return ForecastAudit(forecast = forecast).also { records[forecast.id] = it }
    }

    @Synchronized
    fun resolveCommitted(
        forecastId: String,
        observedOutcome: Double?,
        resolvedAtEpochMillis: Long,
        notes: String = "",
    ): ForecastAudit {
        val existing = requireNotNull(records[forecastId]) { "Forecast must be committed before resolution" }
        if (existing.resolution != ForecastResolution.PENDING) {
            require(
                existing.observedOutcome == observedOutcome &&
                    existing.resolvedAtEpochMillis == resolvedAtEpochMillis &&
                    existing.notes == notes,
            ) { "A resolved forecast audit cannot be changed" }
            return existing
        }
        return resolve(existing.forecast, observedOutcome, resolvedAtEpochMillis, notes).also {
            records[forecastId] = it
        }
    }

    @Synchronized
    fun snapshot(): List<ForecastAudit> = records.values.toList()

    /** Module-internal scoring primitive; app callers can resolve only a previously committed ID. */
    internal fun resolve(
        forecast: HealthForecast,
        observedOutcome: Double?,
        resolvedAtEpochMillis: Long,
        notes: String = "",
    ): ForecastAudit {
        require(resolvedAtEpochMillis >= forecast.targetEndEpochMillis) {
            "A forecast cannot be resolved before its target window ends"
        }
        if (observedOutcome == null || observedOutcome !in setOf(0.0, 1.0)) {
            return ForecastAudit(
                forecast = forecast,
                resolvedAtEpochMillis = resolvedAtEpochMillis,
                resolution = ForecastResolution.INDETERMINATE,
                notes = notes,
            )
        }

        val brier = (forecast.probability - observedOutcome) * (forecast.probability - observedOutcome)
        val predictedPositive = forecast.probability >= 0.50
        val observedPositive = observedOutcome >= 0.50
        return ForecastAudit(
            forecast = forecast,
            resolvedAtEpochMillis = resolvedAtEpochMillis,
            observedOutcome = observedOutcome,
            resolution = if (predictedPositive == observedPositive) ForecastResolution.CORRECT else ForecastResolution.INCORRECT,
            brierScore = brier,
            notes = notes,
        )
    }

    fun brierScore(audits: List<ForecastAudit>): Double? {
        val values = audits.mapNotNull { it.brierScore }
        return if (values.isEmpty()) null else values.average()
    }

    fun expectedCalibrationError(audits: List<ForecastAudit>, bins: Int = 10): Double? {
        require(bins > 0)
        val resolved = audits.filter { it.observedOutcome != null }
        if (resolved.isEmpty()) return null
        val endpointKeys = resolved.map {
            listOf(
                it.forecast.endpoint.id,
                it.forecast.endpoint.version,
                it.forecast.endpoint.definitionSha256,
                it.forecast.endpoint.targetStartOffsetMillis.toString(),
                it.forecast.endpoint.targetEndOffsetMillis.toString(),
                it.forecast.featureSchema.id,
                it.forecast.featureSchema.version,
                it.forecast.featureSchema.definitionSha256,
                it.forecast.featureSchema.featureKeys.sorted().joinToString(","),
                it.forecast.modelVersion,
                it.forecast.policyVersion,
            ).joinToString("|")
        }.distinct()
        require(endpointKeys.size == 1) {
            "Calibration must not mix endpoints, schemas, feature keys, models, or policies"
        }

        return (0 until bins).sumOf { index ->
            val lower = index.toDouble() / bins
            val upper = (index + 1).toDouble() / bins
            val bucket = resolved.filter {
                it.forecast.probability >= lower &&
                    (it.forecast.probability < upper || index == bins - 1)
            }
            if (bucket.isEmpty()) 0.0 else {
                val meanPrediction = bucket.map { it.forecast.probability }.average()
                val meanOutcome = bucket.mapNotNull { it.observedOutcome }.average()
                abs(meanPrediction - meanOutcome) * bucket.size.toDouble() / resolved.size.toDouble()
            }
        }
    }
}
