package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.BaselineDeviation
import au.com.elied.vitalsignal.model.BaselineKey
import au.com.elied.vitalsignal.model.DeviationDirection
import au.com.elied.vitalsignal.model.MetricWindow
import au.com.elied.vitalsignal.model.PersonalBaseline
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Builds circadian and context-specific baselines using robust statistics.
 * Median/MAD deliberately replace mean/standard deviation so isolated illness,
 * exercise and sensor artefacts do not redefine "normal" too quickly.
 */
class RobustBaselineEngine(
    private val targetMaturityDays: Int = 28,
    private val minimumSamples: Int = 20,
    private val maximumReferenceAgeMillis: Long = 90L * 24L * 60L * 60L * 1_000L,
    private val physiologicalNoiseFloor: Map<String, Double> = emptyMap(),
) {
    init {
        require(targetMaturityDays >= 28)
        require(minimumSamples >= 20)
        require(maximumReferenceAgeMillis in 28L * DAY_MILLIS..365L * DAY_MILLIS)
        require(physiologicalNoiseFloor.values.all { it > 0.0 })
    }

    fun fit(
        key: BaselineKey,
        windows: List<MetricWindow>,
        nowEpochMillis: Long,
    ): PersonalBaseline? {
        require(nowEpochMillis >= 0L)
        val contextMatched = windows
            .asSequence()
            .filter { it.metric == key.metric }
            .filter { it.activityState == key.activityState }
            .filter { it.localHourBucket == key.localHourBucket }
            .filter { it.baselineContext == key.context }
            .toList()

        // Identity collisions in the requested acquisition stratum are rejected
        // before freshness/quality filters. A replay must never become acceptable
        // merely because one copy was stale, future-dated or marked low quality.
        if (contextMatched.groupBy(MetricWindow::id).values.any { it.size > 1 }) return null
        val contextProvenance = contextMatched.flatMap(MetricWindow::provenanceIds)
        if (contextProvenance.distinct().size != contextProvenance.size) return null

        val qualified = contextMatched
            .asSequence()
            .filter { it.endEpochMillis <= nowEpochMillis }
            .filter { nowEpochMillis - it.endEpochMillis <= maximumReferenceAgeMillis }
            .filter { it.quality.usable }
            .filter(::localDateAndHourMatchTimestamp)
            .toList()

        val effectiveDays = qualified.map { it.localDateIso }.distinct().size
        val usable = qualified.map { it.value }.sorted()

        if (usable.size < minimumSamples || effectiveDays < targetMaturityDays) return null

        val median = median(usable)
        val rawMad = median(usable.map { abs(it - median) }.sorted())
        val noiseFloor = physiologicalNoiseFloor[key.metric.name] ?: defaultNoiseFloor(key.metric.name)
        val scaledMad = max(max(rawMad * MAD_TO_SIGMA, noiseFloor), MIN_SCALE)
        val dayMaturity = effectiveDays.toDouble() / targetMaturityDays.toDouble()
        val sampleMaturity = usable.size.toDouble() / minimumSamples.toDouble()
        val maturity = min(1.0, min(dayMaturity, sampleMaturity))

        return PersonalBaseline(
            key = key,
            median = median,
            scaledMad = scaledMad,
            lowerReference = median - REFERENCE_Z * scaledMad,
            upperReference = median + REFERENCE_Z * scaledMad,
            sampleCount = usable.size,
            effectiveDays = effectiveDays,
            lastUpdatedEpochMillis = qualified.maxOf(MetricWindow::endEpochMillis),
            maturity = maturity,
        )
    }

    fun compare(window: MetricWindow, baseline: PersonalBaseline): BaselineDeviation {
        require(window.metric == baseline.key.metric)
        require(window.activityState == baseline.key.activityState)
        require(window.localHourBucket == baseline.key.localHourBucket)
        require(window.baselineContext == baseline.key.context)
        require(localDateAndHourMatchTimestamp(window))
        require(window.endEpochMillis >= baseline.lastUpdatedEpochMillis)
        require(window.endEpochMillis - baseline.lastUpdatedEpochMillis <= maximumReferenceAgeMillis)
        val robustZ = (window.value - baseline.median) / baseline.scaledMad
        val direction = when {
            robustZ > EXPECTED_Z -> DeviationDirection.HIGHER
            robustZ < -EXPECTED_Z -> DeviationDirection.LOWER
            else -> DeviationDirection.WITHIN_EXPECTED
        }

        return BaselineDeviation(
            windowId = window.id,
            metric = window.metric,
            observed = window.value,
            expected = baseline.median,
            robustZ = robustZ,
            direction = direction,
            quality = window.quality,
            baselineMaturity = baseline.maturity,
            baselineSampleCount = baseline.sampleCount,
            provenanceIds = window.provenanceIds,
        )
    }

    private fun median(sorted: List<Double>): Double {
        require(sorted.isNotEmpty())
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun defaultNoiseFloor(metricName: String): Double = when (metricName) {
        "HEART_RATE" -> 1.5
        "HRV_RMSSD", "HRV_SDNN" -> 2.0
        "OXYGEN_SATURATION" -> 0.8
        "SKIN_TEMPERATURE" -> 0.12
        "RESPIRATORY_RATE" -> 0.6
        else -> 0.5
    }

    private fun localDateAndHourMatchTimestamp(window: MetricWindow): Boolean = try {
        val localTimestamp = Instant.ofEpochMilli(window.endEpochMillis)
            .atOffset(ZoneOffset.ofTotalSeconds(window.localOffsetMinutes * 60))
        localTimestamp.toLocalDate().toString() == window.localDateIso &&
            localTimestamp.hour == window.localHourBucket
    } catch (_: RuntimeException) {
        false
    }

    companion object {
        private const val MAD_TO_SIGMA = 1.4826
        private const val REFERENCE_Z = 2.5
        private const val EXPECTED_Z = 1.5
        private const val MIN_SCALE = 1e-6
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
