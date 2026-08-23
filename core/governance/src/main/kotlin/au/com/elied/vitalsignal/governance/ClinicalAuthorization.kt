package au.com.elied.vitalsignal.governance

import au.com.elied.vitalsignal.model.SensorMetric

enum class ClinicalDataClass {
    DERIVED_SCALAR_SUMMARY,
    FHIR_OBSERVATION_DRAFT,
    RAW_WAVEFORM,
}

/**
 * Signed, exact consent for one clinician-sharing route. This is deliberately
 * narrower than the general consent scope: observer, session, metric, data
 * class and destination are authenticated fields rather than caller claims.
 */
class ClinicianShareGrant(
    val grantId: String,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val sessionId: String,
    val observerPrincipalId: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val destinationId: String,
    val issuedAtEpochMillis: Long,
    val startsAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val termsSha256: String,
    val signerKeyId: String,
    signature: ByteArray,
) {
    private val signatureSnapshot = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        requireIdentifier(grantId, "grantId")
        requireIdentifier(subjectPseudonym, "subjectPseudonym")
        require(consentGeneration > 0L)
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(observerPrincipalId, "observerPrincipalId")
        requireIdentifier(destinationId, "destinationId")
        require(issuedAtEpochMillis > 0L)
        require(startsAtEpochMillis >= issuedAtEpochMillis)
        require(expiresAtEpochMillis > startsAtEpochMillis)
        requireSha256(termsSha256, "termsSha256")
        require(signerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is ClinicianShareGrant &&
            grantId == other.grantId &&
            subjectPseudonym == other.subjectPseudonym &&
            consentGeneration == other.consentGeneration &&
            sessionId == other.sessionId &&
            observerPrincipalId == other.observerPrincipalId &&
            metric == other.metric &&
            dataClass == other.dataClass &&
            destinationId == other.destinationId &&
            issuedAtEpochMillis == other.issuedAtEpochMillis &&
            startsAtEpochMillis == other.startsAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            termsSha256 == other.termsSha256 &&
            signerKeyId == other.signerKeyId &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * listOf(
        grantId,
        subjectPseudonym,
        consentGeneration,
        sessionId,
        observerPrincipalId,
        metric,
        dataClass,
        destinationId,
        issuedAtEpochMillis,
        startsAtEpochMillis,
        expiresAtEpochMillis,
        termsSha256,
        signerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()

    fun copy(
        grantId: String = this.grantId,
        subjectPseudonym: String = this.subjectPseudonym,
        consentGeneration: Long = this.consentGeneration,
        sessionId: String = this.sessionId,
        observerPrincipalId: String = this.observerPrincipalId,
        metric: SensorMetric = this.metric,
        dataClass: ClinicalDataClass = this.dataClass,
        destinationId: String = this.destinationId,
        issuedAtEpochMillis: Long = this.issuedAtEpochMillis,
        startsAtEpochMillis: Long = this.startsAtEpochMillis,
        expiresAtEpochMillis: Long = this.expiresAtEpochMillis,
        termsSha256: String = this.termsSha256,
        signerKeyId: String = this.signerKeyId,
        signature: ByteArray = this.signature,
    ) = ClinicianShareGrant(
        grantId,
        subjectPseudonym,
        consentGeneration,
        sessionId,
        observerPrincipalId,
        metric,
        dataClass,
        destinationId,
        issuedAtEpochMillis,
        startsAtEpochMillis,
        expiresAtEpochMillis,
        termsSha256,
        signerKeyId,
        signature,
    )
}

fun interface ClinicianShareGrantVerifier {
    fun verify(grant: ClinicianShareGrant): Boolean
}

data class ClinicianSharePermitRequest(
    val capability: PilotCapability,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val sessionId: String,
    val observerPrincipalId: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val destinationId: String,
    val sessionStartsAtEpochMillis: Long,
    val sessionEndsAtEpochMillis: Long,
    val appVersion: String,
    val deviceModel: String,
    val firmwareGeneration: String,
    val dataSchemaVersion: String,
    val evaluatedAtEpochMillis: Long,
    val collectionPaused: Boolean,
    val recoveryRequired: Boolean,
) {
    init {
        requireIdentifier(subjectPseudonym, "subjectPseudonym")
        require(consentGeneration > 0L)
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(observerPrincipalId, "observerPrincipalId")
        requireIdentifier(destinationId, "destinationId")
        require(sessionStartsAtEpochMillis > 0L)
        require(sessionEndsAtEpochMillis > sessionStartsAtEpochMillis)
        require(appVersion.isNotBlank())
        require(deviceModel.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(dataSchemaVersion.isNotBlank())
        require(evaluatedAtEpochMillis > 0L)
    }
}

enum class ClinicianSharePermitDenialReason {
    CAPABILITY_MISMATCH,
    PILOT_GATE_DENIED,
    GRANT_SIGNATURE_INVALID,
    GRANT_BINDING_MISMATCH,
    GRANT_NOT_YET_ACTIVE,
    GRANT_EXPIRED,
}

class ClinicianSharePermit private constructor(
    val capability: PilotCapability,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val sessionId: String,
    val observerPrincipalId: String,
    val metric: SensorMetric,
    val dataClass: ClinicalDataClass,
    val destinationId: String,
    val sessionStartsAtEpochMillis: Long,
    val sessionEndsAtEpochMillis: Long,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val grantId: String,
    val validationReceiptId: String,
) {
    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    companion object {
        internal fun issue(
            capability: PilotCapability,
            subjectPseudonym: String,
            consentGeneration: Long,
            sessionId: String,
            observerPrincipalId: String,
            metric: SensorMetric,
            dataClass: ClinicalDataClass,
            destinationId: String,
            sessionStartsAtEpochMillis: Long,
            sessionEndsAtEpochMillis: Long,
            issuedAtEpochMillis: Long,
            validUntilEpochMillis: Long,
            grantId: String,
            validationReceiptId: String,
        ) = ClinicianSharePermit(
            capability,
            subjectPseudonym,
            consentGeneration,
            sessionId,
            observerPrincipalId,
            metric,
            dataClass,
            destinationId,
            sessionStartsAtEpochMillis,
            sessionEndsAtEpochMillis,
            issuedAtEpochMillis,
            validUntilEpochMillis,
            grantId,
            validationReceiptId,
        )
    }
}

sealed interface ClinicianSharePermitDecision {
    class Allowed internal constructor(val permit: ClinicianSharePermit) : ClinicianSharePermitDecision

    data class Denied(
        val reason: ClinicianSharePermitDenialReason,
        val pilotDecision: PilotGateDecision? = null,
    ) : ClinicianSharePermitDecision
}

class ClinicianSharePermitIssuer(
    private val pilotAccessGate: PilotAccessGate,
    private val grantVerifier: ClinicianShareGrantVerifier,
    private val maximumPermitLifetimeMillis: Long = DEFAULT_CLINICIAN_PERMIT_LIFETIME_MILLIS,
) {
    init {
        require(maximumPermitLifetimeMillis in 1L..MAXIMUM_CLINICIAN_PERMIT_LIFETIME_MILLIS)
    }

    fun issue(
        request: ClinicianSharePermitRequest,
        consent: ConsentGrant,
        validationReceipts: List<ValidationReceipt>,
        grant: ClinicianShareGrant,
    ): ClinicianSharePermitDecision {
        if (request.capability != PilotCapability.CLINICIAN_LIVE_SHARE) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.CAPABILITY_MISMATCH,
            )
        }

        val pilotDecision = pilotAccessGate.evaluate(
            request = PilotGateRequest(
                capability = request.capability,
                subjectPseudonym = request.subjectPseudonym,
                consentGeneration = request.consentGeneration,
                appVersion = request.appVersion,
                deviceModel = request.deviceModel,
                firmwareGeneration = request.firmwareGeneration,
                dataSchemaVersion = request.dataSchemaVersion,
                evaluatedAtEpochMillis = request.evaluatedAtEpochMillis,
                collectionPaused = request.collectionPaused,
                recoveryRequired = request.recoveryRequired,
            ),
            consent = consent,
            validationReceipts = validationReceipts,
        )
        if (!pilotDecision.allowed || pilotDecision.reason != PilotGateReason.ALLOWED) {
            return ClinicianSharePermitDecision.Denied(
                reason = ClinicianSharePermitDenialReason.PILOT_GATE_DENIED,
                pilotDecision = pilotDecision,
            )
        }
        if (!grantVerifier.verify(grant)) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.GRANT_SIGNATURE_INVALID,
                pilotDecision,
            )
        }
        if (!grant.matches(request)) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.GRANT_BINDING_MISMATCH,
                pilotDecision,
            )
        }
        if (request.evaluatedAtEpochMillis < grant.startsAtEpochMillis) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.GRANT_NOT_YET_ACTIVE,
                pilotDecision,
            )
        }
        if (request.evaluatedAtEpochMillis >= grant.expiresAtEpochMillis) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.GRANT_EXPIRED,
                pilotDecision,
            )
        }

        val validationReceipt = validationReceipts.singleOrNull {
            it.receiptId == pilotDecision.validationReceiptId
        } ?: return ClinicianSharePermitDecision.Denied(
            ClinicianSharePermitDenialReason.PILOT_GATE_DENIED,
            pilotDecision,
        )
        val requestedPermitEnd = saturatedAdd(
            request.evaluatedAtEpochMillis,
            maximumPermitLifetimeMillis,
        )
        val consentEnd = consent.expiresAtEpochMillis ?: Long.MAX_VALUE
        val validUntil = minOf(
            requestedPermitEnd,
            grant.expiresAtEpochMillis,
            consentEnd,
            validationReceipt.expiresAtEpochMillis,
        )
        if (validUntil <= request.evaluatedAtEpochMillis) {
            return ClinicianSharePermitDecision.Denied(
                ClinicianSharePermitDenialReason.PILOT_GATE_DENIED,
                pilotDecision,
            )
        }
        return ClinicianSharePermitDecision.Allowed(
            ClinicianSharePermit.issue(
                capability = request.capability,
                subjectPseudonym = request.subjectPseudonym,
                consentGeneration = request.consentGeneration,
                sessionId = request.sessionId,
                observerPrincipalId = request.observerPrincipalId,
                metric = request.metric,
                dataClass = request.dataClass,
                destinationId = request.destinationId,
                sessionStartsAtEpochMillis = request.sessionStartsAtEpochMillis,
                sessionEndsAtEpochMillis = request.sessionEndsAtEpochMillis,
                issuedAtEpochMillis = request.evaluatedAtEpochMillis,
                validUntilEpochMillis = validUntil,
                grantId = grant.grantId,
                validationReceiptId = validationReceipt.receiptId,
            ),
        )
    }

    private fun ClinicianShareGrant.matches(request: ClinicianSharePermitRequest): Boolean =
        subjectPseudonym == request.subjectPseudonym &&
            consentGeneration == request.consentGeneration &&
            sessionId == request.sessionId &&
            observerPrincipalId == request.observerPrincipalId &&
            metric == request.metric &&
            dataClass == request.dataClass &&
            destinationId == request.destinationId &&
            startsAtEpochMillis == request.sessionStartsAtEpochMillis &&
            expiresAtEpochMillis == request.sessionEndsAtEpochMillis
}

