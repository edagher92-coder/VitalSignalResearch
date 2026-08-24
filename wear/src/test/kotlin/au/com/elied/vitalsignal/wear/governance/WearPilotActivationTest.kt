package au.com.elied.vitalsignal.wear.governance

import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.GovernanceReceiptPurpose
import au.com.elied.vitalsignal.governance.HmacGovernanceAuthority
import au.com.elied.vitalsignal.governance.HmacGovernanceVerifier
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateReason
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPilotActivationTest {
    @Test
    fun signedConsentAndExactValidationMintGenerationMatchedWearLeases() {
        val consent = authority(GovernanceReceiptPurpose.CONSENT).issueConsent(
            subjectPseudonym = "pilot-1",
            generation = 9L,
            scopes = setOf(ConsentScope.PASSIVE_WATCH_DATA),
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 100_000L,
            consentTextSha256 = "a".repeat(64),
        )
        val validation = authority(GovernanceReceiptPurpose.VALIDATION).issueValidation(
            receiptId = "validation-watch-passive-1",
            capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
            appVersion = "0.6.0-research",
            deviceModel = "Galaxy Watch Ultra2",
            firmwareGeneration = "ultra2-fw-1",
            dataSchemaVersion = "watch-passive-v1",
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 100_000L,
            evidenceIds = listOf("fixture-pure-kotlin-tests", "fixture-capability-check"),
            evidenceBundleSha256 = "b".repeat(64),
        )
        val result = gate().evaluate(request(), consent, listOf(validation)) as
            GovernedWatchActivationResult.Allowed

        assertEquals(9L, result.lease.consentGeneration)
        assertEquals("validation-watch-passive-1", result.lease.validationReceiptId)
        assertTrue(result.lease.governanceConsentGrantSha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(result.lease.governanceValidationReceiptSha256.matches(Regex("[a-f0-9]{64}")))
        assertEquals(9L, result.lease.outboxFence().generation)
        val collection = result.lease.passiveCollectionConsent(
            setOf(WatchDataChannel.PASSIVE_HEART_RATE),
        )
        assertEquals(9L, collection.generation)
        assertTrue(collection.collectionAllowed)
    }

    @Test
    fun missingValidationCannotMintWearLease() {
        val consent = authority(GovernanceReceiptPurpose.CONSENT).issueConsent(
            subjectPseudonym = "pilot-1",
            generation = 9L,
            scopes = setOf(ConsentScope.PASSIVE_WATCH_DATA),
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 100_000L,
            consentTextSha256 = "a".repeat(64),
        )

        val denied = gate().evaluate(request(), consent, emptyList()) as
            GovernedWatchActivationResult.Denied

        assertEquals(PilotGateReason.VALIDATION_RECEIPT_MISSING, denied.decision.reason)
    }

    private fun gate(): WearPilotActivationGate {
        val verifier = HmacGovernanceVerifier { purpose, id ->
            if (id == keyId(purpose)) key(purpose) else null
        }
        return WearPilotActivationGate(PilotAccessGate(verifier, verifier))
    }

    private fun authority(purpose: GovernanceReceiptPurpose) = HmacGovernanceAuthority(
        keyId(purpose),
        purpose,
        key(purpose),
    )

    private fun keyId(purpose: GovernanceReceiptPurpose) =
        "governance-${purpose.name.lowercase()}-key-1"

    private fun key(purpose: GovernanceReceiptPurpose) =
        ByteArray(32) { index -> (index + 13 + purpose.ordinal * 5).toByte() }

    private fun request() = PilotGateRequest(
        capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
        subjectPseudonym = "pilot-1",
        consentGeneration = 9L,
        appVersion = "0.6.0-research",
        deviceModel = "Galaxy Watch Ultra2",
        firmwareGeneration = "ultra2-fw-1",
        dataSchemaVersion = "watch-passive-v1",
        evaluatedAtEpochMillis = 2_000L,
        collectionPaused = false,
        recoveryRequired = false,
    )

}
