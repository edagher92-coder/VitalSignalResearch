package au.com.elied.vitalsignal.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotAccessGateTest {
    private val gate = PilotAccessGate(
        consentVerifier = ConsentGrantVerifier { it.signature.contentEquals(byteArrayOf(1)) },
        validationVerifier = ValidationReceiptVerifier { it.signature.contentEquals(byteArrayOf(2)) },
    )

    @Test
    fun exactConsentAndValidationEnvironmentAllowsCapability() {
        val result = gate.evaluate(request(), consent(), listOf(receipt()))

        assertTrue(result.allowed)
        assertEquals(PilotGateReason.ALLOWED, result.reason)
        assertEquals("validation-1", result.validationReceiptId)
        assertTrue(result.consentGrantSha256!!.matches(Regex("[a-f0-9]{64}")))
        assertTrue(result.validationReceiptSha256!!.matches(Regex("[a-f0-9]{64}")))
        assertTrue(
            result.authorizes(
                PilotCapability.PERSONAL_INTERPRETATION,
                "pilot-1",
                1L,
                1_500L,
            ),
        )
    }

    @Test
    fun pauseOverridesOtherwiseValidReceipts() {
        val result = gate.evaluate(
            request().copy(collectionPaused = true),
            consent(),
            listOf(receipt()),
        )

        assertFalse(result.allowed)
        assertEquals(PilotGateReason.PAUSED, result.reason)
    }

    @Test
    fun recoveryRequiredOverridesOtherwiseValidReceipts() {
        val result = gate.evaluate(
            request().copy(recoveryRequired = true),
            consent(),
            listOf(receipt()),
        )

        assertEquals(PilotGateReason.RECOVERY_REQUIRED, result.reason)
    }

    @Test
    fun oldConsentGenerationCannotAuthorizeNewData() {
        val result = gate.evaluate(
            request().copy(consentGeneration = 2L),
            consent(),
            listOf(receipt()),
        )

        assertEquals(PilotGateReason.CONSENT_GENERATION_MISMATCH, result.reason)
    }

    @Test
    fun subjectMismatchFailsClosed() {
        val result = gate.evaluate(
            request().copy(subjectPseudonym = "another-person"),
            consent(),
            listOf(receipt()),
        )

        assertEquals(PilotGateReason.SUBJECT_MISMATCH, result.reason)
    }

    @Test
    fun missingScopeCannotBeReplacedByAndroidPermission() {
        val changed = consent().copy(scopes = setOf(ConsentScope.PASSIVE_WATCH_DATA))

        val result = gate.evaluate(request(), changed, listOf(receipt()))

        assertEquals(PilotGateReason.CONSENT_SCOPE_MISSING, result.reason)
    }

    @Test
    fun invalidConsentSignatureFailsClosed() {
        val result = gate.evaluate(
            request(),
            consent().copy(signature = byteArrayOf(9)),
            listOf(receipt()),
        )

        assertEquals(PilotGateReason.CONSENT_SIGNATURE_INVALID, result.reason)
    }

    @Test
    fun expiredConsentFailsClosed() {
        val result = gate.evaluate(
            request().copy(evaluatedAtEpochMillis = 2_000L),
            consent().copy(expiresAtEpochMillis = 2_000L),
            listOf(receipt().copy(expiresAtEpochMillis = 3_000L)),
        )

        assertEquals(PilotGateReason.CONSENT_EXPIRED, result.reason)
    }

    @Test
    fun missingValidationReceiptKeepsRealCapabilityLocked() {
        val result = gate.evaluate(request(), consent(), emptyList())

        assertEquals(PilotGateReason.VALIDATION_RECEIPT_MISSING, result.reason)
    }

    @Test
    fun firmwareChangeRequiresARevalidatedReceipt() {
        val result = gate.evaluate(
            request().copy(firmwareGeneration = "new-firmware"),
            consent(),
            listOf(receipt()),
        )

        assertEquals(PilotGateReason.VALIDATION_ENVIRONMENT_MISMATCH, result.reason)
    }

    @Test
    fun expiredValidationReceiptFailsClosed() {
        val result = gate.evaluate(
            request().copy(evaluatedAtEpochMillis = 5_000L),
            consent().copy(expiresAtEpochMillis = 6_000L),
            listOf(receipt().copy(expiresAtEpochMillis = 5_000L)),
        )

        assertEquals(PilotGateReason.VALIDATION_RECEIPT_EXPIRED, result.reason)
    }

    @Test
    fun invalidValidationSignatureFailsClosed() {
        val result = gate.evaluate(
            request(),
            consent(),
            listOf(receipt().copy(signature = byteArrayOf(7))),
        )

        assertEquals(PilotGateReason.VALIDATION_SIGNATURE_INVALID, result.reason)
    }

    @Test
    fun futureDatedValidationReceiptFailsClosedEvenWhenSignedAndEnvironmentMatched() {
        val result = gate.evaluate(
            request(),
            consent(),
            listOf(
                receipt().copy(
                    issuedAtEpochMillis = 1_600L,
                    expiresAtEpochMillis = 10_000L,
                ),
            ),
        )

        assertFalse(result.allowed)
        assertEquals(PilotGateReason.VALIDATION_RECEIPT_NOT_YET_ACTIVE, result.reason)
    }

    @Test
    fun gateIssuedEvidenceIsExactAndCannotReplayAcrossBindingsOrLifetime() {
        val result = gate.evaluate(request(), consent(), listOf(receipt()))

        assertFalse(result.authorizes(PilotCapability.LOCAL_REASONING, "pilot-1", 1L, 1_500L))
        assertFalse(
            result.authorizes(
                PilotCapability.PERSONAL_INTERPRETATION,
                "pilot-2",
                1L,
                1_500L,
            ),
        )
        assertFalse(
            result.authorizes(
                PilotCapability.PERSONAL_INTERPRETATION,
                "pilot-1",
                2L,
                1_500L,
            ),
        )
        assertFalse(
            result.authorizes(
                PilotCapability.PERSONAL_INTERPRETATION,
                "pilot-1",
                1L,
                61_500L,
            ),
        )
    }

    private fun request() = PilotGateRequest(
        capability = PilotCapability.PERSONAL_INTERPRETATION,
        subjectPseudonym = "pilot-1",
        consentGeneration = 1L,
            appVersion = "0.6.0-research",
        deviceModel = "fixture-ultra2",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "health-v1",
        evaluatedAtEpochMillis = 1_500L,
        collectionPaused = false,
        recoveryRequired = false,
    )

    private fun consent() = ConsentGrant(
        subjectPseudonym = "pilot-1",
        generation = 1L,
        scopes = setOf(ConsentScope.PERSONAL_INSIGHTS),
        issuedAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 10_000L,
        consentTextSha256 = "a".repeat(64),
        signerKeyId = "fixture-consent-key",
        signature = byteArrayOf(1),
    )

    private fun receipt() = ValidationReceipt(
        receiptId = "validation-1",
        capability = PilotCapability.PERSONAL_INTERPRETATION,
                appVersion = "0.6.0-research",
        deviceModel = "fixture-ultra2",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "health-v1",
        issuedAtEpochMillis = 1_100L,
        expiresAtEpochMillis = 10_000L,
        evidenceIds = listOf("p0-fixture", "reference-fixture"),
        evidenceBundleSha256 = "b".repeat(64),
        issuerKeyId = "fixture-validation-key",
        signature = byteArrayOf(2),
    )
}
