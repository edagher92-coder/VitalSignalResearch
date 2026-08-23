package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * A FHIR-shaped draft for a destination connector. It is not an AU Core or
 * destination-profile conformance claim; the connector must map references and
 * validate the completed resource before transmission.
 */
data class FhirCodingDraft(
    val system: String,
    val code: String,
    val display: String,
)

data class FhirQuantityDraft(
    val value: Double,
    val unit: String,
    val system: String,
    val code: String,
)

class FhirQualityEvidenceDraft(
    val score: Double,
    val coverage: Double,
    val contact: Double,
    val motionContamination: Double,
    val validity: Double,
    val clipping: Double,
    val timestampContinuity: Double,
    val usable: Boolean,
    reasons: List<String>,
    val evaluatorVersion: String,
) {
    val reasons: List<String> = java.util.List.copyOf(reasons)

    init {
        require(score in 0.0..1.0)
        require(coverage in 0.0..1.0)
        require(contact in 0.0..1.0)
        require(motionContamination in 0.0..1.0)
        require(validity in 0.0..1.0)
        require(clipping in 0.0..1.0)
        require(timestampContinuity in 0.0..1.0)
        require(evaluatorVersion.isNotBlank())
    }
}

class FhirObservationDraft(
    val observationId: String,
    val status: String,
    val sessionId: String,
    val sampleId: String,
    val streamId: String,
    val sequence: Long,
    val subjectPseudonym: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val source: SensorSource,
    val code: FhirCodingDraft,
    val quantity: FhirQuantityDraft,
    val effectiveEpochMillis: Long,
    val issuedEpochMillis: Long,
    val sourceDeviceId: String,
    val gatewayDeviceId: String,
    val firmwareGeneration: String,
    val acquisitionProtocolVersion: String,
    val dataSchemaVersion: String,
    val consentGeneration: Long,
    val qualityEvidence: FhirQualityEvidenceDraft,
    provenanceIds: List<String>,
    val exportAuditEventId: String,
    securityLabels: Set<FhirCodingDraft>,
) {
    val provenanceIds: List<String> = java.util.List.copyOf(provenanceIds)
    val securityLabels: Set<FhirCodingDraft> = java.util.Set.copyOf(securityLabels)

    init {
        require(observationId.matches(FHIR_ID_REGEX))
        require(status in FHIR_OBSERVATION_STATUSES)
        requireMonitoringIdentifier(sessionId, "sessionId")
        requireMonitoringIdentifier(sampleId, "sampleId")
        requireMonitoringIdentifier(streamId, "streamId")
        require(sequence >= 0L)
        requireMonitoringIdentifier(subjectPseudonym, "subjectPseudonym")
        require(quantity.value.isFinite())
        require(effectiveEpochMillis > 0L)
        require(issuedEpochMillis >= effectiveEpochMillis)
        requireMonitoringIdentifier(sourceDeviceId, "sourceDeviceId")
        requireMonitoringIdentifier(gatewayDeviceId, "gatewayDeviceId")
        require(firmwareGeneration.isNotBlank())
        require(acquisitionProtocolVersion.isNotBlank())
        requireMonitoringIdentifier(dataSchemaVersion, "dataSchemaVersion")
        require(consentGeneration > 0L)
        require(this.provenanceIds.isNotEmpty())
        this.provenanceIds.forEach { requireMonitoringIdentifier(it, "provenanceId") }
        require(exportAuditEventId.matches(FHIR_ID_REGEX))
        require(this.securityLabels.isNotEmpty())
    }
}

data class ClinicalExportAuditEntry(
    val auditEventId: String,
    val observationId: String,
    val sessionId: String,
    val sampleId: String,
    val subjectPseudonym: String,
    val observerPrincipalId: String,
    val destinationId: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val consentGeneration: Long,
    val clinicianShareGrantId: String,
    val validationReceiptId: String,
    val draftSha256: String,
    val committedAtEpochMillis: Long,
) {
    init {
        require(auditEventId.matches(FHIR_ID_REGEX))
        require(observationId.matches(FHIR_ID_REGEX))
        requireMonitoringIdentifier(sessionId, "sessionId")
        requireMonitoringIdentifier(sampleId, "sampleId")
        requireMonitoringIdentifier(subjectPseudonym, "subjectPseudonym")
        requireMonitoringIdentifier(observerPrincipalId, "observerPrincipalId")
        requireMonitoringIdentifier(destinationId, "destinationId")
        require(consentGeneration > 0L)
        requireMonitoringIdentifier(clinicianShareGrantId, "clinicianShareGrantId")
        requireMonitoringIdentifier(validationReceiptId, "validationReceiptId")
        require(draftSha256.matches(SHA_256_REGEX))
        require(committedAtEpochMillis > 0L)
    }
}

