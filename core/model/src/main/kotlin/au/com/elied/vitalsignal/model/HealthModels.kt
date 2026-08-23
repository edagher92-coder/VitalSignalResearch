package au.com.elied.vitalsignal.model

/**
 * Canonical data contracts shared by collection, interpretation and UI layers.
 * Every derived value keeps provenance so a user can drill from an insight to
 * the measurements that produced it.
 */

enum class SensorMetric(val unit: String) {
    HEART_RATE("bpm"),
    INTER_BEAT_INTERVAL("ms"),
    HRV_RMSSD("ms"),
    HRV_SDNN("ms"),
    OXYGEN_SATURATION("%"),
    SKIN_TEMPERATURE("°C"),
    AMBIENT_TEMPERATURE("°C"),
    EDA("µS"),
    RESPIRATORY_RATE("breaths/min"),
    STEP_COUNT("steps"),
    ACTIVITY_LOAD("AU"),
    SLEEP_DURATION("min"),
    SLEEP_EFFICIENCY("%"),
    BODY_IMPEDANCE("Ω"),
    SWEAT_LOSS("mL"),
}

enum class SensorSource {
    GALAXY_WATCH_ULTRA_2,
    SAMSUNG_HEALTH,
    HEALTH_CONNECT,
    USER_REPORTED,
    REFERENCE_DEVICE,
    SIMULATOR,
}

enum class ActivityState {
    ASLEEP,
    RESTING,
    SEDENTARY,
    ACTIVE,
    EXERCISING,
    UNKNOWN,
}

/**
 * Physical acquisition paths that can create common-mode evidence.
 *
 * These are deliberately coarser than physiological domains. For example,
 * wrist-derived heart rate, HRV, oxygen saturation and respiratory proxies can
 * all inherit the same optical/contact/motion artefact and therefore must not
 * be counted as independent corroboration merely because their labels differ.
 */
enum class AcquisitionOrigin {
    WRIST_OPTICAL_CONTACT_MOTION,
    WRIST_ELECTRICAL_CONTACT,
    WRIST_THERMAL_CONTACT,
    WRIST_INERTIAL_MOTION,
    USER_REPORTED,
    EXTERNAL_REFERENCE_DEVICE,
    SIMULATOR_OPTICAL_FIXTURE,
    SIMULATOR_THERMAL_FIXTURE,
    SIMULATOR_INERTIAL_FIXTURE,
    SIMULATOR_ELECTRICAL_FIXTURE,
    SIMULATOR_SHARED_FIXTURE,
    UNKNOWN_SHARED_DEVICE_PIPELINE,
}

/**
 * Auditable acquisition dependency declaration for a derived metric window.
 * The evidence IDs must be contained in the window's immutable provenance.
 */
data class AcquisitionDependencyProfile(
    val primaryOrigin: AcquisitionOrigin,
    val dependentOrigins: Set<AcquisitionOrigin> = emptySet(),
    val evidenceProvenanceIds: List<String>,
    val mappingVersion: String = "acquisition-dependency-v1",
) {
    init {
        require(primaryOrigin !in dependentOrigins)
        require(evidenceProvenanceIds.isNotEmpty())
        require(evidenceProvenanceIds.all(String::isNotBlank))
        require(evidenceProvenanceIds.distinct().size == evidenceProvenanceIds.size)
        require(mappingVersion.isNotBlank())
    }

    val allOrigins: Set<AcquisitionOrigin>
        get() = dependentOrigins + primaryOrigin
}

/**
 * Conservative mapping used when a more specific, validated acquisition
 * declaration is not available. Callers may supply a stricter explicit
 * profile (for example, a separately verified reference device).
 */
