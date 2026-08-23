package au.com.elied.vitalsignal.phone.data.integration

import au.com.elied.vitalsignal.governance.PilotGateDecision
import au.com.elied.vitalsignal.governance.PilotGateReason
import au.com.elied.vitalsignal.governance.PilotCapability

enum class HistoryDataScope {
    VITALS,
    HEART_RATE,
    SLEEP,
    BLOOD_OXYGEN,
    SKIN_TEMPERATURE,
    ACTIVITY_AND_EXERCISE,
    BODY_COMPOSITION,
    MEDICATIONS,
    CONDITIONS,
    LABORATORY_RESULTS,
    PROCEDURES_AND_ENCOUNTERS,
    IMMUNIZATIONS,
}

class HistoryConsentGrant(
    val consentId: String,
    val generation: Long,
    val participantPseudonym: String,
    val protocolId: String,
    allowedSources: Set<HistorySourceKind>,
    allowedScopes: Set<HistoryDataScope>,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
) {
    /** Immutable scope snapshots prevent post-consent capability escalation. */
    val allowedSources: Set<HistorySourceKind> = java.util.Set.copyOf(allowedSources)
    val allowedScopes: Set<HistoryDataScope> = java.util.Set.copyOf(allowedScopes)

    init {
        require(consentId.isNotBlank())
        require(generation > 0L)
        require(participantPseudonym.isNotBlank())
        require(protocolId.isNotBlank())
        require(this.allowedSources.isNotEmpty())
        require(this.allowedScopes.isNotEmpty())
        require(validFromEpochMillis < validUntilEpochMillis)
        revokedAtEpochMillis?.let { require(it >= validFromEpochMillis) }
    }

    fun permits(
        source: HistorySourceKind,
        scopes: Set<HistoryDataScope>,
        atEpochMillis: Long,
    ): Boolean = source in allowedSources &&
        scopes.isNotEmpty() && allowedScopes.containsAll(scopes) &&
        atEpochMillis in validFromEpochMillis until validUntilEpochMillis &&
        (revokedAtEpochMillis == null || atEpochMillis < revokedAtEpochMillis)

    fun copy(
        consentId: String = this.consentId,
        generation: Long = this.generation,
        participantPseudonym: String = this.participantPseudonym,
        protocolId: String = this.protocolId,
        allowedSources: Set<HistorySourceKind> = this.allowedSources,
        allowedScopes: Set<HistoryDataScope> = this.allowedScopes,
        validFromEpochMillis: Long = this.validFromEpochMillis,
        validUntilEpochMillis: Long = this.validUntilEpochMillis,
        revokedAtEpochMillis: Long? = this.revokedAtEpochMillis,
    ) = HistoryConsentGrant(
        consentId,
        generation,
        participantPseudonym,
        protocolId,
        allowedSources,
        allowedScopes,
        validFromEpochMillis,
        validUntilEpochMillis,
        revokedAtEpochMillis,
    )

    override fun equals(other: Any?): Boolean = other is HistoryConsentGrant &&
        consentId == other.consentId && generation == other.generation &&
        participantPseudonym == other.participantPseudonym && protocolId == other.protocolId &&
        allowedSources == other.allowedSources && allowedScopes == other.allowedScopes &&
        validFromEpochMillis == other.validFromEpochMillis &&
        validUntilEpochMillis == other.validUntilEpochMillis &&
        revokedAtEpochMillis == other.revokedAtEpochMillis

    override fun hashCode(): Int = listOf(
        consentId,
        generation,
        participantPseudonym,
        protocolId,
        allowedSources,
        allowedScopes,
        validFromEpochMillis,
        validUntilEpochMillis,
        revokedAtEpochMillis,
    ).hashCode()
}

enum class HistoryPermissionState {
    GRANTED,
    DENIED,
    NOT_REQUESTED,
}

data class HistoryRuntimeCapability(
    val source: HistorySourceKind,
    val adapterInstalled: Boolean,
    val sourceAvailable: Boolean,
    val readPermission: HistoryPermissionState,
    val detail: String? = null,
)