class ObserverHeartbeatReceipt(
    val receiptId: String,
    val sessionId: String,
    val observerPrincipalId: String,
    val destinationId: String,
    val recordedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    private val signatureSnapshot = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        requireIdentifier(receiptId, "receiptId")
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(observerPrincipalId, "observerPrincipalId")
        requireIdentifier(destinationId, "destinationId")
        require(recordedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > recordedAtEpochMillis)
        require(issuerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    fun copy(
        receiptId: String = this.receiptId,
        sessionId: String = this.sessionId,
        observerPrincipalId: String = this.observerPrincipalId,
        destinationId: String = this.destinationId,
        recordedAtEpochMillis: Long = this.recordedAtEpochMillis,
        expiresAtEpochMillis: Long = this.expiresAtEpochMillis,
        issuerKeyId: String = this.issuerKeyId,
        signature: ByteArray = this.signature,
    ) = ObserverHeartbeatReceipt(
        receiptId,
        sessionId,
        observerPrincipalId,
        destinationId,
        recordedAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
        signature,
    )

    override fun equals(other: Any?): Boolean =
        other is ObserverHeartbeatReceipt &&
            receiptId == other.receiptId &&
            sessionId == other.sessionId &&
            observerPrincipalId == other.observerPrincipalId &&
            destinationId == other.destinationId &&
            recordedAtEpochMillis == other.recordedAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            issuerKeyId == other.issuerKeyId &&
            signatureSnapshot.contentEquals(other.signatureSnapshot)

    override fun hashCode(): Int = 31 * listOf(
        receiptId, sessionId, observerPrincipalId, destinationId, recordedAtEpochMillis,
        expiresAtEpochMillis, issuerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()
}

fun interface ObserverHeartbeatReceiptVerifier {
    fun verify(receipt: ObserverHeartbeatReceipt): Boolean
}

class ObserverHeartbeatLease private constructor(
    val receiptId: String,
    val sessionId: String,
    val observerPrincipalId: String,
    val destinationId: String,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
) {
    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    companion object {
        internal fun issue(
            receiptId: String,
            sessionId: String,
            observerPrincipalId: String,
            destinationId: String,
            issuedAtEpochMillis: Long,
            validUntilEpochMillis: Long,
        ) = ObserverHeartbeatLease(
            receiptId,
            sessionId,
            observerPrincipalId,
            destinationId,
            issuedAtEpochMillis,
            validUntilEpochMillis,
        )
    }
}

enum class ObserverHeartbeatDenialReason {
    SIGNATURE_INVALID,
    NOT_YET_ACTIVE,
    STALE,
    EXPIRED,
}

sealed interface ObserverHeartbeatDecision {
    class Allowed internal constructor(val lease: ObserverHeartbeatLease) : ObserverHeartbeatDecision
    data class Denied(val reason: ObserverHeartbeatDenialReason) : ObserverHeartbeatDecision
}

class ObserverHeartbeatGate(
    private val verifier: ObserverHeartbeatReceiptVerifier,
    private val maximumHeartbeatAgeMillis: Long = DEFAULT_HEARTBEAT_AGE_MILLIS,
) {
    init {
        require(maximumHeartbeatAgeMillis in 1L..MAXIMUM_HEARTBEAT_AGE_MILLIS)
    }

    fun issue(receipt: ObserverHeartbeatReceipt, evaluatedAtEpochMillis: Long): ObserverHeartbeatDecision {
        require(evaluatedAtEpochMillis > 0L)
        if (!verifier.verify(receipt)) {
            return ObserverHeartbeatDecision.Denied(ObserverHeartbeatDenialReason.SIGNATURE_INVALID)
        }
        if (evaluatedAtEpochMillis < receipt.recordedAtEpochMillis) {
            return ObserverHeartbeatDecision.Denied(ObserverHeartbeatDenialReason.NOT_YET_ACTIVE)
        }
        if (evaluatedAtEpochMillis >= receipt.expiresAtEpochMillis) {
            return ObserverHeartbeatDecision.Denied(ObserverHeartbeatDenialReason.EXPIRED)
        }
        val heartbeatValidUntil = minOf(
            receipt.expiresAtEpochMillis,
            saturatedAdd(receipt.recordedAtEpochMillis, maximumHeartbeatAgeMillis),
        )
        if (evaluatedAtEpochMillis >= heartbeatValidUntil) {
            return ObserverHeartbeatDecision.Denied(ObserverHeartbeatDenialReason.STALE)
        }
        return ObserverHeartbeatDecision.Allowed(
            ObserverHeartbeatLease.issue(
                receiptId = receipt.receiptId,
                sessionId = receipt.sessionId,
                observerPrincipalId = receipt.observerPrincipalId,
                destinationId = receipt.destinationId,
                issuedAtEpochMillis = evaluatedAtEpochMillis,
                validUntilEpochMillis = heartbeatValidUntil,
            ),
        )
    }
}

class ClinicalRuleApprovalReceipt(
    val receiptId: String,
    val ruleId: String,
    val ruleVersion: String,
    val medicalFeatureId: String,
    val medicalFeatureVersion: String,
    val environmentFingerprintSha256: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val approvedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    private val signatureSnapshot = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        requireIdentifier(receiptId, "receiptId")
        requireIdentifier(ruleId, "ruleId")
        requireIdentifier(ruleVersion, "ruleVersion")
        requireIdentifier(medicalFeatureId, "medicalFeatureId")
        requireIdentifier(medicalFeatureVersion, "medicalFeatureVersion")
        requireSha256(environmentFingerprintSha256, "environmentFingerprintSha256")
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(subjectPseudonym, "subjectPseudonym")
        require(approvedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > approvedAtEpochMillis)
        require(issuerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    fun copy(
        receiptId: String = this.receiptId,
        ruleId: String = this.ruleId,
        ruleVersion: String = this.ruleVersion,
        medicalFeatureId: String = this.medicalFeatureId,
        medicalFeatureVersion: String = this.medicalFeatureVersion,
        environmentFingerprintSha256: String = this.environmentFingerprintSha256,
        sessionId: String = this.sessionId,
        subjectPseudonym: String = this.subjectPseudonym,
        approvedAtEpochMillis: Long = this.approvedAtEpochMillis,
        expiresAtEpochMillis: Long = this.expiresAtEpochMillis,
        issuerKeyId: String = this.issuerKeyId,
        signature: ByteArray = this.signature,
    ) = ClinicalRuleApprovalReceipt(
        receiptId,
        ruleId,
        ruleVersion,
        medicalFeatureId,
        medicalFeatureVersion,
        environmentFingerprintSha256,
        sessionId,
        subjectPseudonym,
        approvedAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
        signature,
    )

    override fun equals(other: Any?): Boolean =
        other is ClinicalRuleApprovalReceipt &&
            receiptId == other.receiptId &&
            ruleId == other.ruleId &&
            ruleVersion == other.ruleVersion &&
            medicalFeatureId == other.medicalFeatureId &&
            medicalFeatureVersion == other.medicalFeatureVersion &&
            environmentFingerprintSha256 == other.environmentFingerprintSha256 &&
            sessionId == other.sessionId &&
            subjectPseudonym == other.subjectPseudonym &&
            approvedAtEpochMillis == other.approvedAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            issuerKeyId == other.issuerKeyId &&
            signatureSnapshot.contentEquals(other.signatureSnapshot)

    override fun hashCode(): Int = 31 * listOf(
        receiptId, ruleId, ruleVersion, medicalFeatureId, medicalFeatureVersion,
        environmentFingerprintSha256, sessionId, subjectPseudonym, approvedAtEpochMillis,
        expiresAtEpochMillis, issuerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()
}

fun interface ClinicalRuleApprovalVerifier {
    fun verify(receipt: ClinicalRuleApprovalReceipt): Boolean
}

class ClinicalRulePermit private constructor(
    val receiptId: String,
    val ruleId: String,
    val ruleVersion: String,
    val medicalFeatureId: String,
    val medicalFeatureVersion: String,
    val medicalSurface: ProductSurface,
    val environmentFingerprintSha256: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    medicalEvidenceReceiptIds: List<String>,
) {
    val medicalEvidenceReceiptIds: List<String> = java.util.List.copyOf(medicalEvidenceReceiptIds)
    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    override fun equals(other: Any?): Boolean = other is ClinicalRulePermit &&
        receiptId == other.receiptId &&
        ruleId == other.ruleId &&
        ruleVersion == other.ruleVersion &&
        medicalFeatureId == other.medicalFeatureId &&
        medicalFeatureVersion == other.medicalFeatureVersion &&
        medicalSurface == other.medicalSurface &&
        environmentFingerprintSha256 == other.environmentFingerprintSha256 &&
        sessionId == other.sessionId &&
        subjectPseudonym == other.subjectPseudonym &&
        issuedAtEpochMillis == other.issuedAtEpochMillis &&
        validUntilEpochMillis == other.validUntilEpochMillis &&
        medicalEvidenceReceiptIds == other.medicalEvidenceReceiptIds

    override fun hashCode(): Int = listOf(
        receiptId,
        ruleId,
        ruleVersion,
        medicalFeatureId,
        medicalFeatureVersion,
        medicalSurface,
        environmentFingerprintSha256,
        sessionId,
        subjectPseudonym,
        issuedAtEpochMillis,
        validUntilEpochMillis,
        medicalEvidenceReceiptIds,
    ).hashCode()

    companion object {
        internal fun issue(
            receiptId: String,
            ruleId: String,
            ruleVersion: String,
            medicalFeatureId: String,
            medicalFeatureVersion: String,
            medicalSurface: ProductSurface,
            environmentFingerprintSha256: String,
            sessionId: String,
            subjectPseudonym: String,
            issuedAtEpochMillis: Long,
            validUntilEpochMillis: Long,
            medicalEvidenceReceiptIds: List<String>,
        ) = ClinicalRulePermit(
            receiptId,
            ruleId,
            ruleVersion,
            medicalFeatureId,
            medicalFeatureVersion,
            medicalSurface,
            environmentFingerprintSha256,
            sessionId,
            subjectPseudonym,
            issuedAtEpochMillis,
            validUntilEpochMillis,
            medicalEvidenceReceiptIds,
        )
    }
}

enum class ClinicalRulePermitDenialReason {
    SIGNATURE_INVALID,
    NOT_YET_ACTIVE,
    EXPIRED,
    MEDICAL_PROMOTION_MISMATCH,
    MEDICAL_PROMOTION_NOT_CURRENT,
}

sealed interface ClinicalRulePermitDecision {
    class Allowed internal constructor(val permit: ClinicalRulePermit) : ClinicalRulePermitDecision
    data class Denied(val reason: ClinicalRulePermitDenialReason) : ClinicalRulePermitDecision
}

class ClinicalRulePermitIssuer(
    private val verifier: ClinicalRuleApprovalVerifier,
    private val maximumPermitLifetimeMillis: Long = DEFAULT_CLINICAL_RULE_PERMIT_LIFETIME_MILLIS,
) {
    init {
        require(maximumPermitLifetimeMillis in 1L..MAXIMUM_CLINICAL_RULE_PERMIT_LIFETIME_MILLIS)
    }

    fun issue(
        receipt: ClinicalRuleApprovalReceipt,
        medicalPromotionPermit: MedicalPromotionPermit,
        evaluatedAtEpochMillis: Long,
    ): ClinicalRulePermitDecision {
        require(evaluatedAtEpochMillis > 0L)
        if (!verifier.verify(receipt)) {
            return ClinicalRulePermitDecision.Denied(ClinicalRulePermitDenialReason.SIGNATURE_INVALID)
        }
        if (evaluatedAtEpochMillis < receipt.approvedAtEpochMillis) {
            return ClinicalRulePermitDecision.Denied(ClinicalRulePermitDenialReason.NOT_YET_ACTIVE)
        }
        if (evaluatedAtEpochMillis >= receipt.expiresAtEpochMillis) {
            return ClinicalRulePermitDecision.Denied(ClinicalRulePermitDenialReason.EXPIRED)
        }
        if (medicalPromotionPermit.surface != ProductSurface.MEDICAL_INTENDED_USE ||
            medicalPromotionPermit.featureId != receipt.medicalFeatureId ||
            medicalPromotionPermit.featureVersion != receipt.medicalFeatureVersion ||
            medicalPromotionPermit.environmentFingerprintSha256 != receipt.environmentFingerprintSha256
        ) {
            return ClinicalRulePermitDecision.Denied(
                ClinicalRulePermitDenialReason.MEDICAL_PROMOTION_MISMATCH,
            )
        }
        if (!medicalPromotionPermit.isCurrentAt(evaluatedAtEpochMillis)) {
            return ClinicalRulePermitDecision.Denied(
                ClinicalRulePermitDenialReason.MEDICAL_PROMOTION_NOT_CURRENT,
            )
        }
        val validUntil = minOf(
            receipt.expiresAtEpochMillis,
            medicalPromotionPermit.validUntilEpochMillis,
            saturatedAdd(evaluatedAtEpochMillis, maximumPermitLifetimeMillis),
        )
        return ClinicalRulePermitDecision.Allowed(
            ClinicalRulePermit.issue(
                receiptId = receipt.receiptId,
                ruleId = receipt.ruleId,
                ruleVersion = receipt.ruleVersion,
                medicalFeatureId = receipt.medicalFeatureId,
                medicalFeatureVersion = receipt.medicalFeatureVersion,
                medicalSurface = medicalPromotionPermit.surface,
                environmentFingerprintSha256 = receipt.environmentFingerprintSha256,
                sessionId = receipt.sessionId,
                subjectPseudonym = receipt.subjectPseudonym,
                issuedAtEpochMillis = evaluatedAtEpochMillis,
                validUntilEpochMillis = validUntil,
                medicalEvidenceReceiptIds = medicalPromotionPermit.evidenceReceiptIds,
            ),
        )
    }
}

internal fun requireIdentifier(value: String, fieldName: String) {
    require(value.matches(IDENTIFIER_REGEX)) {
        "$fieldName must contain 1-96 letters, numbers, dots, underscores or hyphens"
    }
}

internal fun requireSha256(value: String, fieldName: String) {
    require(value.matches(SHA_256_REGEX)) { "$fieldName must be a lowercase SHA-256 digest" }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private val IDENTIFIER_REGEX = Regex("[A-Za-z0-9._-]{1,96}")
private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
private const val DEFAULT_CLINICIAN_PERMIT_LIFETIME_MILLIS = 60_000L
private const val MAXIMUM_CLINICIAN_PERMIT_LIFETIME_MILLIS = 5 * 60_000L
private const val DEFAULT_HEARTBEAT_AGE_MILLIS = 30_000L
private const val MAXIMUM_HEARTBEAT_AGE_MILLIS = 60_000L
private const val DEFAULT_CLINICAL_RULE_PERMIT_LIFETIME_MILLIS = 60_000L
private const val MAXIMUM_CLINICAL_RULE_PERMIT_LIFETIME_MILLIS = 5 * 60_000L