fun interface ClinicalExportAuditSink {
    /** Returns true only after the exact export event is durably committed. */
    fun commit(entry: ClinicalExportAuditEntry): Boolean
}

class FhirObservationProjector(
    private val auditSink: ClinicalExportAuditSink,
) {
    fun project(
        session: MonitoringSession,
        sample: LiveScalarSample,
        permit: MonitoringAccessPermit,
        nowEpochMillis: Long,
    ): FhirObservationDraft {
        require(nowEpochMillis > 0L)
        require(session.purpose != MonitoringPurpose.RESEARCH_LOG_ONLY) {
            "Research-log-only sessions cannot produce clinician export drafts"
        }
        require(nowEpochMillis in session.startsAtEpochMillis until session.endsAtEpochMillis) {
            "Monitoring session is not active"
        }
        require(permit.matches(session, nowEpochMillis)) {
            "A current destination-bound clinician-share permit is required"
        }
        require(sample.matches(session, permit)) { "Sample is not bound to the authorized stream" }
        require(sample.observedAtEpochMillis in session.startsAtEpochMillis until session.endsAtEpochMillis)
        require(sample.observedAtEpochMillis <= nowEpochMillis)
        require(sample.receivedAtGatewayEpochMillis <= nowEpochMillis)
        require(nowEpochMillis - sample.observedAtEpochMillis < session.staleAfterMillis) {
            "Stale live samples are not projected as current numeric drafts"
        }
        require(sample.quality.usable && sample.quality.score >= session.minimumDisplayQuality) {
            "Unusable measurements are not projected as numeric observations"
        }
        val sampleRejection = ClinicalScalarSamplePolicy.rejectionCode(sample)
        require(sampleRejection == null) {
            "Sample failed the reviewed clinical numeric safety gate: $sampleRejection"
        }

        val metricMapping = mappingFor(sample.metric)
        val observationId = fhirId(
            prefix = "vs-",
            canonical = listOf(
                session.sessionId,
                sample.sampleId,
                sample.metric.name,
                sample.dataClass.name,
                sample.source.name,
                metricMapping.code.system,
                metricMapping.code.code,
                sample.value.toRawBits().toString(),
                sample.unit,
                sample.observedAtEpochMillis.toString(),
                sample.receivedAtGatewayEpochMillis.toString(),
                sample.sequence.toString(),
                sample.sourceDeviceId,
                sample.gatewayDeviceId,
                sample.firmwareGeneration,
                sample.acquisitionProtocolVersion,
                sample.dataSchemaVersion,
                sample.consentGeneration.toString(),
                sample.quality.score.toRawBits().toString(),
                sample.quality.coverage.toRawBits().toString(),
                sample.quality.contact.toRawBits().toString(),
                sample.quality.motionContamination.toRawBits().toString(),
                sample.quality.validity.toRawBits().toString(),
                sample.quality.clipping.toRawBits().toString(),
                sample.quality.timestampContinuity.toRawBits().toString(),
                sample.quality.evaluatorVersion,
                sample.quality.reasons.joinToString("\u0001"),
                sample.provenanceIds.joinToString("\u0001"),
                permit.destinationId,
                permit.consentGeneration.toString(),
            ).joinToString("\u0000"),
        )
        val auditEventId = fhirId(
            prefix = "vsa-",
            canonical = listOf(
                observationId,
                permit.grantId,
                permit.validationReceiptId,
                nowEpochMillis.toString(),
            ).joinToString("\u0000"),
        )
        val draft = FhirObservationDraft(
            observationId = observationId,
            status = "preliminary",
            sessionId = sample.sessionId,
            sampleId = sample.sampleId,
            streamId = sample.streamId,
            sequence = sample.sequence,
            subjectPseudonym = sample.subjectPseudonym,
            metric = sample.metric,
            dataClass = sample.dataClass,
            source = sample.source,
            code = metricMapping.code,
            quantity = FhirQuantityDraft(
                value = sample.value,
                unit = sample.unit,
                system = UCUM_SYSTEM,
                code = metricMapping.ucumCode,
            ),
            effectiveEpochMillis = sample.observedAtEpochMillis,
            issuedEpochMillis = nowEpochMillis,
            sourceDeviceId = sample.sourceDeviceId,
            gatewayDeviceId = sample.gatewayDeviceId,
            firmwareGeneration = sample.firmwareGeneration,
            acquisitionProtocolVersion = sample.acquisitionProtocolVersion,
            dataSchemaVersion = sample.dataSchemaVersion,
            consentGeneration = sample.consentGeneration,
            qualityEvidence = FhirQualityEvidenceDraft(
                score = sample.quality.score,
                coverage = sample.quality.coverage,
                contact = sample.quality.contact,
                motionContamination = sample.quality.motionContamination,
                validity = sample.quality.validity,
                clipping = sample.quality.clipping,
                timestampContinuity = sample.quality.timestampContinuity,
                usable = sample.quality.usable,
                reasons = sample.quality.reasons,
                evaluatorVersion = sample.quality.evaluatorVersion,
            ),
            provenanceIds = sample.provenanceIds,
            exportAuditEventId = auditEventId,
            securityLabels = setOf(
                FhirCodingDraft(
                    system = V3_CONFIDENTIALITY_SYSTEM,
                    code = "R",
                    display = "Restricted",
                ),
                FhirCodingDraft(
                    system = VITALSIGNAL_SECURITY_LABEL_SYSTEM,
                    code = "research-device-data",
                    display = "Research device data",
                ),
            ),
        )
        val audit = ClinicalExportAuditEntry(
            auditEventId = auditEventId,
            observationId = observationId,
            sessionId = session.sessionId,
            sampleId = sample.sampleId,
            subjectPseudonym = sample.subjectPseudonym,
            observerPrincipalId = permit.observerPrincipalId,
            destinationId = permit.destinationId,
            metric = sample.metric,
            dataClass = sample.dataClass,
            consentGeneration = sample.consentGeneration,
            clinicianShareGrantId = permit.grantId,
            validationReceiptId = permit.validationReceiptId,
            draftSha256 = canonicalFhirDraftSha256(draft),
            committedAtEpochMillis = nowEpochMillis,
        )
        check(auditSink.commit(audit)) { "Clinical export audit was not durably committed" }
        return draft
    }

    private fun MonitoringAccessPermit.matches(session: MonitoringSession, nowEpochMillis: Long): Boolean =
        capability == PilotCapability.CLINICIAN_LIVE_SHARE &&
            purpose == session.purpose &&
            purpose != MonitoringPurpose.RESEARCH_LOG_ONLY &&
            subjectPseudonym == session.subjectPseudonym &&
            consentGeneration == session.consentGeneration &&
            sessionId == session.sessionId &&
            observerPrincipalId in session.authorizedObserverPrincipalIds &&
            metric == session.metric &&
            dataClass == ClinicalDataClass.FHIR_OBSERVATION_DRAFT &&
            dataClass == session.dataClass &&
            destinationId == session.destinationId &&
            allowedSources == session.allowedSources &&
            sourceDeviceId == session.sourceDeviceId &&
            gatewayDeviceId == session.gatewayDeviceId &&
            firmwareGeneration == session.firmwareGeneration &&
            acquisitionProtocolVersion == session.acquisitionProtocolVersion &&
            dataSchemaVersion == session.dataSchemaVersion &&
            sessionStartsAtEpochMillis == session.startsAtEpochMillis &&
            sessionEndsAtEpochMillis == session.endsAtEpochMillis &&
            (purpose != MonitoringPurpose.REGULATED_CLINICAL_SERVICE ||
                (clinicalFeatureId == session.clinicalFeatureId &&
                    clinicalFeatureVersion == session.clinicalFeatureVersion &&
                    environmentFingerprintSha256 == session.environmentFingerprintSha256 &&
                    medicalEvidenceReceiptIds.isNotEmpty())) &&
            isCurrentAt(nowEpochMillis)

    private fun LiveScalarSample.matches(
        session: MonitoringSession,
        permit: MonitoringAccessPermit,
    ): Boolean =
        sessionId == session.sessionId &&
            streamId == session.streamId &&
            subjectPseudonym == session.subjectPseudonym &&
            metric == session.metric &&
            dataClass == session.dataClass &&
            source in session.allowedSources &&
            source in permit.allowedSources &&
            sourceDeviceId == session.sourceDeviceId &&
            gatewayDeviceId == session.gatewayDeviceId &&
            firmwareGeneration == session.firmwareGeneration &&
            acquisitionProtocolVersion == session.acquisitionProtocolVersion &&
            dataSchemaVersion == session.dataSchemaVersion &&
            dataSchemaVersion == permit.dataSchemaVersion &&
            consentGeneration == session.consentGeneration &&
            consentGeneration == permit.consentGeneration

    private fun mappingFor(metric: SensorMetric): MetricMapping = when (metric) {
        SensorMetric.HEART_RATE -> MetricMapping(
            code = FhirCodingDraft(LOINC_SYSTEM, "8867-4", "Heart rate"),
            ucumCode = "/min",
        )
        SensorMetric.RESPIRATORY_RATE -> MetricMapping(
            code = FhirCodingDraft(LOINC_SYSTEM, "9279-1", "Respiratory rate"),
            ucumCode = "/min",
        )
        SensorMetric.OXYGEN_SATURATION -> MetricMapping(
            code = FhirCodingDraft(
                LOINC_SYSTEM,
                "59408-5",
                "Oxygen saturation in Arterial blood by Pulse oximetry",
            ),
            ucumCode = "%",
        )
        else -> throw IllegalArgumentException(
            "No reviewed FHIR coding exists for ${metric.name}",
        )
    }

    private fun fhirId(prefix: String, canonical: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return prefix + digest.take(64 - prefix.length)
    }

    private data class MetricMapping(
        val code: FhirCodingDraft,
        val ucumCode: String,
    )
}