fun conservativeAcquisitionProfile(
    metric: SensorMetric,
    source: SensorSource,
    evidenceProvenanceIds: List<String>,
): AcquisitionDependencyProfile {
    val sourceLevelOrigin = when (source) {
        SensorSource.USER_REPORTED -> AcquisitionOrigin.USER_REPORTED
        SensorSource.REFERENCE_DEVICE -> AcquisitionOrigin.EXTERNAL_REFERENCE_DEVICE
        SensorSource.SIMULATOR -> null
        SensorSource.SAMSUNG_HEALTH,
        SensorSource.HEALTH_CONNECT,
        -> AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE
        SensorSource.GALAXY_WATCH_ULTRA_2 -> null
    }
    if (sourceLevelOrigin != null) {
        return AcquisitionDependencyProfile(
            primaryOrigin = sourceLevelOrigin,
            evidenceProvenanceIds = evidenceProvenanceIds,
        )
    }

    if (source == SensorSource.SIMULATOR) {
        val simulatorOrigin = when (metric) {
            SensorMetric.HEART_RATE,
            SensorMetric.INTER_BEAT_INTERVAL,
            SensorMetric.HRV_RMSSD,
            SensorMetric.HRV_SDNN,
            SensorMetric.OXYGEN_SATURATION,
            SensorMetric.RESPIRATORY_RATE,
            SensorMetric.SLEEP_DURATION,
            SensorMetric.SLEEP_EFFICIENCY,
            -> AcquisitionOrigin.SIMULATOR_OPTICAL_FIXTURE
            SensorMetric.SKIN_TEMPERATURE,
            SensorMetric.AMBIENT_TEMPERATURE,
            -> AcquisitionOrigin.SIMULATOR_THERMAL_FIXTURE
            SensorMetric.STEP_COUNT,
            SensorMetric.ACTIVITY_LOAD,
            -> AcquisitionOrigin.SIMULATOR_INERTIAL_FIXTURE
            SensorMetric.EDA,
            SensorMetric.BODY_IMPEDANCE,
            -> AcquisitionOrigin.SIMULATOR_ELECTRICAL_FIXTURE
            SensorMetric.SWEAT_LOSS -> AcquisitionOrigin.SIMULATOR_SHARED_FIXTURE
        }
        return AcquisitionDependencyProfile(
            primaryOrigin = simulatorOrigin,
            evidenceProvenanceIds = evidenceProvenanceIds,
            mappingVersion = "acquisition-dependency-v1-simulator",
        )
    }

    return when (metric) {
    SensorMetric.HEART_RATE,
    SensorMetric.INTER_BEAT_INTERVAL,
    SensorMetric.HRV_RMSSD,
    SensorMetric.HRV_SDNN,
    SensorMetric.OXYGEN_SATURATION,
    SensorMetric.RESPIRATORY_RATE,
    -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
        evidenceProvenanceIds = evidenceProvenanceIds,
    )

    SensorMetric.SLEEP_DURATION,
    SensorMetric.SLEEP_EFFICIENCY,
    -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.WRIST_OPTICAL_CONTACT_MOTION,
        dependentOrigins = setOf(AcquisitionOrigin.WRIST_INERTIAL_MOTION),
        evidenceProvenanceIds = evidenceProvenanceIds,
    )

    SensorMetric.SKIN_TEMPERATURE,
    SensorMetric.AMBIENT_TEMPERATURE,
    -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.WRIST_THERMAL_CONTACT,
        evidenceProvenanceIds = evidenceProvenanceIds,
    )

    SensorMetric.EDA,
    SensorMetric.BODY_IMPEDANCE,
    -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.WRIST_ELECTRICAL_CONTACT,
        evidenceProvenanceIds = evidenceProvenanceIds,
    )

    SensorMetric.STEP_COUNT,
    SensorMetric.ACTIVITY_LOAD,
    -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.WRIST_INERTIAL_MOTION,
        evidenceProvenanceIds = evidenceProvenanceIds,
    )

    SensorMetric.SWEAT_LOSS -> AcquisitionDependencyProfile(
        primaryOrigin = AcquisitionOrigin.UNKNOWN_SHARED_DEVICE_PIPELINE,
        dependentOrigins = setOf(AcquisitionOrigin.WRIST_INERTIAL_MOTION),
        evidenceProvenanceIds = evidenceProvenanceIds,
    )
    }
}

