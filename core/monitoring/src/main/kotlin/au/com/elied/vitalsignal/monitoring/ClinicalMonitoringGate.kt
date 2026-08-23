package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.governance.ClinicianShareGrant
import au.com.elied.vitalsignal.governance.ClinicianSharePermit
import au.com.elied.vitalsignal.governance.ClinicianSharePermitDecision
import au.com.elied.vitalsignal.governance.ClinicianSharePermitIssuer
import au.com.elied.vitalsignal.governance.ClinicianSharePermitRequest
import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.MedicalPromotionPermit
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.ProductSurface
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource

enum class MonitoringAccessReason {
    ALLOWED,
    LOG_ONLY_NOT_SHAREABLE,
    SESSION_NOT_ACTIVE,
    SESSION_BINDING_MISMATCH,
    OBSERVER_NOT_AUTHORIZED,
    SHARE_AUTHORIZATION_DENIED,
    MEDICAL_PROMOTION_REQUIRED,
    MEDICAL_PROMOTION_INVALID,
}

data class MonitoringAccessRequest(
    val capability: PilotCapability,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val sessionId: String,
    val observerPrincipalId: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val destinationId: String,
    val evaluatedAtEpochMillis: Long,
    val collectionPaused: Boolean,
    val recoveryRequired: Boolean,
) {
    init {
        requireMonitoringIdentifier(subjectPseudonym, "subjectPseudonym")
        require(consentGeneration > 0L)
        requireMonitoringIdentifier(sessionId, "sessionId")
        requireMonitoringIdentifier(observerPrincipalId, "observerPrincipalId")
        requireMonitoringIdentifier(destinationId, "destinationId")
        require(evaluatedAtEpochMillis > 0L)
    }
}

sealed interface MonitoringAccessDecision {
    class Allowed internal constructor(
        val permit: MonitoringAccessPermit,
        val displayLabel: String,
    ) : MonitoringAccessDecision

    data class Denied(
        val reason: MonitoringAccessReason,
        val displayLabel: String = "Live sharing unavailable",
    ) : MonitoringAccessDecision
}

/** Opaque authority proving both exact sharing access and the session-purpose gate. */
class MonitoringAccessPermit private constructor(
    internal val clinicianSharePermit: ClinicianSharePermit,
    val purpose: MonitoringPurpose,
    val clinicalFeatureId: String?,
    val clinicalFeatureVersion: String?,
    val environmentFingerprintSha256: String?,
    val medicalEvidenceReceiptIds: List<String>,
    allowedSources: Set<SensorSource>,
    val sourceDeviceId: String,
    val gatewayDeviceId: String,
    val firmwareGeneration: String,
    val acquisitionProtocolVersion: String,
    val dataSchemaVersion: String,
    val validUntilEpochMillis: Long,
) {
    val allowedSources: Set<SensorSource> = java.util.Set.copyOf(allowedSources)
    val capability: PilotCapability get() = clinicianSharePermit.capability
    val subjectPseudonym: String get() = clinicianSharePermit.subjectPseudonym
    val consentGeneration: Long get() = clinicianSharePermit.consentGeneration
    val sessionId: String get() = clinicianSharePermit.sessionId
    val observerPrincipalId: String get() = clinicianSharePermit.observerPrincipalId
    val metric: SensorMetric get() = clinicianSharePermit.metric
    val dataClass: ClinicalDataClass get() = clinicianSharePermit.dataClass
    val destinationId: String get() = clinicianSharePermit.destinationId
    val sessionStartsAtEpochMillis: Long get() = clinicianSharePermit.sessionStartsAtEpochMillis
    val sessionEndsAtEpochMillis: Long get() = clinicianSharePermit.sessionEndsAtEpochMillis
    val issuedAtEpochMillis: Long get() = clinicianSharePermit.issuedAtEpochMillis
    val grantId: String get() = clinicianSharePermit.grantId
    val validationReceiptId: String get() = clinicianSharePermit.validationReceiptId

    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    companion object {
        internal fun issue(
            sharePermit: ClinicianSharePermit,
            session: MonitoringSession,
            medicalPromotionPermit: MedicalPromotionPermit?,
        ): MonitoringAccessPermit {
            val medical = if (session.purpose == MonitoringPurpose.REGULATED_CLINICAL_SERVICE) {
                requireNotNull(medicalPromotionPermit)
            } else {
                require(medicalPromotionPermit == null)
                null
            }
            return MonitoringAccessPermit(
                clinicianSharePermit = sharePermit,
                purpose = session.purpose,
                clinicalFeatureId = medical?.featureId,
                clinicalFeatureVersion = medical?.featureVersion,
                environmentFingerprintSha256 = medical?.environmentFingerprintSha256,
                medicalEvidenceReceiptIds = java.util.List.copyOf(
                    medical?.evidenceReceiptIds ?: emptyList(),
                ),
                allowedSources = session.allowedSources,
                sourceDeviceId = session.sourceDeviceId,
                gatewayDeviceId = session.gatewayDeviceId,
                firmwareGeneration = session.firmwareGeneration,
                acquisitionProtocolVersion = session.acquisitionProtocolVersion,
                dataSchemaVersion = session.dataSchemaVersion,
                validUntilEpochMillis = minOf(
                    sharePermit.validUntilEpochMillis,
                    medical?.validUntilEpochMillis ?: Long.MAX_VALUE,
                ),
            )
        }
    }
}