internal fun canonicalFhirDraftSha256(draft: FhirObservationDraft): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        fun writeString(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            output.writeInt(encoded.size)
            output.write(encoded)
        }
        writeString("vitalsignal.fhir-observation-draft.v2")
        writeString(draft.observationId)
        writeString(draft.status)
        writeString(draft.sessionId)
        writeString(draft.sampleId)
        writeString(draft.streamId)
        output.writeLong(draft.sequence)
        writeString(draft.subjectPseudonym)
        writeString(draft.metric.name)
        writeString(draft.dataClass.name)
        writeString(draft.source.name)
        writeString(draft.code.system)
        writeString(draft.code.code)
        writeString(draft.code.display)
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.quantity.value))
        writeString(draft.quantity.unit)
        writeString(draft.quantity.system)
        writeString(draft.quantity.code)
        output.writeLong(draft.effectiveEpochMillis)
        output.writeLong(draft.issuedEpochMillis)
        writeString(draft.sourceDeviceId)
        writeString(draft.gatewayDeviceId)
        writeString(draft.firmwareGeneration)
        writeString(draft.acquisitionProtocolVersion)
        writeString(draft.dataSchemaVersion)
        output.writeLong(draft.consentGeneration)
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.score))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.coverage))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.contact))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.motionContamination))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.validity))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.clipping))
        output.writeLong(java.lang.Double.doubleToRawLongBits(draft.qualityEvidence.timestampContinuity))
        output.writeBoolean(draft.qualityEvidence.usable)
        writeString(draft.qualityEvidence.evaluatorVersion)
        output.writeInt(draft.qualityEvidence.reasons.size)
        draft.qualityEvidence.reasons.forEach(::writeString)
        output.writeInt(draft.provenanceIds.size)
        draft.provenanceIds.forEach(::writeString)
        writeString(draft.exportAuditEventId)
        val labels = draft.securityLabels.sortedWith(
            compareBy(FhirCodingDraft::system, FhirCodingDraft::code, FhirCodingDraft::display),
        )
        output.writeInt(labels.size)
        labels.forEach { label ->
            writeString(label.system)
            writeString(label.code)
            writeString(label.display)
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private val FHIR_ID_REGEX = Regex("[A-Za-z0-9\\-.]{1,64}")
private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
private val FHIR_OBSERVATION_STATUSES = setOf(
    "registered",
    "preliminary",
    "final",
    "amended",
    "corrected",
    "cancelled",
    "entered-in-error",
    "unknown",
)
private const val LOINC_SYSTEM = "http://loinc.org"
private const val UCUM_SYSTEM = "http://unitsofmeasure.org"
private const val V3_CONFIDENTIALITY_SYSTEM =
    "http://terminology.hl7.org/CodeSystem/v3-Confidentiality"
private const val VITALSIGNAL_SECURITY_LABEL_SYSTEM = "urn:vitalsignal:security-label"