class SignalQuality(
    val score: Double,
    val coverage: Double = 1.0,
    val contact: Double = 1.0,
    val motionContamination: Double = 0.0,
    val validity: Double = 1.0,
    val clipping: Double = 0.0,
    val timestampContinuity: Double = 1.0,
    reasons: List<String> = emptyList(),
    val evaluatorVersion: String = "quality-v2",
) {
    /** Immutable snapshot prevents an adapter from changing quality evidence after validation. */
    val reasons: List<String> = java.util.List.copyOf(reasons)

    init {
        require(score in 0.0..1.0)
        require(coverage in 0.0..1.0)
        require(contact in 0.0..1.0)
        require(motionContamination in 0.0..1.0)
        require(validity in 0.0..1.0)
        require(clipping in 0.0..1.0)
        require(timestampContinuity in 0.0..1.0)
        require(this.reasons.none(String::isBlank))
        require(evaluatorVersion.isNotBlank())
    }

    /** May be retained for low-weight feature estimation, never silently treated as normal. */
    val usable: Boolean
        get() = score >= 0.60 && coverage >= 0.50 && contact >= 0.50 &&
            motionContamination <= 0.75 && validity >= 0.70 && clipping <= 0.15 &&
            timestampContinuity >= 0.70

    /** Stronger gate for evidence that can influence a user-visible interpretation. */
    val interpretationGrade: Boolean
        get() = score >= 0.80 && coverage >= 0.80 && contact >= 0.80 &&
            motionContamination <= 0.25 && validity >= 0.90 && clipping <= 0.05 &&
            timestampContinuity >= 0.90

    fun copy(
        score: Double = this.score,
        coverage: Double = this.coverage,
        contact: Double = this.contact,
        motionContamination: Double = this.motionContamination,
        validity: Double = this.validity,
        clipping: Double = this.clipping,
        timestampContinuity: Double = this.timestampContinuity,
        reasons: List<String> = this.reasons,
        evaluatorVersion: String = this.evaluatorVersion,
    ) = SignalQuality(
        score,
        coverage,
        contact,
        motionContamination,
        validity,
        clipping,
        timestampContinuity,
        reasons,
        evaluatorVersion,
    )

    override fun equals(other: Any?): Boolean = other is SignalQuality &&
        score == other.score && coverage == other.coverage && contact == other.contact &&
        motionContamination == other.motionContamination && validity == other.validity &&
        clipping == other.clipping && timestampContinuity == other.timestampContinuity &&
        reasons == other.reasons && evaluatorVersion == other.evaluatorVersion

    override fun hashCode(): Int = listOf(
        score,
        coverage,
        contact,
        motionContamination,
        validity,
        clipping,
        timestampContinuity,
        reasons,
        evaluatorVersion,
    ).hashCode()
}

data class SensorObservation(
    val id: String,
    val metric: SensorMetric,
    val epochMillis: Long,
    val value: Double,
    val quality: SignalQuality,
    val source: SensorSource,
    val activityState: ActivityState = ActivityState.UNKNOWN,
    val provenanceIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(epochMillis >= 0L)
        require(value.isFinite()) { "Sensor observation value must be finite" }
        require(provenanceIds.isNotEmpty())
    }
}

/** Exact acquisition stratum retained by every personal baseline artifact. */
data class BaselineContextKey(
    val deviceGeneration: String,
    val firmwareGeneration: String,
    val acquisitionProtocolVersion: String,
    val environmentFingerprintSha256: String,
) {
    init {
        require(deviceGeneration.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(acquisitionProtocolVersion.isNotBlank())
        require(environmentFingerprintSha256.matches(Regex("[a-f0-9]{64}")))
    }
}

data class MetricWindow(
    val id: String,
    val metric: SensorMetric,
    /** Retained through feature derivation; simulator/manual/history data never masquerade as live watch data. */
    val source: SensorSource,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val value: Double,
    val quality: SignalQuality,
    val activityState: ActivityState,
    val localHourBucket: Int,
    val localDateIso: String,
    val localOffsetMinutes: Int,
    val baselineContext: BaselineContextKey,
    val provenanceIds: List<String>,
    val acquisitionProfile: AcquisitionDependencyProfile,
) {
    init {
        require(id.isNotBlank())
        require(startEpochMillis >= 0L)
        require(endEpochMillis >= startEpochMillis)
        require(value.isFinite()) { "Metric window value must be finite" }
        require(localHourBucket in 0..23)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(localDateIso))
        require(localOffsetMinutes in -18 * 60..18 * 60)
        require(provenanceIds.isNotEmpty())
        require(provenanceIds.all(String::isNotBlank))
        require(provenanceIds.distinct().size == provenanceIds.size)
        require(acquisitionProfile.evidenceProvenanceIds.all { it in provenanceIds }) {
            "Acquisition dependency evidence must be bound to window provenance"
        }
    }
}

