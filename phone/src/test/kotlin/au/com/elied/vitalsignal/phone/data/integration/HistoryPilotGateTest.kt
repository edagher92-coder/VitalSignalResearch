package au.com.elied.vitalsignal.phone.data.integration

import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.ConsentGrantVerifier
import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.governance.ValidationReceiptVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPilotGateTest {
    @Test
    fun exactCentralGateConsentCapabilityAndPermissionIssuePermit() {
        val decision = HistoryPilotGate.evaluate(request(), context())

        assertTrue(decision is HistoryPilotGateDecision.Allowed)
        val permit = (decision as HistoryPilotGateDecision.Allowed).permit
        assertEquals(4L, permit.consentGeneration)
        assertEquals("validation-fhir-1", permit.validationReceiptId)
        assertEquals(HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR, permit.source)
        assertTrue(permit.governanceConsentGrantSha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(permit.governanceValidationReceiptSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun platformPermissionCannotReplaceCentralValidationReceipt() {
        val decision = HistoryPilotGate.evaluate(
            request(),
            context().copy(
                governanceDecision = governanceDecision(collectionPaused = true),
            ),
        ) as HistoryPilotGateDecision.Blocked

        assertTrue(HistoryReadBlockReason.CENTRAL_GOVERNANCE_DENIED in decision.reasons)
    }

    @Test
    fun staleConsentGenerationIsBlockedEvenWhenAllPermissionsAreGranted() {
        val decision = HistoryPilotGate.evaluate(
            request(),
            context().copy(
                governanceDecision = governanceDecision(consentGeneration = 3L),
            ),
        ) as HistoryPilotGateDecision.Blocked

        assertTrue(HistoryReadBlockReason.CONSENT_GENERATION_MISMATCH in decision.reasons)
    }

    @Test
    fun allowedEvidenceCannotBeReplayedAcrossCapabilitySubjectOrLifetime() {
        val wrongCapability = HistoryPilotGate.evaluate(
            request(),
            context().copy(
                governanceDecision = governanceDecision(
                    capability = PilotCapability.PHONE_HEALTH_CONNECT_HISTORY,
                ),
            ),
        ) as HistoryPilotGateDecision.Blocked
        assertTrue(HistoryReadBlockReason.CENTRAL_CAPABILITY_MISMATCH in wrongCapability.reasons)

        val wrongSubject = HistoryPilotGate.evaluate(
            request(),
            context().copy(
                governanceDecision = governanceDecision(subjectPseudonym = "participant-2"),
            ),
        ) as HistoryPilotGateDecision.Blocked
        assertTrue(HistoryReadBlockReason.CENTRAL_SUBJECT_MISMATCH in wrongSubject.reasons)

        val expired = HistoryPilotGate.evaluate(
            request(),
            context().copy(nowEpochMillis = 70_001L),
        ) as HistoryPilotGateDecision.Blocked
        assertTrue(HistoryReadBlockReason.CENTRAL_EVIDENCE_EXPIRED in expired.reasons)
    }

    @Test
    fun sourceAndScopeMustBothBeExplicitlyConsented() {
        val decision = HistoryPilotGate.evaluate(
            request(),
            context().copy(
                consent = consent().copy(allowedScopes = setOf(HistoryDataScope.HEART_RATE)),
            ),
        ) as HistoryPilotGateDecision.Blocked

        assertTrue(HistoryReadBlockReason.CONSENT_MISSING_OR_OUT_OF_SCOPE in decision.reasons)
    }

    @Test
    fun callerCollectionsCannotEscalateConsentRequestOrIssuedPermit() {
        val mutableSources = mutableSetOf(HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR)
        val mutableConsentScopes = mutableSetOf(HistoryDataScope.LABORATORY_RESULTS)
        val mutableRequestScopes = mutableSetOf(HistoryDataScope.LABORATORY_RESULTS)
        val grant = HistoryConsentGrant(
            consentId = "consent-history-4",
            generation = 4L,
            participantPseudonym = "participant-1",
            protocolId = "pilot-protocol-1",
            allowedSources = mutableSources,
            allowedScopes = mutableConsentScopes,
            validFromEpochMillis = 1_000L,
            validUntilEpochMillis = 100_000L,
        )
        val readRequest = HistoryReadRequest(
            HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
            mutableRequestScopes,
        )
        val decision = HistoryPilotGate.evaluate(
            readRequest,
            context().copy(consent = grant),
        ) as HistoryPilotGateDecision.Allowed

        mutableSources += HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK
        mutableConsentScopes += HistoryDataScope.SLEEP
        mutableRequestScopes += HistoryDataScope.SLEEP

        assertEquals(
            setOf(HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR),
            grant.allowedSources,
        )
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), grant.allowedScopes)
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), readRequest.scopes)
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), decision.permit.scopes)

        assertThrows(UnsupportedOperationException::class.java) {
            (grant.allowedSources as MutableSet<HistorySourceKind>) +=
                HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (grant.allowedScopes as MutableSet<HistoryDataScope>) += HistoryDataScope.SLEEP
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (readRequest.scopes as MutableSet<HistoryDataScope>) += HistoryDataScope.SLEEP
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (decision.permit.scopes as MutableSet<HistoryDataScope>) += HistoryDataScope.SLEEP
        }

        assertEquals(
            setOf(HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR),
            grant.allowedSources,
        )
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), grant.allowedScopes)
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), readRequest.scopes)
        assertEquals(setOf(HistoryDataScope.LABORATORY_RESULTS), decision.permit.scopes)
    }

    @Test
    fun blockedReasonsAreAnImmutableSnapshotEvenAfterDowncast() {
        val callerReasons = mutableSetOf(HistoryReadBlockReason.PILOT_DISABLED)
        val blocked = HistoryPilotGateDecision.Blocked(callerReasons)

        callerReasons += HistoryReadBlockReason.SOURCE_UNAVAILABLE
        assertEquals(setOf(HistoryReadBlockReason.PILOT_DISABLED), blocked.reasons)

        assertThrows(UnsupportedOperationException::class.java) {
            (blocked.reasons as MutableSet<HistoryReadBlockReason>) +=
                HistoryReadBlockReason.SOURCE_UNAVAILABLE
        }
        assertEquals(setOf(HistoryReadBlockReason.PILOT_DISABLED), blocked.reasons)
    }

    private fun request() = HistoryReadRequest(
        source = HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
        scopes = setOf(HistoryDataScope.LABORATORY_RESULTS),
    )

    private fun context() = HistoryPilotGateContext(
        pilotFeatureEnabled = true,
        governanceDecision = governanceDecision(),
        consent = consent(),
        capability = HistoryRuntimeCapability(
            source = HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
            adapterInstalled = true,
            sourceAvailable = true,
            readPermission = HistoryPermissionState.GRANTED,
        ),
        nowEpochMillis = 10_000L,
    )

    private fun consent() = HistoryConsentGrant(
        consentId = "consent-history-4",
        generation = 4L,
        participantPseudonym = "participant-1",
        protocolId = "pilot-protocol-1",
        allowedSources = setOf(HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR),
        allowedScopes = setOf(HistoryDataScope.LABORATORY_RESULTS),
        validFromEpochMillis = 1_000L,
        validUntilEpochMillis = 100_000L,
    )

    private fun governanceDecision(
        capability: PilotCapability = PilotCapability.PHONE_FHIR_MEDICAL_RECORDS,
        consentGeneration: Long = 4L,
        subjectPseudonym: String = "participant-1",
        collectionPaused: Boolean = false,
    ) = PilotAccessGate(
        consentVerifier = ConsentGrantVerifier { it.signature.contentEquals(byteArrayOf(1)) },
        validationVerifier = ValidationReceiptVerifier { it.signature.contentEquals(byteArrayOf(2)) },
    ).evaluate(
        request = PilotGateRequest(
            capability = capability,
            subjectPseudonym = subjectPseudonym,
            consentGeneration = consentGeneration,
            appVersion = "0.5.0-research",
            deviceModel = "Galaxy S25 Ultra fixture",
            firmwareGeneration = "fixture-fw-1",
            dataSchemaVersion = "history-v1",
            evaluatedAtEpochMillis = 10_000L,
            collectionPaused = collectionPaused,
            recoveryRequired = false,
        ),
        consent = ConsentGrant(
            subjectPseudonym = subjectPseudonym,
            generation = consentGeneration,
            scopes = setOf(
                when (capability) {
                    PilotCapability.PHONE_FHIR_MEDICAL_RECORDS -> ConsentScope.MEDICAL_RECORDS
                    else -> ConsentScope.HEALTH_CONNECT_HISTORY
                },
            ),
            issuedAtEpochMillis = 1_000L,
            expiresAtEpochMillis = 100_000L,
            consentTextSha256 = "a".repeat(64),
            signerKeyId = "fixture-consent-key",
            signature = byteArrayOf(1),
        ),
        validationReceipts = listOf(
            ValidationReceipt(
                receiptId = if (capability == PilotCapability.PHONE_FHIR_MEDICAL_RECORDS) {
                    "validation-fhir-1"
                } else {
                    "validation-health-connect-1"
                },
                capability = capability,
                appVersion = "0.5.0-research",
                deviceModel = "Galaxy S25 Ultra fixture",
                firmwareGeneration = "fixture-fw-1",
                dataSchemaVersion = "history-v1",
                issuedAtEpochMillis = 2_000L,
                expiresAtEpochMillis = 100_000L,
                evidenceIds = listOf("fixture-history"),
                evidenceBundleSha256 = "b".repeat(64),
                issuerKeyId = "fixture-validation-key",
                signature = byteArrayOf(2),
            ),
        ),
    )
}
