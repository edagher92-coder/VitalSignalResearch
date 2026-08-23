package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.AcquisitionOrigin
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.model.conservativeAcquisitionProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Explicit reasons why an activity or exercise interval was not observed.
 * Missingness is never converted to rest, inactivity, recovery, or normality.
 */
enum class ActivityGapReason {
    OFF_WRIST,
    CHARGING,
    BATTERY_DEPLETED,
    REBOOT_RECOVERY,
    PERMISSION_LOST,
    POOR_CONTACT,
    SENSOR_PREEMPTED,
    SYNC_DELAY,
    UNKNOWN,
}

data class ActivityDataGap(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val reason: ActivityGapReason,
    val provenanceId: String,
) {
    init {
        require(startEpochMillis >= 0L)
        require(endEpochMillis > startEpochMillis)
        require(startEpochMillis % 1_000L == 0L && endEpochMillis % 1_000L == 0L) {
            "Gap accounting is exact to whole seconds"
        }
        require(provenanceId.isNotBlank())
    }

    val durationSeconds: Long
        get() = (endEpochMillis - startEpochMillis) / 1_000L
}

/**
 * A finalized local-day movement aggregate with a complete observation ledger.
 * The accounted gap durations plus qualified duration must equal the declared
 * observation interval, preventing an incomplete day from masquerading as a
 * low-activity day.
 */