/**
 * Exact fail-closed sharing boundary. The gate creates its permit from current
 * signed consent and validation artifacts; it never accepts a caller-created
 * boolean decision as sharing authority.
 */
class ClinicalMonitoringGate(
    private val permitIssuer: ClinicianSharePermitIssuer,
) {
    fun evaluate(
        session: MonitoringSession,
        request: MonitoringAccessRequest,
        consent: ConsentGrant,
        validationReceipts: List<ValidationReceipt>,
        clinicianShareGrant: ClinicianShareGrant,
        medicalPromotionPermit: MedicalPromotionPermit?,
    ): MonitoringAccessDecision {
        if (session.purpose == MonitoringPurpose.RESEARCH_LOG_ONLY) {
            return deny(MonitoringAccessReason.LOG_ONLY_NOT_SHAREABLE)
        }
        if (request.evaluatedAtEpochMillis !in session.startsAtEpochMillis until session.endsAtEpochMillis) {
            return deny(MonitoringAccessReason.SESSION_NOT_ACTIVE)
        }
        if (!session.matches(request)) {
            return deny(MonitoringAccessReason.SESSION_BINDING_MISMATCH)
        }
        if (request.observerPrincipalId !in session.authorizedObserverPrincipalIds) {
            return deny(MonitoringAccessReason.OBSERVER_NOT_AUTHORIZED)
        }

        val permitDecision = permitIssuer.issue(
            request = ClinicianSharePermitRequest(
                capability = request.capability,
                subjectPseudonym = request.subjectPseudonym,
                consentGeneration = request.consentGeneration,
                sessionId = request.sessionId,
                observerPrincipalId = request.observerPrincipalId,
                metric = request.metric,
                dataClass = request.dataClass,
                destinationId = request.destinationId,
                sessionStartsAtEpochMillis = session.startsAtEpochMillis,
                sessionEndsAtEpochMillis = session.endsAtEpochMillis,
                appVersion = session.appVersion,
                deviceModel = session.deviceModel,
                firmwareGeneration = session.firmwareGeneration,
                dataSchemaVersion = session.dataSchemaVersion,
                evaluatedAtEpochMillis = request.evaluatedAtEpochMillis,
                collectionPaused = request.collectionPaused,
                recoveryRequired = request.recoveryRequired,
            ),
            consent = consent,
            validationReceipts = validationReceipts,
            grant = clinicianShareGrant,
        )
        val permit = when (permitDecision) {
            is ClinicianSharePermitDecision.Allowed -> permitDecision.permit
            is ClinicianSharePermitDecision.Denied -> {
                return deny(MonitoringAccessReason.SHARE_AUTHORIZATION_DENIED)
            }
        }
        if (!permit.matches(session, request) || !permit.isCurrentAt(request.evaluatedAtEpochMillis)) {
            return deny(MonitoringAccessReason.SHARE_AUTHORIZATION_DENIED)
        }

        if (session.purpose == MonitoringPurpose.REGULATED_CLINICAL_SERVICE) {
            val promotion = medicalPromotionPermit
                ?: return deny(MonitoringAccessReason.MEDICAL_PROMOTION_REQUIRED)
            if (!promotion.matches(session, request.evaluatedAtEpochMillis)) {
                return deny(MonitoringAccessReason.MEDICAL_PROMOTION_INVALID)
            }
            return MonitoringAccessDecision.Allowed(
                permit = MonitoringAccessPermit.issue(permit, session, promotion),
                displayLabel = "Validated clinical monitoring service",
            )
        }

        return MonitoringAccessDecision.Allowed(
            permit = MonitoringAccessPermit.issue(permit, session, null),
            displayLabel = "Observed research session — not a clinical monitoring service",
        )
    }

    private fun MonitoringSession.matches(request: MonitoringAccessRequest): Boolean =
        request.capability == PilotCapability.CLINICIAN_LIVE_SHARE &&
            sessionId == request.sessionId &&
            subjectPseudonym == request.subjectPseudonym &&
            consentGeneration == request.consentGeneration &&
            metric == request.metric &&
            dataClass == request.dataClass &&
            destinationId == request.destinationId

    private fun ClinicianSharePermit.matches(
        session: MonitoringSession,
        request: MonitoringAccessRequest,
    ): Boolean =
        capability == PilotCapability.CLINICIAN_LIVE_SHARE &&
            sessionId == session.sessionId &&
            subjectPseudonym == session.subjectPseudonym &&
            consentGeneration == session.consentGeneration &&
            observerPrincipalId == request.observerPrincipalId &&
            metric == session.metric &&
            dataClass == session.dataClass &&
            destinationId == session.destinationId &&
            sessionStartsAtEpochMillis == session.startsAtEpochMillis &&
            sessionEndsAtEpochMillis == session.endsAtEpochMillis

    private fun MedicalPromotionPermit.matches(session: MonitoringSession, nowEpochMillis: Long): Boolean =
        surface == ProductSurface.MEDICAL_INTENDED_USE &&
            featureId == session.clinicalFeatureId &&
            featureVersion == session.clinicalFeatureVersion &&
            environmentFingerprintSha256 == session.environmentFingerprintSha256 &&
            isCurrentAt(nowEpochMillis)

    private fun deny(reason: MonitoringAccessReason) = MonitoringAccessDecision.Denied(reason)
}