data class BaselineKey(
    val metric: SensorMetric,
    val localHourBucket: Int,
    val activityState: ActivityState,
    val context: BaselineContextKey,
) {
    init {
        require(localHourBucket in 0..23)
    }
}

data class PersonalBaseline(
    val key: BaselineKey,
    val median: Double,
    val scaledMad: Double,
    val lowerReference: Double,
    val upperReference: Double,
    val sampleCount: Int,
    val effectiveDays: Int,
    val lastUpdatedEpochMillis: Long,
    val maturity: Double,
) {
    init {
        require(median.isFinite())
        require(scaledMad.isFinite())
        require(scaledMad > 0.0)
        require(lowerReference.isFinite())
        require(upperReference.isFinite())
        require(lowerReference <= upperReference)
        require(sampleCount >= 0)
        require(effectiveDays >= 0)
        require(lastUpdatedEpochMillis >= 0L)
        require(maturity in 0.0..1.0)
    }
}

enum class DeviationDirection { LOWER, WITHIN_EXPECTED, HIGHER }

data class BaselineDeviation(
    val windowId: String,
    val metric: SensorMetric,
    val observed: Double,
    val expected: Double,
    val robustZ: Double,
    val direction: DeviationDirection,
    val quality: SignalQuality,
    val baselineMaturity: Double,
    val baselineSampleCount: Int,
    val provenanceIds: List<String>,
)

enum class PhysiologicalDomain {
    CARDIOVASCULAR,
    AUTONOMIC,
    RESPIRATORY,
    THERMAL,
    SLEEP,
    RECOVERY,
    MOVEMENT,
    HYDRATION,
    BODY_COMPOSITION,
    CONTEXT,
}

/** Emergency guidance belongs to a separate, reviewed symptom route. */
enum class InsightSeverity { INFORMATIONAL, WATCH, CHECK }
enum class InsightState { PRELIMINARY, CORROBORATED, PERSISTENT, RESOLVED }

data class EvidenceItem(
    val domain: PhysiologicalDomain,
    val metric: SensorMetric,
    val statement: String,
    val contribution: Double,
    val quality: Double,
    val provenanceIds: List<String>,
) {
    init {
        require(contribution in -1.0..1.0)
        require(quality in 0.0..1.0)
    }
}

data class HealthInsight(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val plainLanguageSummary: String,
    val severity: InsightSeverity,
    val state: InsightState,
    val confidence: Double,
    val dataQuality: Double,
    val evidence: List<EvidenceItem>,
    val nextStep: String,
    val recheckAtEpochMillis: Long? = null,
    val safetyCopyVersion: String = "wellness-v1",
) {
    init {
        require(confidence in 0.0..1.0)
        require(dataQuality in 0.0..1.0)
        require(evidence.isNotEmpty())
    }
}