class DailyActivityObservation(
    val id: String,
    val localDateIso: String,
    val observationStartEpochMillis: Long,
    val observationEndEpochMillis: Long,
    val finalizedAtEpochMillis: Long,
    val source: SensorSource,
    val deviceGeneration: String,
    val firmwareGeneration: String,
    val acquisitionProtocolVersion: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeDurationSeconds: Long,
    val elevationGainMeters: Double,
    val qualifiedObservationSeconds: Long,
    val qualityScore: Double,
    gaps: List<ActivityDataGap>,
    provenanceIds: List<String>,
) {
    val gaps: List<ActivityDataGap> = immutableListSnapshot(gaps)
    val provenanceIds: List<String> = immutableListSnapshot(provenanceIds)

    init {
        require(id.isNotBlank())
        require(parseDate(localDateIso) != null)
        require(observationStartEpochMillis >= 0L)
        require(observationEndEpochMillis > observationStartEpochMillis)
        require(observationStartEpochMillis % 1_000L == 0L && observationEndEpochMillis % 1_000L == 0L)
        require(finalizedAtEpochMillis >= observationEndEpochMillis)
        require(deviceGeneration.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(acquisitionProtocolVersion.isNotBlank())
        require(steps >= 0L)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(activeDurationSeconds >= 0L)
        require(elevationGainMeters.isFinite() && elevationGainMeters >= 0.0)
        require(qualifiedObservationSeconds >= 0L)
        require(qualityScore in 0.0..1.0)
        require(this.provenanceIds.isNotEmpty())
        require(this.provenanceIds.all(String::isNotBlank))
        require(this.provenanceIds.distinct().size == this.provenanceIds.size)
        require(this.gaps.zipWithNext().all { (left, right) -> left.endEpochMillis <= right.startEpochMillis }) {
            "Activity data gaps must be ordered and non-overlapping"
        }
        require(this.gaps.all {
            it.startEpochMillis >= observationStartEpochMillis &&
                it.endEpochMillis <= observationEndEpochMillis &&
                it.provenanceId in this.provenanceIds
        })

        val intendedSeconds = observationDurationSeconds
        require(intendedSeconds in MIN_FINAL_DAY_SECONDS..MAX_FINAL_DAY_SECONDS) {
            "A daily activity observation must represent one finalized local day"
        }
        require(activeDurationSeconds <= qualifiedObservationSeconds)
        require(qualifiedObservationSeconds <= intendedSeconds)
        require(qualifiedObservationSeconds + this.gaps.sumOf(ActivityDataGap::durationSeconds) == intendedSeconds) {
            "Qualified and explicitly classified gap time must account for the entire observation day"
        }
    }

    val observationDurationSeconds: Long
        get() = (observationEndEpochMillis - observationStartEpochMillis) / 1_000L

    val qualifiedCoverage: Double
        get() = qualifiedObservationSeconds.toDouble() / observationDurationSeconds.toDouble()

    val distanceKilometres: Double
        get() = distanceMeters / 1_000.0

    fun copy(
        id: String = this.id,
        localDateIso: String = this.localDateIso,
        observationStartEpochMillis: Long = this.observationStartEpochMillis,
        observationEndEpochMillis: Long = this.observationEndEpochMillis,
        finalizedAtEpochMillis: Long = this.finalizedAtEpochMillis,
        source: SensorSource = this.source,
        deviceGeneration: String = this.deviceGeneration,
        firmwareGeneration: String = this.firmwareGeneration,
        acquisitionProtocolVersion: String = this.acquisitionProtocolVersion,
        steps: Long = this.steps,
        distanceMeters: Double = this.distanceMeters,
        activeDurationSeconds: Long = this.activeDurationSeconds,
        elevationGainMeters: Double = this.elevationGainMeters,
        qualifiedObservationSeconds: Long = this.qualifiedObservationSeconds,
        qualityScore: Double = this.qualityScore,
        gaps: List<ActivityDataGap> = this.gaps,
        provenanceIds: List<String> = this.provenanceIds,
    ) = DailyActivityObservation(
        id = id,
        localDateIso = localDateIso,
        observationStartEpochMillis = observationStartEpochMillis,
        observationEndEpochMillis = observationEndEpochMillis,
        finalizedAtEpochMillis = finalizedAtEpochMillis,
        source = source,
        deviceGeneration = deviceGeneration,
        firmwareGeneration = firmwareGeneration,
        acquisitionProtocolVersion = acquisitionProtocolVersion,
        steps = steps,
        distanceMeters = distanceMeters,
        activeDurationSeconds = activeDurationSeconds,
        elevationGainMeters = elevationGainMeters,
        qualifiedObservationSeconds = qualifiedObservationSeconds,
        qualityScore = qualityScore,
        gaps = gaps,
        provenanceIds = provenanceIds,
    )

    companion object {
        private const val MIN_FINAL_DAY_SECONDS = 20L * 60L * 60L
        private const val MAX_FINAL_DAY_SECONDS = 26L * 60L * 60L

        private fun parseDate(value: String): LocalDate? = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

enum class DailyActivityTrendState {
    LEARNING,
    ABSTAINED,
    DESCRIPTIVE_TREND_AVAILABLE,
}

data class ActivityVolumeVector(
    val steps: Double,
    val distanceKilometres: Double,
    val activeMinutes: Double,
    val elevationGainMeters: Double,
) {
    init {
        require(listOf(steps, distanceKilometres, activeMinutes, elevationGainMeters).all {
            it.isFinite() && it >= 0.0
        })
    }
}

data class RollingActivityReference(
    val windowDays: Int,
    val qualifiedDayCount: Int,
    val median: ActivityVolumeVector,
    val lowerQuartile: ActivityVolumeVector,
    val upperQuartile: ActivityVolumeVector,
) {
    init {
        require(windowDays in 7..365)
        require(qualifiedDayCount in 1..windowDays)
        require(lowerQuartile.steps <= median.steps && median.steps <= upperQuartile.steps)
        require(
            lowerQuartile.distanceKilometres <= median.distanceKilometres &&
                median.distanceKilometres <= upperQuartile.distanceKilometres,
        )
        require(lowerQuartile.activeMinutes <= median.activeMinutes && median.activeMinutes <= upperQuartile.activeMinutes)
        require(
            lowerQuartile.elevationGainMeters <= median.elevationGainMeters &&
                median.elevationGainMeters <= upperQuartile.elevationGainMeters,
        )
    }
}

data class ActivityVolumeDifference(
    val metric: String,
    val current: Double,
    val referenceMedian: Double,
    /** Null when the reference is zero or the finite inputs cannot yield a finite ratio. */
    val differencePercent: Double?,
    val unit: String,
) {
    init {
        require(metric.isNotBlank())
        require(current.isFinite() && current >= 0.0)
        require(referenceMedian.isFinite() && referenceMedian >= 0.0)
        differencePercent?.let { require(it.isFinite()) }
        require(unit.isNotBlank())
    }
}

class DailyActivityTrendAssessment(
    val state: DailyActivityTrendState,
    val current: ActivityVolumeVector?,
    val qualifiedCoverage: Double,
    gapSecondsByReason: Map<ActivityGapReason, Long>,
    references: List<RollingActivityReference>,
    differencesFrom28DayMedian: List<ActivityVolumeDifference>,
    val reason: String,
    val modelVersion: String = "daily-activity-trend-v1",
) {
    val gapSecondsByReason: Map<ActivityGapReason, Long> = immutableMapSnapshot(gapSecondsByReason)
    val references: List<RollingActivityReference> = immutableListSnapshot(references)
    val differencesFrom28DayMedian: List<ActivityVolumeDifference> =
        immutableListSnapshot(differencesFrom28DayMedian)

    init {
        require(qualifiedCoverage in 0.0..1.0)
        require(this.gapSecondsByReason.values.all { it >= 0L })
        require(this.references.map(RollingActivityReference::windowDays).distinct().size == this.references.size)
        require(reason.isNotBlank())
        require(modelVersion.isNotBlank())
        require((state == DailyActivityTrendState.ABSTAINED) == (current == null))
        if (state != DailyActivityTrendState.DESCRIPTIVE_TREND_AVAILABLE) {
            require(this.differencesFrom28DayMedian.isEmpty())
        }
    }
}

/**
 * Describes movement exposure against prior same-source, same-device history.
 * It deliberately does not infer health, fitness, illness, intent, or cause.
 */
class DailyActivityTrendEngine(
    private val minimumQualifiedCoverage: Double = 0.95,
    private val minimumQualityScore: Double = 0.80,
    referenceRequirements: Map<Int, Int> = linkedMapOf(
        7 to 5,
        28 to 20,
        90 to 60,
    ),
) {
    private val referenceRequirements: Map<Int, Int> = immutableMapSnapshot(referenceRequirements)

    init {
        require(minimumQualifiedCoverage in 0.90..1.0)
        require(minimumQualityScore in 0.75..1.0)
        require(referenceRequirements[28]?.let { it >= 20 } == true)
        require(referenceRequirements.keys.all { it in 7..365 })
        require(referenceRequirements.all { (window, count) -> count in 1..window })
    }

    fun assess(
        history: List<DailyActivityObservation>,
        current: DailyActivityObservation,
    ): DailyActivityTrendAssessment {
        val historySnapshot = immutableListSnapshot(history)
        val coverage = current.qualifiedCoverage
        val gaps = current.gaps
            .groupBy(ActivityDataGap::reason)
            .mapValues { (_, values) -> values.sumOf(ActivityDataGap::durationSeconds) }
            .toSortedMap(compareBy(ActivityGapReason::ordinal))

        if (coverage < minimumQualifiedCoverage || current.qualityScore < minimumQualityScore) {
            return DailyActivityTrendAssessment(
                state = DailyActivityTrendState.ABSTAINED,
                current = null,
                qualifiedCoverage = coverage,
                gapSecondsByReason = gaps,
                references = emptyList(),
                differencesFrom28DayMedian = emptyList(),
                reason = "The finalized day lacks enough qualified observation coverage; missing time is not inactivity",
            )
        }

        val currentDate = LocalDate.parse(current.localDateIso)
        val comparable = historySnapshot.filter { it.sameAcquisitionStratum(current) }
        if (hasIdentityOrProvenanceCollision(historySnapshot, current)) {
            return DailyActivityTrendAssessment(
                state = DailyActivityTrendState.ABSTAINED,
                current = null,
                qualifiedCoverage = coverage,
                gapSecondsByReason = gaps,
                references = emptyList(),
                differencesFrom28DayMedian = emptyList(),
                reason = "The personal activity reference contains duplicate identity or provenance evidence",
            )
        }

        val strictlyPriorQualified = comparable.filter { candidate ->
            val date = LocalDate.parse(candidate.localDateIso)
            date.isBefore(currentDate) &&
                candidate.finalizedAtEpochMillis <= current.observationStartEpochMillis &&
                candidate.qualifiedCoverage >= minimumQualifiedCoverage &&
                candidate.qualityScore >= minimumQualityScore
        }
        val references = referenceRequirements.entries.mapNotNull { (windowDays, minimumDays) ->
            val earliestInclusive = currentDate.minusDays(windowDays.toLong())
            val inWindow = strictlyPriorQualified.filter {
                val date = LocalDate.parse(it.localDateIso)
                !date.isBefore(earliestInclusive) && date.isBefore(currentDate)
            }
            val distinctDays = inWindow.distinctBy(DailyActivityObservation::localDateIso)
            if (distinctDays.size < minimumDays) null else buildReference(windowDays, distinctDays)
        }.sortedBy(RollingActivityReference::windowDays)

        val currentVector = current.toVolumeVector()
        val reference28 = references.firstOrNull { it.windowDays == 28 }
        if (reference28 == null) {
            return DailyActivityTrendAssessment(
                state = DailyActivityTrendState.LEARNING,
                current = currentVector,
                qualifiedCoverage = coverage,
                gapSecondsByReason = gaps,
                references = references,
                differencesFrom28DayMedian = emptyList(),
                reason = "Learning a prior-only 28-day same-source activity reference; no low- or high-activity conclusion is made",
            )
        }

        return DailyActivityTrendAssessment(
            state = DailyActivityTrendState.DESCRIPTIVE_TREND_AVAILABLE,
            current = currentVector,
            qualifiedCoverage = coverage,
            gapSecondsByReason = gaps,
            references = references,
            differencesFrom28DayMedian = differences(currentVector, reference28.median),
            reason = "Descriptive movement exposure versus qualified personal history; it does not identify illness, fitness, intent, or cause",
        )
    }

    private fun buildReference(
        windowDays: Int,
        observations: List<DailyActivityObservation>,
    ): RollingActivityReference {
        val vectors = observations.map { it.toVolumeVector() }
        return RollingActivityReference(
            windowDays = windowDays,
            qualifiedDayCount = observations.size,
            median = aggregate(vectors, 0.50),
            lowerQuartile = aggregate(vectors, 0.25),
            upperQuartile = aggregate(vectors, 0.75),
        )
    }

    private fun aggregate(vectors: List<ActivityVolumeVector>, quantile: Double) = ActivityVolumeVector(
        steps = quantile(vectors.map(ActivityVolumeVector::steps), quantile),
        distanceKilometres = quantile(vectors.map(ActivityVolumeVector::distanceKilometres), quantile),
        activeMinutes = quantile(vectors.map(ActivityVolumeVector::activeMinutes), quantile),
        elevationGainMeters = quantile(vectors.map(ActivityVolumeVector::elevationGainMeters), quantile),
    )

    private fun differences(current: ActivityVolumeVector, median: ActivityVolumeVector) = listOf(
        difference("steps", current.steps, median.steps, "steps"),
        difference("distance", current.distanceKilometres, median.distanceKilometres, "km"),
        difference("active-duration", current.activeMinutes, median.activeMinutes, "min"),
        difference("elevation-gain", current.elevationGainMeters, median.elevationGainMeters, "m"),
    )

    private fun difference(metric: String, current: Double, reference: Double, unit: String) =
        ActivityVolumeDifference(
            metric = metric,
            current = current,
            referenceMedian = reference,
            differencePercent = if (reference > 0.0) {
                ((current - reference) / reference * 100.0).takeIf(Double::isFinite)
            } else {
                null
            },
            unit = unit,
        )

    private fun quantile(values: List<Double>, probability: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = probability * (sorted.lastIndex)
        val lower = index.toInt()
        val upper = min(sorted.lastIndex, lower + 1)
        val fraction = index - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    private fun hasIdentityOrProvenanceCollision(
        history: List<DailyActivityObservation>,
        current: DailyActivityObservation,
    ): Boolean {
        val currentEvidence = current.provenanceIds.toSet()
        if (history.any { it.id == current.id || it.provenanceIds.any(currentEvidence::contains) }) return true
        val all = history.filter { it.sameAcquisitionStratum(current) } + current
        if (all.groupBy(DailyActivityObservation::id).values.any { it.size > 1 }) return true
        if (all.groupBy(DailyActivityObservation::localDateIso).values.any { it.size > 1 }) return true
        val allProvenance = all.flatMap(DailyActivityObservation::provenanceIds)
        return allProvenance.distinct().size != allProvenance.size
    }

    private fun DailyActivityObservation.sameAcquisitionStratum(other: DailyActivityObservation) =
        source == other.source &&
            deviceGeneration == other.deviceGeneration &&
            firmwareGeneration == other.firmwareGeneration &&
            acquisitionProtocolVersion == other.acquisitionProtocolVersion

    private fun DailyActivityObservation.toVolumeVector() = ActivityVolumeVector(
        steps = steps.toDouble(),
        distanceKilometres = distanceKilometres,
        activeMinutes = activeDurationSeconds / 60.0,
        elevationGainMeters = elevationGainMeters,
    )
}

data class ExerciseSourceMap(
    val heartRate: SensorSource,
    val heartRateOrigin: AcquisitionOrigin,
    val workload: SensorSource,
    val workloadOrigin: AcquisitionOrigin,
    val recoveryHeartRate: SensorSource,
    val recoveryHeartRateOrigin: AcquisitionOrigin,
    val dailyTotals: SensorSource,
    val dailyTotalsOrigin: AcquisitionOrigin,
) {
    init {
        requireOriginMatchesSource(heartRate, SensorMetric.HEART_RATE, heartRateOrigin)
        requireOriginMatchesSource(workload, SensorMetric.ACTIVITY_LOAD, workloadOrigin)
        requireOriginMatchesSource(recoveryHeartRate, SensorMetric.HEART_RATE, recoveryHeartRateOrigin)
        requireOriginMatchesSource(dailyTotals, SensorMetric.STEP_COUNT, dailyTotalsOrigin)
    }

    /**
     * Only physically distinct, known acquisition families may satisfy the
     * cross-family response-change rule. A shared/unknown pipeline remains
     * useful descriptively but cannot manufacture corroboration.
     */
    val hasIndependentCardiacAndWorkloadOrigins: Boolean
        get() = heartRateOrigin != workloadOrigin &&
            heartRateOrigin !in NON_INDEPENDENT_ORIGINS &&
            workloadOrigin !in NON_INDEPENDENT_ORIGINS

    private companion object {
        val NON_INDEPENDENT_ORIGINS = immutableSetSnapshot(
            listOf(
                AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
                AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE,
                AcquisitionOrigin.SIMULATOR_THERMAL_FIXTURE,
                AcquisitionOrigin.SIMULATOR_INERTIAL_FIXTURE,
                AcquisitionOrigin.SIMULATOR_ELECTRICAL_FIXTURE,
                AcquisitionOrigin.SIMULATOR_SHARED_FIXTURE,
                AcquisitionOrigin.USER_REPORTED,
            ),
        )

        fun requireOriginMatchesSource(
            source: SensorSource,
            metric: SensorMetric,
            declaredOrigin: AcquisitionOrigin,
        ) {
            val expected = conservativeAcquisitionProfile(
                metric = metric,
                source = source,
                evidenceProvenanceIds = listOf("source-map-validation"),
            ).primaryOrigin
            require(declaredOrigin == expected) {
                "Acquisition origin $declaredOrigin is inconsistent with $source for $metric"
            }
        }
    }
}

data class PersonalHeartRateBand(
    val id: String,
    val lowerInclusiveBpm: Double,
    val upperExclusiveBpm: Double,
) {
    init {
        require(id.isNotBlank())
        require(lowerInclusiveBpm.isFinite() && upperExclusiveBpm.isFinite())
        require(lowerInclusiveBpm >= 0.0)
        require(upperExclusiveBpm <= 300.0)
        require(upperExclusiveBpm > lowerInclusiveBpm)
    }
}

/** Versioned personal/reviewed bands; no age-predicted maximum is created here. */
class PersonalHeartRateBandSet(
    val definitionId: String,
    val version: String,
    bands: List<PersonalHeartRateBand>,
    provenanceIds: List<String>,
) {
    val bands: List<PersonalHeartRateBand> = immutableListSnapshot(bands)
    val provenanceIds: List<String> = immutableListSnapshot(provenanceIds)

    init {
        require(definitionId.isNotBlank())
        require(version.isNotBlank())
        require(this.bands.size >= 2)
        require(this.bands.map(PersonalHeartRateBand::id).distinct().size == this.bands.size)
        require(this.bands.zipWithNext().all { (left, right) ->
            left.upperExclusiveBpm <= right.lowerInclusiveBpm
        })
        require(this.provenanceIds.isNotEmpty())
        require(this.provenanceIds.all(String::isNotBlank))
        require(this.provenanceIds.distinct().size == this.provenanceIds.size)
    }

    val digestSha256: String
        get() = sha256(
            buildString {
                append(definitionId).append('|').append(version)
                bands.forEach {
                    append('|').append(it.id)
                    append(':').append(java.lang.Double.toHexString(it.lowerInclusiveBpm))
                    append(':').append(java.lang.Double.toHexString(it.upperExclusiveBpm))
                }
                provenanceIds.sorted().forEach { append('|').append(it) }
            },
        )

    fun copy(
        definitionId: String = this.definitionId,
        version: String = this.version,
        bands: List<PersonalHeartRateBand> = this.bands,
        provenanceIds: List<String> = this.provenanceIds,
    ) = PersonalHeartRateBandSet(definitionId, version, bands, provenanceIds)
}

data class MatchedWorkloadTarget(
    val targetDistanceMeters: Double,
    val distanceToleranceFraction: Double,
    val targetActiveDurationSeconds: Long,
    val durationToleranceFraction: Double,
    val targetMeanSpeedMps: Double,
    val speedToleranceFraction: Double,
    val targetElevationGainMeters: Double,
    val elevationToleranceMeters: Double,
    val targetMeanCadenceSpm: Double?,
    val cadenceToleranceFraction: Double?,
    val minimumAmbientTemperatureC: Double?,
    val maximumAmbientTemperatureC: Double?,
) {
    init {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters > 0.0)
        require(distanceToleranceFraction in 0.0..0.20)
        require(targetActiveDurationSeconds >= 60L)
        require(durationToleranceFraction in 0.0..0.20)
        require(targetMeanSpeedMps.isFinite() && targetMeanSpeedMps > 0.0)
        require(speedToleranceFraction in 0.0..0.20)
        require(targetElevationGainMeters.isFinite() && targetElevationGainMeters >= 0.0)
        require(elevationToleranceMeters.isFinite() && elevationToleranceMeters >= 0.0)
        require((targetMeanCadenceSpm == null) == (cadenceToleranceFraction == null))
        targetMeanCadenceSpm?.let { require(it.isFinite() && it > 0.0) }
        cadenceToleranceFraction?.let { require(it in 0.0..0.20) }
        require((minimumAmbientTemperatureC == null) == (maximumAmbientTemperatureC == null))
        if (minimumAmbientTemperatureC != null && maximumAmbientTemperatureC != null) {
            require(minimumAmbientTemperatureC.isFinite() && maximumAmbientTemperatureC.isFinite())
            require(maximumAmbientTemperatureC >= minimumAmbientTemperatureC)
        }
    }
}

data class ExerciseComparabilityKey(
    val exerciseType: String,
    val protocolId: String,
    val protocolVersion: String,
    val deviceGeneration: String,
    val firmwareGeneration: String,
    val sourceMap: ExerciseSourceMap,
    val routeOrEquipmentFingerprintSha256: String,
    /** A reviewed condition band, not a claim that outdoor weather was identical. */
    val environmentBandFingerprintSha256: String,
    val workloadModelId: String,
    val workloadModelVersion: String,
    val recoveryProtocolId: String,
    val recoveryProtocolVersion: String,
) {
    init {
        require(exerciseType.isNotBlank())
        require(protocolId.isNotBlank())
        require(protocolVersion.isNotBlank())
        require(deviceGeneration.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(routeOrEquipmentFingerprintSha256.matches(SHA256_REGEX))
        require(environmentBandFingerprintSha256.matches(SHA256_REGEX))
        require(workloadModelId.isNotBlank())
        require(workloadModelVersion.isNotBlank())
        require(recoveryProtocolId.isNotBlank())
        require(recoveryProtocolVersion.isNotBlank())
    }
}

data class ExerciseHeartRateInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val heartRateBpm: Double,
    val quality: SignalQuality,
    val provenanceId: String,
) {
    init {
        require(startEpochMillis >= 0L)
        require(endEpochMillis > startEpochMillis)
        require(heartRateBpm.isFinite() && heartRateBpm in 20.0..260.0)
        require(provenanceId.isNotBlank())
    }
}

/**
 * A time segment from a versioned protocol-specific workload transform. Raw
 * speed, grade and cadence are retained; protocolWorkloadUnits is never mixed
 * across workload model versions.
 */
data class ExerciseWorkloadInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val speedMetersPerSecond: Double,
    val gradePercent: Double,
    val cadenceStepsPerMinute: Double?,
    val protocolWorkloadUnits: Double,
    val quality: SignalQuality,
    val provenanceId: String,
) {
    init {
        require(startEpochMillis >= 0L)
        require(endEpochMillis > startEpochMillis)
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(gradePercent.isFinite() && gradePercent in -40.0..40.0)
        cadenceStepsPerMinute?.let { require(it.isFinite() && it >= 0.0) }
        require(protocolWorkloadUnits.isFinite() && protocolWorkloadUnits > 0.0)
        require(provenanceId.isNotBlank())
    }
}

data class ExerciseRecoveryHeartRatePoint(
    val offsetSeconds: Int,
    val observedAtEpochMillis: Long,
    val averagingWindowSeconds: Int,
    val heartRateBpm: Double,
    val quality: SignalQuality,
    val provenanceId: String,
) {
    init {
        require(offsetSeconds > 0)
        require(observedAtEpochMillis >= 0L)
        require(averagingWindowSeconds in 5..30)
        require(heartRateBpm.isFinite() && heartRateBpm in 20.0..260.0)
        require(provenanceId.isNotBlank())
    }
}

class ExerciseSessionObservation(
    val id: String,
    val localDateIso: String,
    val startedAtEpochMillis: Long,
    val activeEndedAtEpochMillis: Long,
    val recoveryEndedAtEpochMillis: Long,
    val comparabilityKey: ExerciseComparabilityKey,
    val workloadTarget: MatchedWorkloadTarget,
    heartRateBands: PersonalHeartRateBandSet,
    val eligibilityReceiptId: String,
    val humanConcern: HumanConcernState,
    val sessionQualityScore: Double,
    val sessionQualityEvaluatorVersion: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeDurationSeconds: Long,
    val elevationGainMeters: Double,
    val restingHeartRateBpm: Double,
    val observedAmbientTemperatureC: Double?,
    summaryQuality: SignalQuality,
    restingHeartRateQuality: SignalQuality,
    ambientTemperatureQuality: SignalQuality?,
    val summaryProvenanceId: String,
    val restingHeartRateProvenanceId: String,
    val ambientTemperatureProvenanceId: String?,
    heartRateIntervals: List<ExerciseHeartRateInterval>,
    workloadIntervals: List<ExerciseWorkloadInterval>,
    recoveryHeartRatePoints: List<ExerciseRecoveryHeartRatePoint>,
    gaps: List<ActivityDataGap>,
    provenanceIds: List<String>,
) {
    val summaryQuality: SignalQuality = summaryQuality.immutableSnapshot()
    val restingHeartRateQuality: SignalQuality = restingHeartRateQuality.immutableSnapshot()
    val ambientTemperatureQuality: SignalQuality? = ambientTemperatureQuality?.immutableSnapshot()
    val heartRateBands: PersonalHeartRateBandSet = heartRateBands.copy()
    val heartRateIntervals: List<ExerciseHeartRateInterval> = immutableListSnapshot(
        heartRateIntervals.map { it.copy(quality = it.quality.immutableSnapshot()) },
    )
    val workloadIntervals: List<ExerciseWorkloadInterval> = immutableListSnapshot(
        workloadIntervals.map { it.copy(quality = it.quality.immutableSnapshot()) },
    )
    val recoveryHeartRatePoints: List<ExerciseRecoveryHeartRatePoint> = immutableListSnapshot(
        recoveryHeartRatePoints.map { it.copy(quality = it.quality.immutableSnapshot()) },
    )
    val gaps: List<ActivityDataGap> = immutableListSnapshot(gaps)
    val provenanceIds: List<String> = immutableListSnapshot(provenanceIds)

    init {
        require(id.isNotBlank())
        require(parseLocalDate(localDateIso) != null)
        require(startedAtEpochMillis >= 0L)
        require(activeEndedAtEpochMillis > startedAtEpochMillis)
        require(activeEndedAtEpochMillis <= Long.MAX_VALUE - MINIMUM_RECOVERY_MILLIS) {
            "Active end is too close to the timestamp limit for the fixed recovery protocol"
        }
        require(recoveryEndedAtEpochMillis >= activeEndedAtEpochMillis + MINIMUM_RECOVERY_MILLIS)
        require(eligibilityReceiptId.isNotBlank())
        require(sessionQualityScore in 0.0..1.0)
        require(sessionQualityEvaluatorVersion.isNotBlank())
        require(steps >= 0L)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(activeDurationSeconds in 60L..MAX_ACTIVE_DURATION_SECONDS)
        require(elevationGainMeters.isFinite() && elevationGainMeters >= 0.0)
        require(restingHeartRateBpm.isFinite() && restingHeartRateBpm in 20.0..180.0)
        observedAmbientTemperatureC?.let { require(it.isFinite() && it in -50.0..70.0) }
        require(summaryProvenanceId.isNotBlank())
        require(restingHeartRateProvenanceId.isNotBlank())
        require((observedAmbientTemperatureC == null) == (this.ambientTemperatureQuality == null)) {
            "Ambient temperature and its quality evidence must be present together"
        }
        require((observedAmbientTemperatureC == null) == (ambientTemperatureProvenanceId == null)) {
            "Ambient temperature and its provenance must be present together"
        }
        ambientTemperatureProvenanceId?.let { require(it.isNotBlank()) }
        require(this.heartRateIntervals.isNotEmpty())
        require(this.workloadIntervals.isNotEmpty())
        require(this.recoveryHeartRatePoints.isNotEmpty())
        require(this.heartRateIntervals.map { it.quality.evaluatorVersion }.distinct().size == 1) {
            "Heart-rate quality evaluation must use one version per session"
        }
        require(this.workloadIntervals.map { it.quality.evaluatorVersion }.distinct().size == 1) {
            "Workload quality evaluation must use one version per session"
        }
        require(this.recoveryHeartRatePoints.map { it.quality.evaluatorVersion }.distinct().size == 1) {
            "Recovery quality evaluation must use one version per session"
        }
        require(this.provenanceIds.isNotEmpty())
        require(this.provenanceIds.all(String::isNotBlank))
        require(this.provenanceIds.distinct().size == this.provenanceIds.size)
        require(this.heartRateIntervals.zipWithNext().all { (left, right) ->
            left.endEpochMillis <= right.startEpochMillis
        }) { "Heart-rate intervals must be ordered and non-overlapping" }
        require(this.workloadIntervals.zipWithNext().all { (left, right) ->
            left.endEpochMillis <= right.startEpochMillis
        }) { "Workload intervals must be ordered and non-overlapping" }
        require(this.gaps.zipWithNext().all { (left, right) -> left.endEpochMillis <= right.startEpochMillis }) {
            "Exercise gaps must be ordered and non-overlapping"
        }
        require(this.heartRateIntervals.all {
            it.startEpochMillis >= startedAtEpochMillis && it.endEpochMillis <= activeEndedAtEpochMillis
        })
        require(this.workloadIntervals.all {
            it.startEpochMillis >= startedAtEpochMillis && it.endEpochMillis <= activeEndedAtEpochMillis
        })
        require(this.recoveryHeartRatePoints.map(ExerciseRecoveryHeartRatePoint::offsetSeconds).distinct().size ==
            this.recoveryHeartRatePoints.size)
        require(this.recoveryHeartRatePoints.all {
            it.observedAtEpochMillis in activeEndedAtEpochMillis..recoveryEndedAtEpochMillis
        })
        require(this.gaps.all {
            it.startEpochMillis >= startedAtEpochMillis &&
                it.endEpochMillis <= recoveryEndedAtEpochMillis
        })
        val evidenceIds = buildList {
            add(summaryProvenanceId)
            add(restingHeartRateProvenanceId)
            ambientTemperatureProvenanceId?.let(::add)
            addAll(this@ExerciseSessionObservation.heartRateIntervals.map(ExerciseHeartRateInterval::provenanceId))
            addAll(this@ExerciseSessionObservation.workloadIntervals.map(ExerciseWorkloadInterval::provenanceId))
            addAll(this@ExerciseSessionObservation.recoveryHeartRatePoints.map(ExerciseRecoveryHeartRatePoint::provenanceId))
            addAll(this@ExerciseSessionObservation.gaps.map(ActivityDataGap::provenanceId))
            addAll(this@ExerciseSessionObservation.heartRateBands.provenanceIds)
        }
        require(evidenceIds.distinct().size == evidenceIds.size) {
            "Each exercise measurement and configuration item requires distinct provenance"
        }
        require(evidenceIds.all { it in this.provenanceIds }) {
            "All exercise components and band definitions must bind to session provenance"
        }
        require(this.provenanceIds.toSet() == evidenceIds.toSet()) {
            "Unbound exercise provenance is not accepted"
        }
    }

    fun copy(
        id: String = this.id,
        localDateIso: String = this.localDateIso,
        startedAtEpochMillis: Long = this.startedAtEpochMillis,
        activeEndedAtEpochMillis: Long = this.activeEndedAtEpochMillis,
        recoveryEndedAtEpochMillis: Long = this.recoveryEndedAtEpochMillis,
        comparabilityKey: ExerciseComparabilityKey = this.comparabilityKey,
        workloadTarget: MatchedWorkloadTarget = this.workloadTarget,
        heartRateBands: PersonalHeartRateBandSet = this.heartRateBands,
        eligibilityReceiptId: String = this.eligibilityReceiptId,
        humanConcern: HumanConcernState = this.humanConcern,
        sessionQualityScore: Double = this.sessionQualityScore,
        sessionQualityEvaluatorVersion: String = this.sessionQualityEvaluatorVersion,
        steps: Long = this.steps,
        distanceMeters: Double = this.distanceMeters,
        activeDurationSeconds: Long = this.activeDurationSeconds,
        elevationGainMeters: Double = this.elevationGainMeters,
        restingHeartRateBpm: Double = this.restingHeartRateBpm,
        observedAmbientTemperatureC: Double? = this.observedAmbientTemperatureC,
        summaryQuality: SignalQuality = this.summaryQuality,
        restingHeartRateQuality: SignalQuality = this.restingHeartRateQuality,
        ambientTemperatureQuality: SignalQuality? = this.ambientTemperatureQuality,
        summaryProvenanceId: String = this.summaryProvenanceId,
        restingHeartRateProvenanceId: String = this.restingHeartRateProvenanceId,
        ambientTemperatureProvenanceId: String? = this.ambientTemperatureProvenanceId,
        heartRateIntervals: List<ExerciseHeartRateInterval> = this.heartRateIntervals,
        workloadIntervals: List<ExerciseWorkloadInterval> = this.workloadIntervals,
        recoveryHeartRatePoints: List<ExerciseRecoveryHeartRatePoint> = this.recoveryHeartRatePoints,
        gaps: List<ActivityDataGap> = this.gaps,
        provenanceIds: List<String> = this.provenanceIds,
    ) = ExerciseSessionObservation(
        id = id,
        localDateIso = localDateIso,
        startedAtEpochMillis = startedAtEpochMillis,
        activeEndedAtEpochMillis = activeEndedAtEpochMillis,
        recoveryEndedAtEpochMillis = recoveryEndedAtEpochMillis,
        comparabilityKey = comparabilityKey,
        workloadTarget = workloadTarget,
        heartRateBands = heartRateBands,
        eligibilityReceiptId = eligibilityReceiptId,
        humanConcern = humanConcern,
        sessionQualityScore = sessionQualityScore,
        sessionQualityEvaluatorVersion = sessionQualityEvaluatorVersion,
        steps = steps,
        distanceMeters = distanceMeters,
        activeDurationSeconds = activeDurationSeconds,
        elevationGainMeters = elevationGainMeters,
        restingHeartRateBpm = restingHeartRateBpm,
        observedAmbientTemperatureC = observedAmbientTemperatureC,
        summaryQuality = summaryQuality,
        restingHeartRateQuality = restingHeartRateQuality,
        ambientTemperatureQuality = ambientTemperatureQuality,
        summaryProvenanceId = summaryProvenanceId,
        restingHeartRateProvenanceId = restingHeartRateProvenanceId,
        ambientTemperatureProvenanceId = ambientTemperatureProvenanceId,
        heartRateIntervals = heartRateIntervals,
        workloadIntervals = workloadIntervals,
        recoveryHeartRatePoints = recoveryHeartRatePoints,
        gaps = gaps,
        provenanceIds = provenanceIds,
    )

    private companion object {
        const val MINIMUM_RECOVERY_MILLIS = 300_000L
        const val MAX_ACTIVE_DURATION_SECONDS = 24L * 60L * 60L

        fun parseLocalDate(value: String): LocalDate? = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

data class ExerciseZoneDuration(
    val bandId: String,
    val durationSeconds: Double,
) {
    init {
        require(bandId.isNotBlank())
        require(durationSeconds.isFinite() && durationSeconds >= 0.0)
    }
}

class ExerciseResponseFeatureSet(
    val steps: Long,
    val distanceKilometres: Double,
    val activeMinutes: Double,
    val elevationGainMeters: Double,
    val timeWeightedAverageHeartRateBpm: Double,
    /** Duration-weighted 95th percentile; never a one-sample maximum. */
    val persistentPeakHeartRateBpm: Double,
    zoneDurations: List<ExerciseZoneDuration>,
    /** Qualified HR time not covered by a versioned personal band. */
    val unclassifiedZoneDurationSeconds: Double,
    val heartRateCoverage: Double,
    val workloadCoverage: Double,
    val cadenceCoverage: Double,
    val meanSpeedMetersPerSecond: Double,
    val meanCadenceStepsPerMinute: Double?,
    /**
     * Time-weighted heart-rate elevation relative to the session's qualified
     * protocol resting reference per versioned workload unit. It has no
     * standalone diagnostic, treatment or fitness meaning.
     */
    val matchedWorkloadCardiacCost: Double,
    recoveryDropBpmByOffsetSeconds: Map<Int, Double>,
    val cardiacDriftPercent: Double,
    provenanceIds: List<String>,
) {
    val zoneDurations: List<ExerciseZoneDuration> = immutableListSnapshot(zoneDurations)
    val recoveryDropBpmByOffsetSeconds: Map<Int, Double> =
        immutableMapSnapshot(recoveryDropBpmByOffsetSeconds)
    val provenanceIds: List<String> = immutableListSnapshot(provenanceIds)

    init {
        require(steps >= 0L)
        require(distanceKilometres.isFinite() && distanceKilometres >= 0.0)
        require(activeMinutes.isFinite() && activeMinutes > 0.0)
        require(elevationGainMeters.isFinite() && elevationGainMeters >= 0.0)
        require(timeWeightedAverageHeartRateBpm.isFinite() && timeWeightedAverageHeartRateBpm in 20.0..260.0)
        require(persistentPeakHeartRateBpm.isFinite() && persistentPeakHeartRateBpm in 20.0..260.0)
        require(this.zoneDurations.map(ExerciseZoneDuration::bandId).distinct().size == this.zoneDurations.size)
        require(unclassifiedZoneDurationSeconds.isFinite() && unclassifiedZoneDurationSeconds >= 0.0)
        require(heartRateCoverage in 0.0..1.0)
        require(workloadCoverage in 0.0..1.0)
        require(cadenceCoverage in 0.0..1.0)
        require(meanSpeedMetersPerSecond.isFinite() && meanSpeedMetersPerSecond >= 0.0)
        meanCadenceStepsPerMinute?.let { require(it.isFinite() && it >= 0.0) }
        require(matchedWorkloadCardiacCost.isFinite())
        require(this.recoveryDropBpmByOffsetSeconds.keys == setOf(60, 120, 300))
        require(this.recoveryDropBpmByOffsetSeconds.values.all(Double::isFinite))
        require(cardiacDriftPercent.isFinite())
        require(this.provenanceIds.isNotEmpty())
        require(this.provenanceIds.all(String::isNotBlank))
        require(this.provenanceIds.distinct().size == this.provenanceIds.size)
    }
}

enum class ExerciseTrendState {
    LEARNING,
    ABSTAINED,
    HUMAN_CONCERN_REVIEW,
    WITHIN_PERSONAL_RANGE,
    POSSIBLE_RESPONSE_CHANGE,
}

class ExerciseTrendAssessment(
    val state: ExerciseTrendState,
    val featureSet: ExerciseResponseFeatureSet?,
    responseAssessment: StandardizedResponseAssessment?,
    gapReasons: Set<ActivityGapReason>,
    val reason: String,
    val modelVersion: String = "activity-exercise-response-v1",
) {
    val responseAssessment: StandardizedResponseAssessment? = responseAssessment?.immutableSnapshot()
    val gapReasons: Set<ActivityGapReason> = immutableSetSnapshot(gapReasons)

    init {
        require(reason.isNotBlank())
        require(modelVersion.isNotBlank())
        if (state == ExerciseTrendState.LEARNING ||
            state == ExerciseTrendState.WITHIN_PERSONAL_RANGE ||
            state == ExerciseTrendState.POSSIBLE_RESPONSE_CHANGE
        ) {
            require(featureSet != null && this.responseAssessment != null)
        }
        if (state == ExerciseTrendState.HUMAN_CONCERN_REVIEW) require(featureSet == null)
    }
}

/**
 * Extracts auditable exercise dose/response/recovery features and delegates
 * personal change detection to [StandardizedResponseEngine]. This avoids a
 * second competing baseline algorithm while adding the missing workload,
 * coverage, gap, provenance, zone and recovery contracts.
 */
class ActivityExerciseTrendEngine(
    eligibilityVerifier: StandardizedResponseEligibilityVerifier,
    private val minimumHeartRateCoverage: Double = 0.90,
    private val minimumWorkloadCoverage: Double = 0.90,
    private val minimumSessionQuality: Double = 0.85,
    requiredRecoveryOffsetsSeconds: Set<Int> = linkedSetOf(60, 120, 300),
) {
    private val requiredRecoveryOffsetsSeconds: Set<Int> =
        immutableSetSnapshot(requiredRecoveryOffsetsSeconds)
    private val responseEngine = StandardizedResponseEngine(
        eligibilityVerifier = eligibilityVerifier,
        minimumReferenceEpisodes = 12,
        minimumReferenceDays = 28,
        minimumCurrentQuality = minimumSessionQuality,
        minimumReferenceQuality = 0.75,
        minimumScaleByFeature = mapOf(
            FEATURE_AVERAGE_HR to 1.0,
            FEATURE_PERSISTENT_PEAK_HR to 1.5,
            FEATURE_CARDIAC_COST to 0.75,
            FEATURE_RECOVERY_60 to 1.5,
            FEATURE_RECOVERY_120 to 1.5,
            FEATURE_RECOVERY_300 to 1.5,
            FEATURE_CARDIAC_DRIFT to 1.5,
            FEATURE_MEAN_SPEED to 0.05,
            FEATURE_MEAN_CADENCE to 1.5,
        ),
    )

    init {
        require(minimumHeartRateCoverage in 0.85..1.0)
        require(minimumWorkloadCoverage in 0.85..1.0)
        require(minimumSessionQuality in 0.80..1.0)
        require(requiredRecoveryOffsetsSeconds == setOf(60, 120, 300)) {
            "Version 1 requires fixed 60, 120 and 300 second recovery points"
        }
    }

    fun assess(
        history: List<ExerciseSessionObservation>,
        current: ExerciseSessionObservation,
    ): ExerciseTrendAssessment {
        val historySnapshot = immutableListSnapshot(history)
        if (current.humanConcern != HumanConcernState.NO_CONCERN_REPORTED) {
            return ExerciseTrendAssessment(
                state = ExerciseTrendState.HUMAN_CONCERN_REVIEW,
                featureSet = null,
                responseAssessment = null,
                gapReasons = current.gaps.mapTo(linkedSetOf(), ActivityDataGap::reason),
                reason = "A human concern is present or was not captured; exercise sensor features cannot clear it",
            )
        }
        if (current.gaps.isNotEmpty()) {
            return abstained(
                reason = "The exercise or fixed recovery interval contains explicitly unobserved time; no physiological conclusion is made",
                gapReasons = current.gaps.mapTo(linkedSetOf(), ActivityDataGap::reason),
            )
        }
        val currentExtraction = extract(current)
        if (currentExtraction is ExerciseExtractionFailure) {
            return abstained(currentExtraction.reason)
        }
        currentExtraction as ExerciseExtractionSuccess

        val comparable = historySnapshot.filter { it.comparabilityKey == current.comparabilityKey }
        if (hasIdentityOrProvenanceCollision(historySnapshot, current)) {
            return abstained("The matched exercise reference contains duplicate identity or provenance evidence")
        }
        val currentDate = LocalDate.parse(current.localDateIso)
        val priorComparable = comparable.filter { candidate ->
            val candidateDate = LocalDate.parse(candidate.localDateIso)
            candidate.recoveryEndedAtEpochMillis <= current.startedAtEpochMillis &&
                !candidateDate.isAfter(currentDate)
        }
        val referenceEpisodes = priorComparable.mapNotNull { candidate ->
            if (candidate.humanConcern != HumanConcernState.NO_CONCERN_REPORTED || candidate.gaps.isNotEmpty()) {
                null
            } else {
                (extract(candidate) as? ExerciseExtractionSuccess)?.episode
            }
        }
        val response = responseEngine.assess(referenceEpisodes, currentExtraction.episode)
        return ExerciseTrendAssessment(
            state = when (response.state) {
                ResponseAssessmentState.LEARNING -> ExerciseTrendState.LEARNING
                ResponseAssessmentState.ABSTAINED -> ExerciseTrendState.ABSTAINED
                ResponseAssessmentState.HUMAN_CONCERN_REVIEW -> ExerciseTrendState.HUMAN_CONCERN_REVIEW
                ResponseAssessmentState.WITHIN_PERSONAL_RANGE -> ExerciseTrendState.WITHIN_PERSONAL_RANGE
                ResponseAssessmentState.POSSIBLE_RESPONSE_CHANGE -> ExerciseTrendState.POSSIBLE_RESPONSE_CHANGE
            },
            featureSet = currentExtraction.features,
            responseAssessment = response,
            gapReasons = emptySet(),
            reason = when (response.state) {
                ResponseAssessmentState.POSSIBLE_RESPONSE_CHANGE ->
                    "A repeatable workload response changed across separately sourced cardiac and movement feature families; cause is unknown and this is not a diagnosis"
                ResponseAssessmentState.WITHIN_PERSONAL_RANGE ->
                    "This qualified session did not meet the conservative cross-family personal change rule"
                else -> response.reason
            },
        )
    }

    private fun extract(session: ExerciseSessionObservation): ExerciseExtractionResult {
        if (session.sessionQualityScore < minimumSessionQuality) {
            return ExerciseExtractionFailure("Overall exercise data quality is below the research gate")
        }
        if (!session.summaryQuality.interpretationGrade ||
            session.summaryQuality.coverage < minimumWorkloadCoverage
        ) {
            return ExerciseExtractionFailure("Exercise summary totals are below the research quality gate")
        }
        if (!session.restingHeartRateQuality.interpretationGrade ||
            session.restingHeartRateQuality.coverage < minimumHeartRateCoverage
        ) {
            return ExerciseExtractionFailure("The protocol resting heart-rate reference is below the research quality gate")
        }
        if (session.workloadTarget.minimumAmbientTemperatureC != null &&
            session.ambientTemperatureQuality?.interpretationGrade != true
        ) {
            return ExerciseExtractionFailure("Ambient-temperature evidence is below the matched-workload quality gate")
        }
        val elapsedActiveMillis = session.activeEndedAtEpochMillis - session.startedAtEpochMillis
        val declaredActiveMillis = session.activeDurationSeconds * 1_000L
        if (abs(elapsedActiveMillis - declaredActiveMillis) > ACTIVE_DURATION_TOLERANCE_MILLIS) {
            return ExerciseExtractionFailure(
                "Version 1 matched-workload analysis requires one continuous active phase without an unaccounted pause",
            )
        }

        val qualifiedHr = session.heartRateIntervals.filter { it.quality.interpretationGrade }
        val qualifiedWorkload = session.workloadIntervals.filter { it.quality.interpretationGrade }
        val activeMillis = session.activeDurationSeconds * 1_000.0
        val hrCoverage = qualifiedHr.sumOf { it.effectiveDurationMillis() } / activeMillis
        val workloadCoverage = qualifiedWorkload.sumOf { it.effectiveDurationMillis() } / activeMillis
        if (hrCoverage < minimumHeartRateCoverage) {
            return ExerciseExtractionFailure("Qualified exercise heart-rate coverage is below the research gate")
        }
        if (workloadCoverage < minimumWorkloadCoverage) {
            return ExerciseExtractionFailure("Qualified speed/grade/cadence workload coverage is below the research gate")
        }
        if (!matchesWorkloadTarget(session, qualifiedWorkload)) {
            return ExerciseExtractionFailure(
                "Distance, duration, speed, cadence, elevation or environment is outside the reviewed matched-workload tolerance",
            )
        }

        val recoveryByOffset = session.recoveryHeartRatePoints.associateBy {
            it.offsetSeconds
        }
        if (!recoveryByOffset.keys.containsAll(requiredRecoveryOffsetsSeconds)) {
            return ExerciseExtractionFailure("Fixed 60, 120 and 300 second recovery points are required")
        }
        val qualifiedRecovery = requiredRecoveryOffsetsSeconds.map { offset -> recoveryByOffset.getValue(offset) }
        if (qualifiedRecovery.any {
                !it.quality.interpretationGrade || it.quality.coverage < minimumHeartRateCoverage
            }
        ) {
            return ExerciseExtractionFailure("A fixed recovery heart-rate point is below the research quality gate")
        }
        if (qualifiedRecovery.any {
                abs(it.observedAtEpochMillis - (session.activeEndedAtEpochMillis + it.offsetSeconds * 1_000L)) >
                    RECOVERY_TIMESTAMP_TOLERANCE_MILLIS
            }
        ) {
            return ExerciseExtractionFailure("A recovery point is not aligned to its fixed protocol timestamp")
        }

        val averageHr = weightedAverageHr(qualifiedHr, session.startedAtEpochMillis, session.activeEndedAtEpochMillis)
            ?: return ExerciseExtractionFailure("Qualified heart-rate intervals do not support a time-weighted mean")
        val peakHr = weightedQuantileHr(qualifiedHr, PERSISTENT_PEAK_QUANTILE)
        val terminalStart = session.activeEndedAtEpochMillis - TERMINAL_HR_WINDOW_MILLIS
        val terminalHr = weightedAverageHr(qualifiedHr, terminalStart, session.activeEndedAtEpochMillis)
            ?: return ExerciseExtractionFailure("The terminal active heart-rate window is unavailable")
        val terminalCoverage = coveredEffectiveMillis(qualifiedHr, terminalStart, session.activeEndedAtEpochMillis) /
            TERMINAL_HR_WINDOW_MILLIS.toDouble()
        if (terminalCoverage < minimumHeartRateCoverage) {
            return ExerciseExtractionFailure("The terminal active heart-rate window lacks qualified coverage")
        }

        val fullCost = weightedCardiacCost(
            qualifiedHr,
            qualifiedWorkload,
            session.startedAtEpochMillis,
            session.activeEndedAtEpochMillis,
            session.restingHeartRateBpm,
        ) ?: return ExerciseExtractionFailure(
            "Heart rate and workload do not support a finite, sufficiently aligned cardiac-cost calculation",
        )
        val phaseMillis = elapsedActiveMillis / 3L
        val earlyCost = weightedCardiacCost(
            qualifiedHr,
            qualifiedWorkload,
            session.startedAtEpochMillis,
            session.startedAtEpochMillis + phaseMillis,
            session.restingHeartRateBpm,
        ) ?: return ExerciseExtractionFailure("The early workload-response phase is unavailable")
        val lateCost = weightedCardiacCost(
            qualifiedHr,
            qualifiedWorkload,
            session.activeEndedAtEpochMillis - phaseMillis,
            session.activeEndedAtEpochMillis,
            session.restingHeartRateBpm,
        ) ?: return ExerciseExtractionFailure("The late workload-response phase is unavailable")
        if (earlyCost < MINIMUM_DRIFT_DENOMINATOR) {
            return ExerciseExtractionFailure("The early cardiac-cost denominator is too small for stable drift")
        }
        val drift = (lateCost / earlyCost - 1.0) * 100.0
        if (!drift.isFinite()) {
            return ExerciseExtractionFailure("The cardiac-drift calculation is not numerically stable")
        }
        val meanSpeed = weightedAverageWorkload(qualifiedWorkload) { it.speedMetersPerSecond }
        val cadenceIntervals = qualifiedWorkload.filter { it.cadenceStepsPerMinute != null }
        val cadenceCoverage = cadenceIntervals.sumOf { it.effectiveDurationMillis() } / activeMillis
        if (session.workloadTarget.targetMeanCadenceSpm != null && cadenceCoverage < minimumWorkloadCoverage) {
            return ExerciseExtractionFailure("Qualified cadence coverage is below the matched-workload research gate")
        }
        val meanCadence = if (cadenceIntervals.isEmpty()) null else {
            weightedAverageWorkload(cadenceIntervals) { it.cadenceStepsPerMinute!! }
        }
        val recoveryDrops = qualifiedRecovery.associate { point ->
            point.offsetSeconds to (terminalHr - point.heartRateBpm)
        }.toSortedMap()
        val zoneDurations = session.heartRateBands.bands.map { band ->
            val millis = qualifiedHr.sumOf { interval ->
                if (interval.heartRateBpm >= band.lowerInclusiveBpm &&
                    interval.heartRateBpm < band.upperExclusiveBpm
                ) {
                    interval.effectiveDurationMillis()
                } else {
                    0.0
                }
            }
            ExerciseZoneDuration(band.id, millis / 1_000.0)
        }
        val qualifiedHrSeconds = qualifiedHr.sumOf { it.effectiveDurationMillis() } / 1_000.0
        val unclassifiedZoneSeconds = max(
            0.0,
            qualifiedHrSeconds - zoneDurations.sumOf(ExerciseZoneDuration::durationSeconds),
        )
        val features = ExerciseResponseFeatureSet(
            steps = session.steps,
            distanceKilometres = session.distanceMeters / 1_000.0,
            activeMinutes = session.activeDurationSeconds / 60.0,
            elevationGainMeters = session.elevationGainMeters,
            timeWeightedAverageHeartRateBpm = averageHr,
            persistentPeakHeartRateBpm = peakHr,
            zoneDurations = zoneDurations,
            unclassifiedZoneDurationSeconds = unclassifiedZoneSeconds,
            heartRateCoverage = min(1.0, hrCoverage),
            workloadCoverage = min(1.0, workloadCoverage),
            cadenceCoverage = min(1.0, cadenceCoverage),
            meanSpeedMetersPerSecond = meanSpeed,
            meanCadenceStepsPerMinute = meanCadence,
            matchedWorkloadCardiacCost = fullCost,
            recoveryDropBpmByOffsetSeconds = recoveryDrops,
            cardiacDriftPercent = drift,
            provenanceIds = session.provenanceIds,
        )
        val responseFeatures = buildList {
            add(ResponseFeature(FEATURE_AVERAGE_HR, ResponseFeatureFamily.CARDIAC_KINETICS, averageHr, "bpm"))
            add(ResponseFeature(FEATURE_PERSISTENT_PEAK_HR, ResponseFeatureFamily.CARDIAC_KINETICS, peakHr, "bpm"))
            add(ResponseFeature(FEATURE_CARDIAC_COST, ResponseFeatureFamily.CARDIAC_KINETICS, fullCost, "bpm/workload-unit"))
            add(ResponseFeature(FEATURE_RECOVERY_60, ResponseFeatureFamily.CARDIAC_KINETICS, recoveryDrops.getValue(60), "bpm"))
            add(ResponseFeature(FEATURE_RECOVERY_120, ResponseFeatureFamily.CARDIAC_KINETICS, recoveryDrops.getValue(120), "bpm"))
            add(ResponseFeature(FEATURE_RECOVERY_300, ResponseFeatureFamily.CARDIAC_KINETICS, recoveryDrops.getValue(300), "bpm"))
            add(ResponseFeature(FEATURE_CARDIAC_DRIFT, ResponseFeatureFamily.CARDIAC_KINETICS, drift, "%"))
            if (session.comparabilityKey.sourceMap.hasIndependentCardiacAndWorkloadOrigins) {
                add(ResponseFeature(FEATURE_MEAN_SPEED, ResponseFeatureFamily.MOVEMENT, meanSpeed, "m/s"))
                meanCadence?.let {
                    add(ResponseFeature(FEATURE_MEAN_CADENCE, ResponseFeatureFamily.MOVEMENT, it, "steps/min"))
                }
            }
        }
        val episode = StandardizedResponseEpisode(
            id = session.id,
            protocolId = session.comparabilityKey.protocolId,
            protocolVersion = session.comparabilityKey.protocolVersion,
            deviceGeneration = session.comparabilityKey.deviceGeneration,
            firmwareGeneration = session.comparabilityKey.firmwareGeneration,
            completedAtEpochMillis = session.recoveryEndedAtEpochMillis,
            localDateIso = session.localDateIso,
            quality = buildList {
                add(session.sessionQualityScore)
                add(min(1.0, hrCoverage))
                add(min(1.0, workloadCoverage))
                add(session.summaryQuality.score)
                add(session.restingHeartRateQuality.score)
                addAll(qualifiedRecovery.map { it.quality.score })
                session.ambientTemperatureQuality?.let { add(it.score) }
            }.min(),
            eligibilityReceiptId = session.eligibilityReceiptId,
            standardizationFingerprint = standardizationFingerprint(session),
            humanConcern = session.humanConcern,
            features = responseFeatures,
            provenanceIds = session.provenanceIds,
        )
        return ExerciseExtractionSuccess(features, episode)
    }

    private fun matchesWorkloadTarget(
        session: ExerciseSessionObservation,
        workload: List<ExerciseWorkloadInterval>,
    ): Boolean {
        val target = session.workloadTarget
        val meanSpeed = weightedAverageWorkload(workload) { it.speedMetersPerSecond }
        val cadenceIntervals = workload.filter { it.cadenceStepsPerMinute != null }
        val meanCadence = if (cadenceIntervals.isEmpty()) null else {
            weightedAverageWorkload(cadenceIntervals) { it.cadenceStepsPerMinute!! }
        }
        val ambientMatches = if (target.minimumAmbientTemperatureC == null) {
            true
        } else {
            session.observedAmbientTemperatureC?.let {
                it in target.minimumAmbientTemperatureC..target.maximumAmbientTemperatureC!!
            } == true
        }
        val impliedDistanceMeters = meanSpeed * session.activeDurationSeconds
        val distanceAndSpeedAgree = withinFraction(
            session.distanceMeters,
            impliedDistanceMeters,
            MAX_DISTANCE_SPEED_DISAGREEMENT_FRACTION,
        )
        val stepsAndCadenceAgree = if (meanCadence == null) {
            true
        } else {
            val impliedSteps = meanCadence * session.activeDurationSeconds / 60.0
            withinFraction(
                session.steps.toDouble(),
                impliedSteps,
                MAX_STEPS_CADENCE_DISAGREEMENT_FRACTION,
            )
        }
        return withinFraction(session.distanceMeters, target.targetDistanceMeters, target.distanceToleranceFraction) &&
            withinFraction(
                session.activeDurationSeconds.toDouble(),
                target.targetActiveDurationSeconds.toDouble(),
                target.durationToleranceFraction,
            ) &&
            withinFraction(meanSpeed, target.targetMeanSpeedMps, target.speedToleranceFraction) &&
            abs(session.elevationGainMeters - target.targetElevationGainMeters) <= target.elevationToleranceMeters &&
            distanceAndSpeedAgree &&
            stepsAndCadenceAgree &&
            if (target.targetMeanCadenceSpm == null) {
                ambientMatches
            } else {
                meanCadence != null &&
                    withinFraction(meanCadence, target.targetMeanCadenceSpm, target.cadenceToleranceFraction!!) &&
                    ambientMatches
            }
    }

    private fun weightedAverageHr(
        intervals: List<ExerciseHeartRateInterval>,
        start: Long,
        end: Long,
    ): Double? {
        var weightedSum = 0.0
        var totalMillis = 0.0
        intervals.forEach { interval ->
            val overlap = overlapMillis(interval.startEpochMillis, interval.endEpochMillis, start, end)
            if (overlap > 0L) {
                val effectiveMillis = overlap * interval.quality.coverage
                weightedSum += interval.heartRateBpm * effectiveMillis
                totalMillis += effectiveMillis
            }
        }
        val mean = if (totalMillis > 0.0) weightedSum / totalMillis else return null
        return mean.takeIf(Double::isFinite)
    }

    private fun weightedQuantileHr(
        intervals: List<ExerciseHeartRateInterval>,
        probability: Double,
    ): Double {
        val weighted = intervals.sortedBy(ExerciseHeartRateInterval::heartRateBpm)
        val total = weighted.sumOf { it.effectiveDurationMillis() }
        val threshold = total * probability
        var cumulative = 0.0
        weighted.forEach {
            cumulative += it.effectiveDurationMillis()
            if (cumulative >= threshold) return it.heartRateBpm
        }
        return weighted.last().heartRateBpm
    }

    private fun coveredEffectiveMillis(
        intervals: List<ExerciseHeartRateInterval>,
        start: Long,
        end: Long,
    ): Double = intervals.sumOf {
        overlapMillis(it.startEpochMillis, it.endEpochMillis, start, end) * it.quality.coverage
    }

    private fun weightedAverageWorkload(
        intervals: List<ExerciseWorkloadInterval>,
        value: (ExerciseWorkloadInterval) -> Double,
    ): Double {
        val duration = intervals.sumOf { it.effectiveDurationMillis() }
        require(duration > 0.0)
        val weightedSum = intervals.sumOf { value(it) * it.effectiveDurationMillis() }
        val mean = weightedSum / duration
        return if (mean.isFinite()) mean else Double.NaN
    }

    private fun weightedCardiacCost(
        heartRate: List<ExerciseHeartRateInterval>,
        workload: List<ExerciseWorkloadInterval>,
        start: Long,
        end: Long,
        restingHeartRateBpm: Double,
    ): Double? {
        var weightedCost = 0.0
        var alignedMillis = 0.0
        heartRate.forEach { hr ->
            workload.forEach { load ->
                val overlapStart = max(max(hr.startEpochMillis, load.startEpochMillis), start)
                val overlapEnd = min(min(hr.endEpochMillis, load.endEpochMillis), end)
                if (overlapEnd > overlapStart) {
                    val jointCoverageLowerBound = max(
                        0.0,
                        hr.quality.coverage + load.quality.coverage - 1.0,
                    )
                    val duration = (overlapEnd - overlapStart) * jointCoverageLowerBound
                    val cost = (hr.heartRateBpm - restingHeartRateBpm) / load.protocolWorkloadUnits
                    if (!cost.isFinite() || !duration.isFinite()) return null
                    weightedCost += cost * duration
                    alignedMillis += duration
                    if (!weightedCost.isFinite() || !alignedMillis.isFinite()) return null
                }
            }
        }
        val requestedMillis = end - start
        if (requestedMillis <= 0L || alignedMillis / requestedMillis < MINIMUM_ALIGNED_COVERAGE) {
            return null
        }
        return (weightedCost / alignedMillis).takeIf(Double::isFinite)
    }

    private fun standardizationFingerprint(session: ExerciseSessionObservation): String {
        val key = session.comparabilityKey
        val target = session.workloadTarget
        return sha256(
            listOf(
                key.exerciseType,
                key.protocolId,
                key.protocolVersion,
                key.deviceGeneration,
                key.firmwareGeneration,
                key.sourceMap.heartRate.name,
                key.sourceMap.heartRateOrigin.name,
                key.sourceMap.workload.name,
                key.sourceMap.workloadOrigin.name,
                key.sourceMap.recoveryHeartRate.name,
                key.sourceMap.recoveryHeartRateOrigin.name,
                key.sourceMap.dailyTotals.name,
                key.sourceMap.dailyTotalsOrigin.name,
                key.routeOrEquipmentFingerprintSha256,
                key.environmentBandFingerprintSha256,
                key.workloadModelId,
                key.workloadModelVersion,
                key.recoveryProtocolId,
                key.recoveryProtocolVersion,
                session.sessionQualityEvaluatorVersion,
                session.summaryQuality.evaluatorVersion,
                session.restingHeartRateQuality.evaluatorVersion,
                session.ambientTemperatureQuality?.evaluatorVersion ?: "none",
                session.heartRateIntervals.first().quality.evaluatorVersion,
                session.workloadIntervals.first().quality.evaluatorVersion,
                session.recoveryHeartRatePoints.first().quality.evaluatorVersion,
                session.heartRateBands.digestSha256,
                java.lang.Double.toHexString(target.targetDistanceMeters),
                java.lang.Double.toHexString(target.distanceToleranceFraction),
                target.targetActiveDurationSeconds.toString(),
                java.lang.Double.toHexString(target.durationToleranceFraction),
                java.lang.Double.toHexString(target.targetMeanSpeedMps),
                java.lang.Double.toHexString(target.speedToleranceFraction),
                java.lang.Double.toHexString(target.targetElevationGainMeters),
                java.lang.Double.toHexString(target.elevationToleranceMeters),
                target.targetMeanCadenceSpm?.let(java.lang.Double::toHexString) ?: "none",
                target.cadenceToleranceFraction?.let(java.lang.Double::toHexString) ?: "none",
                target.minimumAmbientTemperatureC?.let(java.lang.Double::toHexString) ?: "none",
                target.maximumAmbientTemperatureC?.let(java.lang.Double::toHexString) ?: "none",
            ).joinToString("|"),
        )
    }

    private fun hasIdentityOrProvenanceCollision(
        history: List<ExerciseSessionObservation>,
        current: ExerciseSessionObservation,
    ): Boolean {
        val currentEvidence = current.measurementProvenanceIds().toSet()
        if (history.any {
                it.id == current.id || it.measurementProvenanceIds().any(currentEvidence::contains)
            }
        ) {
            return true
        }
        val all = history.filter { it.comparabilityKey == current.comparabilityKey } + current
        if (all.groupBy(ExerciseSessionObservation::id).values.any { it.size > 1 }) return true
        // Reusable configuration evidence (for example the reviewed personal
        // band definition) may legitimately bind every episode. Only raw/session
        // measurement evidence must be unique across episodes.
        val provenance = all.flatMap { it.measurementProvenanceIds() }
        return provenance.distinct().size != provenance.size
    }

    private fun ExerciseSessionObservation.measurementProvenanceIds(): List<String> = buildList {
        add(summaryProvenanceId)
        add(restingHeartRateProvenanceId)
        ambientTemperatureProvenanceId?.let(::add)
        addAll(heartRateIntervals.map(ExerciseHeartRateInterval::provenanceId))
        addAll(workloadIntervals.map(ExerciseWorkloadInterval::provenanceId))
        addAll(recoveryHeartRatePoints.map(ExerciseRecoveryHeartRatePoint::provenanceId))
        addAll(gaps.map(ActivityDataGap::provenanceId))
    }

    private fun withinFraction(observed: Double, target: Double, tolerance: Double): Boolean {
        if (!observed.isFinite() || !target.isFinite() || !tolerance.isFinite()) return false
        return abs(observed - target) <= abs(target) * tolerance
    }

    private fun overlapMillis(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        max(0L, min(aEnd, bEnd) - max(aStart, bStart))

    private fun ExerciseHeartRateInterval.effectiveDurationMillis(): Double =
        (endEpochMillis - startEpochMillis) * quality.coverage

    private fun ExerciseWorkloadInterval.effectiveDurationMillis(): Double =
        (endEpochMillis - startEpochMillis) * quality.coverage

    private fun abstained(
        reason: String,
        gapReasons: Set<ActivityGapReason> = emptySet(),
    ) = ExerciseTrendAssessment(
        state = ExerciseTrendState.ABSTAINED,
        featureSet = null,
        responseAssessment = null,
        gapReasons = gapReasons,
        reason = reason,
    )

    private sealed interface ExerciseExtractionResult

    private data class ExerciseExtractionSuccess(
        val features: ExerciseResponseFeatureSet,
        val episode: StandardizedResponseEpisode,
    ) : ExerciseExtractionResult

    private data class ExerciseExtractionFailure(
        val reason: String,
    ) : ExerciseExtractionResult

    private companion object {
        const val FEATURE_AVERAGE_HR = "time-weighted-average-hr"
        const val FEATURE_PERSISTENT_PEAK_HR = "persistent-p95-heart-rate"
        const val FEATURE_CARDIAC_COST = "matched-workload-cardiac-cost"
        const val FEATURE_RECOVERY_60 = "heart-rate-recovery-drop-60s"
        const val FEATURE_RECOVERY_120 = "heart-rate-recovery-drop-120s"
        const val FEATURE_RECOVERY_300 = "heart-rate-recovery-drop-300s"
        const val FEATURE_CARDIAC_DRIFT = "cardiac-drift-percent"
        const val FEATURE_MEAN_SPEED = "mean-speed"
        const val FEATURE_MEAN_CADENCE = "mean-cadence"
        const val ACTIVE_DURATION_TOLERANCE_MILLIS = 2_000L
        const val TERMINAL_HR_WINDOW_MILLIS = 15_000L
        const val RECOVERY_TIMESTAMP_TOLERANCE_MILLIS = 5_000L
        const val PERSISTENT_PEAK_QUANTILE = 0.95
        const val MINIMUM_ALIGNED_COVERAGE = 0.85
        const val MINIMUM_DRIFT_DENOMINATOR = 1.0
        const val MAX_DISTANCE_SPEED_DISAGREEMENT_FRACTION = 0.10
        const val MAX_STEPS_CADENCE_DISAGREEMENT_FRACTION = 0.15
    }
}

private val SHA256_REGEX = Regex("[a-f0-9]{64}")

private fun <T> immutableListSnapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMapSnapshot(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun <T> immutableSetSnapshot(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun SignalQuality.immutableSnapshot(): SignalQuality = copy(
    reasons = immutableListSnapshot(reasons),
)

private fun StandardizedResponseAssessment.immutableSnapshot(): StandardizedResponseAssessment = copy(
    deviations = immutableListSnapshot(deviations),
    changedIndependentFamilies = immutableSetSnapshot(changedIndependentFamilies),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
