package au.com.elied.vitalsignal.wear.governance

import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateDecision
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesConsent
import au.com.elied.vitalsignal.wear.sensor.CollectionMode
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import au.com.elied.vitalsignal.wear.transport.WatchConsentFence

/** Only an allowed core governance decision can mint an activation lease. */
class GovernedWatchAccessLease private constructor(
    val capability: PilotCapability,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val validationReceiptId: String,
    val governanceConsentGrantSha256: String,
    val governanceValidationReceiptSha256: String,
    val evaluatedAtEpochMillis: Long,
) {
    fun outboxFence(transferAllowed: Boolean = true): WatchConsentFence = WatchConsentFence(
        generation = consentGeneration,
        installedAtEpochMillis = evaluatedAtEpochMillis,
        transferAllowed = transferAllowed,
    )

    fun passiveCollectionConsent(channels: Set<WatchDataChannel>): WearHealthServicesConsent {
        require(capability == PilotCapability.WATCH_PASSIVE_COLLECTION)
        require(channels.isNotEmpty() && channels.all { it.mode == CollectionMode.PASSIVE })
        return WearHealthServicesConsent(
            generation = consentGeneration,
            grantedAtEpochMillis = evaluatedAtEpochMillis,
            allowedChannels = channels,
            collectionAllowed = true,
        )
    }

    companion object {
        /** Only a fresh, opaque core-gate decision can cross this construction path. */
        fun evaluateGoverned(
            pilotAccessGate: PilotAccessGate,
            request: PilotGateRequest,
            consent: ConsentGrant,
            validationReceipts: List<ValidationReceipt>,
        ): GovernedWatchActivationResult {
            require(
                request.capability == PilotCapability.WATCH_PASSIVE_COLLECTION ||
                    request.capability == PilotCapability.WATCH_RESEARCH_CAPTURE,
            ) { "Only watch capabilities may pass through the wear activation gate" }
            val decision = pilotAccessGate.evaluate(request, consent, validationReceipts)
            if (!decision.authorizes(
                    capability = request.capability,
                    subjectPseudonym = request.subjectPseudonym,
                    consentGeneration = request.consentGeneration,
                    atEpochMillis = request.evaluatedAtEpochMillis,
                )
            ) {
                return GovernedWatchActivationResult.Denied(decision)
            }
            return GovernedWatchActivationResult.Allowed(
                lease = GovernedWatchAccessLease(
                    capability = request.capability,
                    subjectPseudonym = request.subjectPseudonym,
                    consentGeneration = decision.consentGeneration,
                    validationReceiptId = requireNotNull(decision.validationReceiptId),
                    governanceConsentGrantSha256 = requireNotNull(decision.consentGrantSha256),
                    governanceValidationReceiptSha256 = requireNotNull(
                        decision.validationReceiptSha256,
                    ),
                    evaluatedAtEpochMillis = request.evaluatedAtEpochMillis,
                ),
                decision = decision,
            )
        }
    }
}

sealed interface GovernedWatchActivationResult {
    data class Allowed(
        val lease: GovernedWatchAccessLease,
        val decision: PilotGateDecision,
    ) : GovernedWatchActivationResult

    data class Denied(val decision: PilotGateDecision) : GovernedWatchActivationResult
}

/**
 * Wear-facing adapter for the central signed consent + exact-environment validation gate. It does
 * not interpret receipts itself and cannot create a lease from a denied decision.
 */
class WearPilotActivationGate(
    private val pilotAccessGate: PilotAccessGate,
) {
    fun evaluate(
        request: PilotGateRequest,
        consent: ConsentGrant,
        validationReceipts: List<ValidationReceipt>,
    ): GovernedWatchActivationResult {
        return GovernedWatchAccessLease.evaluateGoverned(
            pilotAccessGate = pilotAccessGate,
            request = request,
            consent = consent,
            validationReceipts = validationReceipts,
        )
    }
}
