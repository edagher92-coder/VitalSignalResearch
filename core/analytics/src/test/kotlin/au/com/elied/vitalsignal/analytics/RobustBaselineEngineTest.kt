package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.BaselineContextKey
import au.com.elied.vitalsignal.model.BaselineKey
import au.com.elied.vitalsignal.model.MetricWindow
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.conservativeAcquisitionProfile
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustBaselineEngineTest {
    @Test
    fun outlierDoesNotRedefineBaseline() {
        val values = List(28) { index -> 58.0 + (index % 5) } + 120.0
        val windows = values.mapIndexed { index, value -> window(index, value) }
        val engine = RobustBaselineEngine()

        val baseline = engine.fit(
            key = key(),
            windows = windows,
            nowEpochMillis = NOW,
        )

        assertNotNull(baseline)
        assertEquals(60.0, baseline!!.median, 0.001)
        assertTrue(baseline.upperReference < 70.0)
    }

    @Test
    fun highQualityChangeProducesPositiveDeviation() {
        val baselineWindows = (0 until 28).map { window(it, 60.0 + (it % 3 - 1)) }
        val engine = RobustBaselineEngine()
        val baseline = engine.fit(
            key(),
            baselineWindows,
            nowEpochMillis = NOW,
        )!!

        val deviation = engine.compare(window(99, 70.0), baseline)
        assertTrue(deviation.robustZ > 3.0)
    }

    @Test
    fun wrongHourAndFutureWindowsCannotMatureBaseline() {
        val engine = RobustBaselineEngine()
        val wrongHour = (0 until 28).map { window(it, 60.0).copy(localHourBucket = 8) }
        val future = (0 until 28).map {
            window(it, 60.0).copy(
                startEpochMillis = NOW + DAY_MILLIS + it * 60_000L,
                endEpochMillis = NOW + DAY_MILLIS + (it + 1) * 60_000L,
            )
        }

        assertNull(
            engine.fit(
                key(),
                wrongHour,
                nowEpochMillis = NOW,
            ),
        )
        assertNull(
            engine.fit(
                key(),
                future,
                nowEpochMillis = NOW,
            ),
        )
    }

    @Test
    fun manySamplesFromOneDayCannotBecomeMatureBaseline() {
        val engine = RobustBaselineEngine()
        val oneDay = (0 until 40).map {
            val start = BASE_EPOCH_MILLIS + 7L * HOUR_MILLIS + it * 60_000L
            window(it, 60.0).copy(
                startEpochMillis = start,
                endEpochMillis = start + 30_000L,
                localDateIso = "2026-07-01",
            )
        }

        assertNull(
            engine.fit(
                key(),
                oneDay,
                nowEpochMillis = NOW,
            ),
        )
    }

    @Test
    fun deviceFirmwareProtocolAndEnvironmentMustMatchExactly() {
        val engine = RobustBaselineEngine()
        val mismatched = (0 until 28).map { index ->
            window(index, 60.0).copy(
                baselineContext = CONTEXT.copy(firmwareGeneration = "other-firmware"),
            )
        }

        assertNull(engine.fit(key(), mismatched, nowEpochMillis = NOW))
        val baseline = engine.fit(
            key(),
            (0 until 28).map { window(it, 60.0) },
            nowEpochMillis = NOW,
        )!!
        assertThrows(IllegalArgumentException::class.java) {
            engine.compare(
                window(99, 62.0).copy(
                    baselineContext = CONTEXT.copy(deviceGeneration = "other-device"),
                ),
                baseline,
            )
        }
    }

    @Test
    fun nonFiniteValuesAreRejectedAtTheWindowBoundary() {
        assertThrows(IllegalArgumentException::class.java) { window(1, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { window(1, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun staleDuplicateOrProvenanceReusedWindowsCannotMatureBaseline() {
        val engine = RobustBaselineEngine()
        val valid = (0 until 28).map { window(it, 60.0) }
        val staleNow = NOW + 100L * DAY_MILLIS
        assertNull(engine.fit(key(), valid, staleNow))

        val duplicateId = valid.mapIndexed { index, item ->
            if (index == 1) item.copy(id = valid.first().id) else item
        }
        assertNull(engine.fit(key(), duplicateId, NOW))

        val reusedProvenance = valid.mapIndexed { index, item ->
            if (index == 1) {
                item.copy(
                    provenanceIds = valid.first().provenanceIds,
                    acquisitionProfile = valid.first().acquisitionProfile,
                )
            } else {
                item
            }
        }
        assertNull(engine.fit(key(), reusedProvenance, NOW))
    }

    @Test
    fun replayIsRejectedBeforeFreshnessAndQualityFilters() {
        val engine = RobustBaselineEngine()
        val valid = (0 until 28).map { window(it, 60.0) }
        val replayedStale = valid + valid.first().copy(
            startEpochMillis = 0L,
            endEpochMillis = 60_000L,
            localDateIso = "1970-01-01",
        )
        val replayedLowQuality = valid + valid.first().copy(
            id = "low-quality-replay",
            quality = SignalQuality(0.10),
        )

        assertNull(engine.fit(key(), replayedStale, NOW))
        assertNull(engine.fit(key(), replayedLowQuality, NOW))
    }

    @Test
    fun localDateAndHourMustMatchTimestampAndLastUpdateUsesLatestSourceTime() {
        val engine = RobustBaselineEngine()
        val valid = (0 until 28).map { window(it, 60.0) }
        val mismatchedDate = valid.map { it.copy(localDateIso = "2020-01-01") }
        val mismatchedHour = valid.map { item ->
            item.copy(
                startEpochMillis = item.startEpochMillis + HOUR_MILLIS,
                endEpochMillis = item.endEpochMillis + HOUR_MILLIS,
            )
        }
        val mismatchedOffset = valid.map { it.copy(localOffsetMinutes = 660) }

        assertNull(engine.fit(key(), mismatchedDate, NOW))
        assertNull(engine.fit(key(), mismatchedHour, NOW))
        assertNull(engine.fit(key(), mismatchedOffset, NOW))
        assertEquals(valid.maxOf { it.endEpochMillis }, engine.fit(key(), valid, NOW)!!.lastUpdatedEpochMillis)
    }

    @Test
    fun maximumReferenceAgeBoundaryIsInclusiveAndCannotInflateEffectiveDays() {
        val engine = RobustBaselineEngine()
        val valid = (0 until 28).map { window(it, 60.0) }
        val oldestEnd = valid.first().endEpochMillis

        assertNotNull(engine.fit(key(), valid, oldestEnd + 90L * DAY_MILLIS))
        assertNull(engine.fit(key(), valid, oldestEnd + 90L * DAY_MILLIS + 1L))
    }

    @Test
    fun compareRejectsTimestampMismatchesFutureBaselineAndStaleBaseline() {
        val engine = RobustBaselineEngine()
        val baseline = engine.fit(key(), (0 until 28).map { window(it, 60.0) }, NOW)!!
        val validComparison = window(40, 62.0)

        assertNotNull(engine.compare(validComparison, baseline))
        assertThrows(IllegalArgumentException::class.java) {
            engine.compare(validComparison.copy(localDateIso = "2026-07-01"), baseline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.compare(validComparison, baseline.copy(lastUpdatedEpochMillis = validComparison.endEpochMillis + 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.compare(window(140, 62.0), baseline)
        }
    }

    @Test
    fun negativeTimestampsAndInvalidProvenanceAreRejectedAtTheWindowBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            window(1, 60.0).copy(startEpochMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            window(1, 60.0).copy(provenanceIds = listOf("sample-1", "sample-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            window(1, 60.0).copy(provenanceIds = listOf(""))
        }
    }

    private fun key() = BaselineKey(
        SensorMetric.HEART_RATE,
        7,
        ActivityState.RESTING,
        CONTEXT,
    )

    private fun window(index: Int, value: Double) = MetricWindow(
        id = "window-$index",
        metric = SensorMetric.HEART_RATE,
        source = SensorSource.GALAXY_WATCH_ULTRA_2,
        startEpochMillis = BASE_EPOCH_MILLIS + index * DAY_MILLIS + 7L * 60L * 60L * 1_000L,
        endEpochMillis = BASE_EPOCH_MILLIS + index * DAY_MILLIS + 7L * 60L * 60L * 1_000L + 60_000L,
        value = value,
        quality = SignalQuality(0.95),
        activityState = ActivityState.RESTING,
        localHourBucket = 7,
        localDateIso = LocalDate.of(2026, 7, 1).plusDays(index.toLong()).toString(),
        localOffsetMinutes = 600,
        baselineContext = CONTEXT,
        provenanceIds = listOf("sample-$index"),
        acquisitionProfile = conservativeAcquisitionProfile(
            SensorMetric.HEART_RATE,
            SensorSource.GALAXY_WATCH_ULTRA_2,
            listOf("sample-$index"),
        ),
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val HOUR_MILLIS = 60L * 60L * 1_000L
        val BASE_EPOCH_MILLIS = LocalDate.of(2026, 7, 1)
            .atStartOfDay(ZoneOffset.ofHours(10))
            .toInstant()
            .toEpochMilli()
        val NOW = BASE_EPOCH_MILLIS + 40L * DAY_MILLIS
        val CONTEXT = BaselineContextKey(
            deviceGeneration = "fixture-device-v1",
            firmwareGeneration = "fixture-firmware-v1",
            acquisitionProtocolVersion = "fixture-protocol-v1",
            environmentFingerprintSha256 = "b".repeat(64),
        )
    }
}