data class HealthForecast(
    val id: String,
    val createdAtEpochMillis: Long,
    val endpoint: ForecastEndpointDefinition,
    val probability: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val confidence: Double,
    val modelVersion: String,
    val featureSnapshotIds: List<String>,
    val featureSchema: ForecastFeatureSchemaDefinition,
    val cutoffEpochMillis: Long = createdAtEpochMillis,
    val targetStartEpochMillis: Long = endpoint.targetStart(cutoffEpochMillis),
    val targetEndEpochMillis: Long = endpoint.targetEnd(cutoffEpochMillis),
    val policyVersion: String = "forecast-policy-v2",
    val intervalCoverage: Double = 0.80,
    val featureSnapshotHash: String,
    val maximumCommitLagMillis: Long = 15L * 60L * 1_000L,
) {
    val horizonHours: Int
        get() = (endpoint.targetStartOffsetMillis / (60L * 60L * 1_000L)).toInt()

    val outcomeName: String
        get() = endpoint.displayLabel

    val creationLagMillis: Long
        get() = createdAtEpochMillis - cutoffEpochMillis

    val leadTimeAtCreationMillis: Long
        get() = targetStartEpochMillis - createdAtEpochMillis

    init {
        require(probability in 0.0..1.0)
        require(lowerBound in 0.0..1.0)
        require(upperBound in 0.0..1.0)
        require(lowerBound <= probability && probability <= upperBound)
        require(confidence in 0.0..1.0)
        require(cutoffEpochMillis <= createdAtEpochMillis)
        require(maximumCommitLagMillis in 0L..60L * 60L * 1_000L)
        require(creationLagMillis <= maximumCommitLagMillis) {
            "Forecast creation exceeded the frozen cutoff-to-commit latency bound"
        }
        require(targetStartEpochMillis == endpoint.targetStart(cutoffEpochMillis)) {
            "Forecast target start must match the frozen endpoint offset"
        }
        require(targetEndEpochMillis == endpoint.targetEnd(cutoffEpochMillis)) {
            "Forecast target end must match the frozen endpoint offset"
        }
        require(policyVersion.isNotBlank())
        require(intervalCoverage in 0.0..1.0)
        require(featureSnapshotIds.isNotEmpty())
        require(featureSnapshotHash.matches(Regex("[a-f0-9]{64}"))) {
            "featureSnapshotHash must be a lowercase SHA-256 digest"
        }
    }
}

enum class ContextEventType {
    MEDICATION_DOSE,
    GLUCOCORTICOID_TAPER_PHASE,
    BIOLOGIC_INFUSION,
    ANTIBIOTIC,
    HYDRATION,
    MEAL,
    EXERCISE,
    STRESS,
    ILLNESS,
    ACUTE_ILLNESS_STRESSOR,
    LAB_RESULT,
    SYMPTOM,
    USER_CONCERN,
    ORTHOSTATIC_SYMPTOM,
    NAUSEA_VOMITING_DIARRHEA,
    SLEEP_NOTE,
}

data class ContextEvent(
    val id: String,
    val epochMillis: Long,
    val type: ContextEventType,
    val label: String,
    val numericValue: Double? = null,
    val unit: String? = null,
    val source: SensorSource = SensorSource.USER_REPORTED,
)

data class DailyOutcome(
    val localDateIso: String,
    val energy: Int,
    val fatigue: Int,
    val perceivedStress: Int,
    val gastrointestinalSymptoms: Int,
    val sleepQuality: Int,
    val functionalCapacity: Int? = null,
    val lightheadedness: Int? = null,
    val nauseaVomitingDiarrhea: Int? = null,
    val acuteIllnessBurden: Int? = null,
    val notes: String = "",
) {
    init {
        require(energy in 0..10)
        require(fatigue in 0..10)
        require(perceivedStress in 0..10)
        require(gastrointestinalSymptoms in 0..10)
        require(sleepQuality in 0..10)
        require(functionalCapacity == null || functionalCapacity in 0..10)
        require(lightheadedness == null || lightheadedness in 0..10)
        require(nauseaVomitingDiarrhea == null || nauseaVomitingDiarrhea in 0..10)
        require(acuteIllnessBurden == null || acuteIllnessBurden in 0..10)
    }
}

enum class ForecastResolution { CORRECT, INCORRECT, INDETERMINATE, PENDING }

data class ForecastAudit(
    val forecast: HealthForecast,
    val resolvedAtEpochMillis: Long? = null,
    val observedOutcome: Double? = null,
    val resolution: ForecastResolution = ForecastResolution.PENDING,
    val brierScore: Double? = null,
    val notes: String = "",
)