class HistoryReadRequest(
    val source: HistorySourceKind,
    scopes: Set<HistoryDataScope>,
) {
    val scopes: Set<HistoryDataScope> = java.util.Set.copyOf(scopes)

    init {
        require(this.scopes.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean = other is HistoryReadRequest &&
        source == other.source && scopes == other.scopes

    override fun hashCode(): Int = 31 * source.hashCode() + scopes.hashCode()
}

data class HistoryPilotGateContext(
    val pilotFeatureEnabled: Boolean,
    /** Opaque result issued by core:governance for the exact requested source capability. */
    val governanceDecision: PilotGateDecision,
    val consent: HistoryConsentGrant?,
    val capability: HistoryRuntimeCapability,
    val nowEpochMillis: Long,
)

enum class HistoryReadBlockReason {
    PILOT_DISABLED,
    CENTRAL_GOVERNANCE_DENIED,
    CENTRAL_CAPABILITY_MISMATCH,
    CENTRAL_SUBJECT_MISMATCH,
    CENTRAL_EVIDENCE_EXPIRED,
    CONSENT_GENERATION_MISMATCH,
    VALIDATION_RECEIPT_MISSING,
    CONSENT_MISSING_OR_OUT_OF_SCOPE,
    ADAPTER_NOT_INSTALLED,
    SOURCE_UNAVAILABLE,
    READ_PERMISSION_NOT_GRANTED,
    CAPABILITY_SOURCE_MISMATCH,
}

class HistoryReadPermit private constructor(
    val source: HistorySourceKind,
    scopes: Set<HistoryDataScope>,
    val participantPseudonym: String,
    val consentId: String,
    val consentGeneration: Long,
    val protocolId: String,
    val validationReceiptId: String,
    val governanceConsentGrantSha256: String,
    val governanceValidationReceiptSha256: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    val scopes: Set<HistoryDataScope> = java.util.Set.copyOf(scopes)

    init {
        require(this.scopes.isNotEmpty())
        require(participantPseudonym.isNotBlank())
        require(consentId.isNotBlank())
        require(consentGeneration > 0L)
        require(protocolId.isNotBlank())
        require(validationReceiptId.isNotBlank())
        require(governanceConsentGrantSha256.matches(Regex("[a-f0-9]{64}")))
        require(governanceValidationReceiptSha256.matches(Regex("[a-f0-9]{64}")))
        require(expiresAtEpochMillis > issuedAtEpochMillis)
    }

    fun isValidAt(epochMillis: Long): Boolean =
        epochMillis in issuedAtEpochMillis until expiresAtEpochMillis

    companion object {
        /** The only construction path; it repeats every fail-closed check before construction. */
        fun evaluateGoverned(
            request: HistoryReadRequest,
            context: HistoryPilotGateContext,
        ): HistoryPilotGateDecision {
            val decision = context.governanceDecision
            val expectedCapability = request.source.requiredPilotCapability()
            val consent = context.consent
            val reasons = buildSet {
                if (!context.pilotFeatureEnabled) add(HistoryReadBlockReason.PILOT_DISABLED)
                if (!decision.allowed || decision.reason != PilotGateReason.ALLOWED) {
                    add(HistoryReadBlockReason.CENTRAL_GOVERNANCE_DENIED)
                }
                if (decision.capability != expectedCapability) {
                    add(HistoryReadBlockReason.CENTRAL_CAPABILITY_MISMATCH)
                }
                if (consent != null && decision.subjectPseudonym != consent.participantPseudonym) {
                    add(HistoryReadBlockReason.CENTRAL_SUBJECT_MISMATCH)
                }
                if (consent != null && decision.consentGeneration != consent.generation) {
                    add(HistoryReadBlockReason.CONSENT_GENERATION_MISMATCH)
                }
                if (decision.validationReceiptId.isNullOrBlank() ||
                    decision.consentGrantSha256.isNullOrBlank() ||
                    decision.validationReceiptSha256.isNullOrBlank()
                ) {
                    add(HistoryReadBlockReason.VALIDATION_RECEIPT_MISSING)
                }
                if (decision.allowed &&
                    decision.authorizationExpiresAtEpochMillis?.let {
                        context.nowEpochMillis !in decision.evaluatedAtEpochMillis until it
                    } != false
                ) {
                    add(HistoryReadBlockReason.CENTRAL_EVIDENCE_EXPIRED)
                }
                if (context.capability.source != request.source) {
                    add(HistoryReadBlockReason.CAPABILITY_SOURCE_MISMATCH)
                }
                if (!context.capability.adapterInstalled) {
                    add(HistoryReadBlockReason.ADAPTER_NOT_INSTALLED)
                }
                if (!context.capability.sourceAvailable) {
                    add(HistoryReadBlockReason.SOURCE_UNAVAILABLE)
                }
                if (context.capability.readPermission != HistoryPermissionState.GRANTED) {
                    add(HistoryReadBlockReason.READ_PERMISSION_NOT_GRANTED)
                }
                if (consent?.permits(request.source, request.scopes, context.nowEpochMillis) != true) {
                    add(HistoryReadBlockReason.CONSENT_MISSING_OR_OUT_OF_SCOPE)
                }
                if (consent != null && !decision.authorizes(
                        capability = expectedCapability,
                        subjectPseudonym = consent.participantPseudonym,
                        consentGeneration = consent.generation,
                        atEpochMillis = context.nowEpochMillis,
                    )
                ) {
                    add(HistoryReadBlockReason.CENTRAL_GOVERNANCE_DENIED)
                }
            }
            if (reasons.isNotEmpty()) return HistoryPilotGateDecision.Blocked(reasons)

            val exactConsent = requireNotNull(consent)
            return HistoryPilotGateDecision.Allowed(
                HistoryReadPermit(
                    source = request.source,
                    scopes = request.scopes,
                    participantPseudonym = exactConsent.participantPseudonym,
                    consentId = exactConsent.consentId,
                    consentGeneration = exactConsent.generation,
                    protocolId = exactConsent.protocolId,
                    validationReceiptId = requireNotNull(decision.validationReceiptId),
                    governanceConsentGrantSha256 = requireNotNull(decision.consentGrantSha256),
                    governanceValidationReceiptSha256 = requireNotNull(
                        decision.validationReceiptSha256,
                    ),
                    issuedAtEpochMillis = context.nowEpochMillis,
                    expiresAtEpochMillis = minOf(
                        exactConsent.validUntilEpochMillis,
                        requireNotNull(decision.authorizationExpiresAtEpochMillis),
                        context.nowEpochMillis + maximumPermitLifetimeMillis,
                    ),
                ),
            )
        }

        private const val maximumPermitLifetimeMillis = 15 * 60 * 1_000L
    }
}

sealed interface HistoryPilotGateDecision {
    data class Allowed(val permit: HistoryReadPermit) : HistoryPilotGateDecision

    class Blocked(reasons: Set<HistoryReadBlockReason>) : HistoryPilotGateDecision {
        val reasons: Set<HistoryReadBlockReason> = java.util.Set.copyOf(reasons)

        init {
            require(this.reasons.isNotEmpty())
        }

        override fun equals(other: Any?): Boolean =
            other is Blocked && reasons == other.reasons

        override fun hashCode(): Int = reasons.hashCode()

        override fun toString(): String = "Blocked(reasons=$reasons)"
    }
}

/** Consent, runtime capability, permission, and pilot-only gate for every real history read. */
object HistoryPilotGate {
    fun evaluate(
        request: HistoryReadRequest,
        context: HistoryPilotGateContext,
    ): HistoryPilotGateDecision {
        return HistoryReadPermit.evaluateGoverned(request, context)
    }
}

internal fun HistorySourceKind.requiredPilotCapability(): PilotCapability = when (this) {
    HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK -> PilotCapability.PHONE_SAMSUNG_HEALTH_HISTORY
    HistorySourceKind.HEALTH_CONNECT -> PilotCapability.PHONE_HEALTH_CONNECT_HISTORY
    HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR ->
        PilotCapability.PHONE_FHIR_MEDICAL_RECORDS
}
