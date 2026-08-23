package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality

enum class MonitoringPurpose {
    RESEARCH_LOG_ONLY,
    OBSERVED_RESEARCH_SESSION,
    REGULATED_CLINICAL_SERVICE,
}

enum class ObserverCoverage {
    ACTIVE,
    UNAVAILABLE,
}

class MonitoringSession(
    val sessionId: String,
    val subjectPseudonym: String,
    val purpose: MonitoringPurpose,
    authorizedObserverPrincipalIds: Set<String>,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    allowedSources: Set<SensorSource>,
    val destinationId: String,
    val streamId: String,
    val sourceDeviceId: String,
    val gatewayDeviceId: String,
    val acquisitionProtocolVersion: String,
    val consentGeneration: Long,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val expectedSamplePeriodMillis: Long,
    val minimumDisplayQuality: Double,
    val protocolVersion: String,
    val appVersion: String,
    val deviceModel: String,
    val firmwareGeneration: String,
    val dataSchemaVersion: String,
    val clinicalFeatureId: String,
    val clinicalFeatureVersion: String,
    val environmentFingerprintSha256: String,
) {
    val authorizedObserverPrincipalIds: Set<String> = java.util.Set.copyOf(authorizedObserverPrincipalIds)
    val allowedSources: Set<SensorSource> = java.util.Set.copyOf(allowedSources)

    init {
        requireMonitoringIdentifier(sessionId, "sessionId")
        requireMonitoringIdentifier(subjectPseudonym, "subjectPseudonym")
        requireMonitoringIdentifier(destinationId, "destinationId")
        requireMonitoringIdentifier(streamId, "streamId")
        requireMonitoringIdentifier(sourceDeviceId, "sourceDeviceId")
        requireMonitoringIdentifier(gatewayDeviceId, "gatewayDeviceId")
        require(acquisitionProtocolVersion.isNotBlank())
        require(consentGeneration > 0L)
        require(startsAtEpochMillis > 0L)
        require(endsAtEpochMillis > startsAtEpochMillis)
        require(expectedSamplePeriodMillis in 1L..MAXIMUM_EXPECTED_SAMPLE_PERIOD_MILLIS)
        require(minimumDisplayQuality in 0.0..1.0)
        require(protocolVersion.isNotBlank())
        require(appVersion.isNotBlank())
        require(deviceModel.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        requireMonitoringIdentifier(dataSchemaVersion, "dataSchemaVersion")
        requireMonitoringIdentifier(clinicalFeatureId, "clinicalFeatureId")
        requireMonitoringIdentifier(clinicalFeatureVersion, "clinicalFeatureVersion")
        require(environmentFingerprintSha256.matches(SHA_256_REGEX))
        require(this.authorizedObserverPrincipalIds.all(MONITORING_IDENTIFIER_REGEX::matches))
        require(this.allowedSources.isNotEmpty()) { "At least one explicitly allowed source is required" }
        if (purpose != MonitoringPurpose.RESEARCH_LOG_ONLY) {
            require(
                this.allowedSources.none {
                    it == SensorSource.SIMULATOR || it == SensorSource.USER_REPORTED
                },
            ) {
                "Simulator and user-reported values cannot be allowed as live sensor data"
            }
        }
        if (purpose != MonitoringPurpose.RESEARCH_LOG_ONLY) {
            require(this.authorizedObserverPrincipalIds.isNotEmpty())
        }
    }

    val delayedAfterMillis: Long
        get() = expectedSamplePeriodMillis

    /** Two missed samples or five minutes, whichever occurs first. */
    val staleAfterMillis: Long
        get() = minOf(expectedSamplePeriodMillis * 2L, MAXIMUM_STALE_AFTER_MILLIS)

    override fun equals(other: Any?): Boolean =
        other is MonitoringSession && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(
        sessionId, subjectPseudonym, purpose, authorizedObserverPrincipalIds, metric, dataClass,
        allowedSources,
        destinationId, streamId, sourceDeviceId, gatewayDeviceId, acquisitionProtocolVersion,
        consentGeneration, startsAtEpochMillis, endsAtEpochMillis, expectedSamplePeriodMillis,
        minimumDisplayQuality, protocolVersion, appVersion, deviceModel, firmwareGeneration,
        dataSchemaVersion, clinicalFeatureId, clinicalFeatureVersion, environmentFingerprintSha256,
    )

    fun copy(
        sessionId: String = this.sessionId,
        subjectPseudonym: String = this.subjectPseudonym,
        purpose: MonitoringPurpose = this.purpose,
        authorizedObserverPrincipalIds: Set<String> = this.authorizedObserverPrincipalIds,
        metric: SensorMetric = this.metric,
        dataClass: ClinicalDataClass = this.dataClass,
        allowedSources: Set<SensorSource> = this.allowedSources,
        destinationId: String = this.destinationId,
        streamId: String = this.streamId,
        sourceDeviceId: String = this.sourceDeviceId,
        gatewayDeviceId: String = this.gatewayDeviceId,
        acquisitionProtocolVersion: String = this.acquisitionProtocolVersion,
        consentGeneration: Long = this.consentGeneration,
        startsAtEpochMillis: Long = this.startsAtEpochMillis,
        endsAtEpochMillis: Long = this.endsAtEpochMillis,
        expectedSamplePeriodMillis: Long = this.expectedSamplePeriodMillis,
        minimumDisplayQuality: Double = this.minimumDisplayQuality,
        protocolVersion: String = this.protocolVersion,
        appVersion: String = this.appVersion,
        deviceModel: String = this.deviceModel,
        firmwareGeneration: String = this.firmwareGeneration,
        dataSchemaVersion: String = this.dataSchemaVersion,
        clinicalFeatureId: String = this.clinicalFeatureId,
        clinicalFeatureVersion: String = this.clinicalFeatureVersion,
        environmentFingerprintSha256: String = this.environmentFingerprintSha256,
    ) = MonitoringSession(
        sessionId,
        subjectPseudonym,
        purpose,
        authorizedObserverPrincipalIds,
        metric,
        dataClass,
        allowedSources,
        destinationId,
        streamId,
        sourceDeviceId,
        gatewayDeviceId,
        acquisitionProtocolVersion,
        consentGeneration,
        startsAtEpochMillis,
        endsAtEpochMillis,
        expectedSamplePeriodMillis,
        minimumDisplayQuality,
        protocolVersion,
        appVersion,
        deviceModel,
        firmwareGeneration,
        dataSchemaVersion,
        clinicalFeatureId,
        clinicalFeatureVersion,
        environmentFingerprintSha256,
    )
}

class LiveScalarSample(
    val sampleId: String,
    val sessionId: String,
    val streamId: String,
    val subjectPseudonym: String,
    val sequence: Long,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val unit: String,
    val value: Double,
    val observedAtEpochMillis: Long,
    val receivedAtGatewayEpochMillis: Long,
    quality: SignalQuality,
    val source: SensorSource,
    val sourceDeviceId: String,
    val gatewayDeviceId: String,
    val firmwareGeneration: String,
    val acquisitionProtocolVersion: String,
    val dataSchemaVersion: String,
    val consentGeneration: Long,
    provenanceIds: List<String>,
) {
    val quality: SignalQuality = quality.copy(reasons = java.util.List.copyOf(quality.reasons))
    val provenanceIds: List<String> = java.util.List.copyOf(provenanceIds)

    init {
        requireMonitoringIdentifier(sampleId, "sampleId")
        requireMonitoringIdentifier(sessionId, "sessionId")
        requireMonitoringIdentifier(streamId, "streamId")
        requireMonitoringIdentifier(subjectPseudonym, "subjectPseudonym")
        require(sequence >= 0L)
        require(dataClass != ClinicalDataClass.RAW_WAVEFORM) {
            "A scalar sample cannot carry the raw-waveform data class"
        }
        require(unit == metric.unit) { "Unit must match the canonical metric unit" }
        require(value.isFinite())
        require(observedAtEpochMillis > 0L)
        require(receivedAtGatewayEpochMillis >= observedAtEpochMillis)
        requireMonitoringIdentifier(sourceDeviceId, "sourceDeviceId")
        requireMonitoringIdentifier(gatewayDeviceId, "gatewayDeviceId")
        require(firmwareGeneration.isNotBlank())
        require(acquisitionProtocolVersion.isNotBlank())
        requireMonitoringIdentifier(dataSchemaVersion, "dataSchemaVersion")
        require(consentGeneration > 0L)
        require(this.provenanceIds.isNotEmpty())
        require(this.provenanceIds.all(MONITORING_IDENTIFIER_REGEX::matches))
    }

    override fun equals(other: Any?): Boolean =
        other is LiveScalarSample && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(
        sampleId, sessionId, streamId, subjectPseudonym, sequence, metric, dataClass, unit, value,
        observedAtEpochMillis, receivedAtGatewayEpochMillis, quality, source, sourceDeviceId,
        gatewayDeviceId, firmwareGeneration, acquisitionProtocolVersion, dataSchemaVersion,
        consentGeneration, provenanceIds,
    )

    fun copy(
        sampleId: String = this.sampleId,
        sessionId: String = this.sessionId,
        streamId: String = this.streamId,
        subjectPseudonym: String = this.subjectPseudonym,
        sequence: Long = this.sequence,
        metric: SensorMetric = this.metric,
        dataClass: ClinicalDataClass = this.dataClass,
        unit: String = this.unit,
        value: Double = this.value,
        observedAtEpochMillis: Long = this.observedAtEpochMillis,
        receivedAtGatewayEpochMillis: Long = this.receivedAtGatewayEpochMillis,
        quality: SignalQuality = this.quality,
        source: SensorSource = this.source,
        sourceDeviceId: String = this.sourceDeviceId,
        gatewayDeviceId: String = this.gatewayDeviceId,
        firmwareGeneration: String = this.firmwareGeneration,
        acquisitionProtocolVersion: String = this.acquisitionProtocolVersion,
        dataSchemaVersion: String = this.dataSchemaVersion,
        consentGeneration: Long = this.consentGeneration,
        provenanceIds: List<String> = this.provenanceIds,
    ) = LiveScalarSample(
        sampleId,
        sessionId,
        streamId,
        subjectPseudonym,
        sequence,
        metric,
        dataClass,
        unit,
        value,
        observedAtEpochMillis,
        receivedAtGatewayEpochMillis,
        quality,
        source,
        sourceDeviceId,
        gatewayDeviceId,
        firmwareGeneration,
        acquisitionProtocolVersion,
        dataSchemaVersion,
        consentGeneration,
        provenanceIds,
    )
}

enum class StreamAvailability {
    LIVE,
    DELAYED,
    STALE,
    NO_DATA,
    QUALITY_BLOCKED,
    VALIDATION_BLOCKED,
    AUTHORIZATION_BLOCKED,
    SESSION_INACTIVE,
    CLOCK_UNTRUSTED,
    SEQUENCE_INVALID,
}

class StreamStatus(
    val availability: StreamAvailability,
    val ageMillis: Long?,
    val measurementAtEpochMillis: Long?,
    val gatewayReceivedAtEpochMillis: Long?,
    val viewedAtEpochMillis: Long,
    val observerCoverage: ObserverCoverage,
    val activelyObserved: Boolean,
    val qualityBlocked: Boolean,
    val headline: String,
    reasonCodes: Set<String>,
) {
    val reasonCodes: Set<String> = java.util.Set.copyOf(reasonCodes)

    init {
        require(viewedAtEpochMillis > 0L)
        require(ageMillis == null || ageMillis >= 0L)
        if (activelyObserved) {
            require(observerCoverage == ObserverCoverage.ACTIVE)
            require(availability == StreamAvailability.LIVE)
            require(!qualityBlocked)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is StreamStatus && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    private fun valueFields(): List<Any?> = listOf(
        availability, ageMillis, measurementAtEpochMillis, gatewayReceivedAtEpochMillis,
        viewedAtEpochMillis, observerCoverage, activelyObserved, qualityBlocked, headline, reasonCodes,
    )
}

internal fun requireMonitoringIdentifier(value: String, fieldName: String) {
    require(value.matches(MONITORING_IDENTIFIER_REGEX)) {
        "$fieldName must contain 1-96 letters, numbers, dots, underscores or hyphens"
    }
}

private val MONITORING_IDENTIFIER_REGEX = Regex("[A-Za-z0-9._-]{1,96}")
private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
private const val MAXIMUM_EXPECTED_SAMPLE_PERIOD_MILLIS = 5 * 60_000L
private const val MAXIMUM_STALE_AFTER_MILLIS = 5 * 60_000L
