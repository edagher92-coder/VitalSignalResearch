package au.com.elied.vitalsignal.wear.governance

import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.ConsentGrantVerifier
import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.governance.ValidationReceiptVerifier

/** Test fixture deliberately crosses the real gate; it does not expose a lease constructor. */
fun governedWatchLeaseFixture(
    capability: PilotCapability = PilotCapability.WATCH_PASSIVE_COLLECTION,
    subjectPseudonym: String = "pilot-1",
    consentGeneration: Long = 1L,
    evaluatedAtEpochMillis: Long = 2_000L,
): GovernedWatchAccessLease {
    val consentSignature = byteArrayOf(1)
    val validationSignature = byteArrayOf(2)
    val gate = WearPilotActivationGate(
        PilotAccessGate(
            ConsentGrantVerifier { it.signature.contentEquals(consentSignature) },
            ValidationReceiptVerifier { it.signature.contentEquals(validationSignature) },
        ),
    )
    val scope = when (capability) {
        PilotCapability.WATCH_PASSIVE_COLLECTION -> ConsentScope.PASSIVE_WATCH_DATA
        PilotCapability.WATCH_RESEARCH_CAPTURE -> ConsentScope.RAW_RESEARCH_SIGNALS
        else -> error("Watch fixture only supports watch capabilities")
    }
    val request = PilotGateRequest(
        capability = capability,
        subjectPseudonym = subjectPseudonym,
        consentGeneration = consentGeneration,
        appVersion = "0.5.0-research",
        deviceModel = "Galaxy Watch Ultra2 fixture",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "watch-fixture-v1",
        evaluatedAtEpochMillis = evaluatedAtEpochMillis,
        collectionPaused = false,
        recoveryRequired = false,
    )
    val consent = ConsentGrant(
        subjectPseudonym = subjectPseudonym,
        generation = consentGeneration,
        scopes = setOf(scope),
        issuedAtEpochMillis = 1L,
        expiresAtEpochMillis = evaluatedAtEpochMillis + 120_000L,
        consentTextSha256 = "a".repeat(64),
        signerKeyId = "fixture-consent-key",
        signature = consentSignature,
    )
    val receipt = ValidationReceipt(
        receiptId = "validation-${capability.name.lowercase()}-$consentGeneration",
        capability = capability,
        appVersion = request.appVersion,
        deviceModel = request.deviceModel,
        firmwareGeneration = request.firmwareGeneration,
        dataSchemaVersion = request.dataSchemaVersion,
        issuedAtEpochMillis = 1L,
        expiresAtEpochMillis = evaluatedAtEpochMillis + 120_000L,
        evidenceIds = listOf("fixture-gated-lease"),
        evidenceBundleSha256 = "b".repeat(64),
        issuerKeyId = "fixture-validation-key",
        signature = validationSignature,
    )
    return (gate.evaluate(request, consent, listOf(receipt)) as
        GovernedWatchActivationResult.Allowed).lease
}
