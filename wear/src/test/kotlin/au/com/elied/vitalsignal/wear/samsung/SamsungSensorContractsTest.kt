package au.com.elied.vitalsignal.wear.samsung

import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.ConsentGrantVerifier
import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.governance.ValidationReceiptVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungSensorContractsTest {
    @Test
    fun catalogPreservesOfficialModesRatesAndRestrictions() {
        val ecg = OfficialSamsungSensorCatalog.specification(SamsungTrackerId.ECG_ON_DEMAND)
        val ppgContinuous = OfficialSamsungSensorCatalog.specification(
            SamsungTrackerId.PPG_CONTINUOUS,
        )
        val mfBia = OfficialSamsungSensorCatalog.specification(SamsungTrackerId.MF_BIA_ON_DEMAND)

        assertEquals(500, ecg.sampling.nominalHertz)
        assertEquals(setOf(5, 10), ecg.sampling.eventPointCounts)
        assertTrue(ecg.restriction.foregroundOnly)
        assertEquals(30, ecg.restriction.maximumSessionSeconds)
        assertTrue(ecg.restriction.continuousValuesMayBeInvalidDuringCapture)
        assertEquals(25, ppgContinuous.sampling.nominalHertz)
        assertEquals(setOf(5, 10, 50, 250), mfBia.sampling.frequenciesKilohertz)
        assertEquals(8, mfBia.restriction.minimumGalaxyWatchGeneration)
        assertEquals(SamsungTrackerId.entries.size, OfficialSamsungSensorCatalog.specifications.size)
    }

    @Test
    fun exactGovernanceConsentCapabilityAndPermissionIssueProvenancedPermit() {
        val decision = SamsungSensorPilotGate.evaluate(
            request = SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            context = gateContext(),
        )

        assertTrue(decision is SamsungSensorGateDecision.Allowed)
        val permit = (decision as SamsungSensorGateDecision.Allowed).permit
        assertEquals(7L, permit.consentGeneration)
        assertEquals("validation-watch-1", permit.validationReceiptId)
        assertEquals("participant-1", permit.participantPseudonym)
        assertTrue(permit.governanceConsentGrantSha256.matches(Regex("[a-f0-9]{64}")))
        assertTrue(permit.governanceValidationReceiptSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun androidPermissionCannotReplaceCentralGovernanceApproval() {
        val denied = governanceDecision(collectionPaused = true)

        val decision = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(governanceDecision = denied),
        ) as SamsungSensorGateDecision.Blocked

        assertTrue(SamsungSensorBlockReason.CENTRAL_GOVERNANCE_DENIED in decision.reasons)
    }

    @Test
    fun allowedEvidenceCannotBeReplayedAcrossCapabilitySubjectOrLifetime() {
        val wrongCapability = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(
                governanceDecision = governanceDecision(
                    capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
                ),
            ),
        ) as SamsungSensorGateDecision.Blocked
        assertTrue(SamsungSensorBlockReason.CENTRAL_CAPABILITY_MISMATCH in wrongCapability.reasons)

        val wrongSubject = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(
                governanceDecision = governanceDecision(subjectPseudonym = "participant-2"),
            ),
        ) as SamsungSensorGateDecision.Blocked
        assertTrue(SamsungSensorBlockReason.CENTRAL_SUBJECT_MISMATCH in wrongSubject.reasons)

        val expired = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(nowEpochMillis = 70_001L),
        ) as SamsungSensorGateDecision.Blocked
        assertTrue(SamsungSensorBlockReason.CENTRAL_EVIDENCE_EXPIRED in expired.reasons)
    }

    @Test
    fun onDemandCaptureFailsClosedWhenContinuousTrackersWereNotPaused() {
        val decision = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(
                activeContinuousTrackers = setOf(SamsungTrackerId.PPG_CONTINUOUS),
            ),
        ) as SamsungSensorGateDecision.Blocked

        assertTrue(SamsungSensorBlockReason.CONTINUOUS_TRACKERS_MUST_BE_PAUSED in decision.reasons)
    }

    @Test
    fun missingRuntimeTrackerReportIsUnknownAndBlocked() {
        val decision = SamsungSensorPilotGate.evaluate(
            SamsungSensorStartRequest(SamsungTrackerId.ECG_ON_DEMAND, 30),
            gateContext().copy(inventory = inventory().copy(trackers = emptyMap())),
        ) as SamsungSensorGateDecision.Blocked

        assertTrue(SamsungSensorBlockReason.TRACKER_NOT_SUPPORTED in decision.reasons)
        assertTrue(SamsungSensorBlockReason.PERMISSION_NOT_GRANTED in decision.reasons)
    }

    @Test
    fun consentAndRuntimeCollectionsAreImmutableSnapshots() {
        val mutableTrackers = mutableSetOf(SamsungTrackerId.ECG_ON_DEMAND)
        val consent = SamsungSensorConsent(
            consentId = "consent-7",
            generation = 7L,
            participantPseudonym = "participant-1",
            protocolId = "pilot-protocol-1",
            allowedTrackers = mutableTrackers,
            validFromEpochMillis = 1_000L,
            validUntilEpochMillis = 100_000L,
        )
        val mutableInventory = mutableMapOf(
            SamsungTrackerId.ECG_ON_DEMAND to SamsungTrackerRuntimeCapability(
                SamsungTrackerId.ECG_ON_DEMAND,
                SamsungRuntimeSupport.SUPPORTED,
                SamsungRuntimePermission.GRANTED,
            ),
        )
        val inventory = SamsungSensorRuntimeInventory(
            bridgeState = SamsungSdkBridgeState.INSTALLED,
            sdkVersion = "1.4.1",
            watchModel = "Galaxy Watch Ultra2 fixture",
            firmwareVersion = "fixture-fw-1",
            observedAtEpochMillis = 9_000L,
            trackers = mutableInventory,
        )

        mutableTrackers += SamsungTrackerId.PPG_ON_DEMAND
        mutableInventory.clear()

        assertEquals(setOf(SamsungTrackerId.ECG_ON_DEMAND), consent.allowedTrackers)
        assertEquals(setOf(SamsungTrackerId.ECG_ON_DEMAND), inventory.trackers.keys)
    }

    @Test
    fun gateActivityAndBlockedReasonSetsAreImmutableSnapshots() {
        val onDemand = mutableSetOf(SamsungTrackerId.ECG_ON_DEMAND)
        val continuous = mutableSetOf(SamsungTrackerId.PPG_CONTINUOUS)
        val context = gateContext().copy(
            activeOnDemandTrackers = onDemand,
            activeContinuousTrackers = continuous,
        )
        val reasons = mutableSetOf(SamsungSensorBlockReason.PILOT_DISABLED)
        val blocked = SamsungSensorGateDecision.Blocked(reasons)

        onDemand.clear()
        continuous.clear()
        reasons += SamsungSensorBlockReason.SDK_BRIDGE_NOT_READY
        assertEquals(setOf(SamsungTrackerId.ECG_ON_DEMAND), context.activeOnDemandTrackers)
        assertEquals(setOf(SamsungTrackerId.PPG_CONTINUOUS), context.activeContinuousTrackers)
        assertEquals(setOf(SamsungSensorBlockReason.PILOT_DISABLED), blocked.reasons)

        assertThrows(UnsupportedOperationException::class.java) {
            (context.activeOnDemandTrackers as MutableSet<SamsungTrackerId>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (context.activeContinuousTrackers as MutableSet<SamsungTrackerId>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (blocked.reasons as MutableSet<SamsungSensorBlockReason>).clear()
        }
    }

    @Test
    fun bridgeEventDeepCopiesBoundsAndDigestsUntrustedSdkPayload() {
        val source = byteArrayOf(1, 2, 3)
        val event = bridgeEvent(source)
        val digest = event.payloadSha256
        source[0] = 99
        event.payloadCopy()[1] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3), event.payloadCopy())
        assertEquals(digest, event.payloadSha256)

        assertThrows(IllegalArgumentException::class.java) {
            bridgeEvent(ByteArray(SamsungSensorBridgeEvent.MAX_EVENT_PAYLOAD_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridgeEvent(byteArrayOf(1), sampleCount =
                SamsungSensorBridgeEvent.maximumSamplesFor(SamsungTrackerId.ECG_ON_DEMAND) + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridgeEvent(
                byteArrayOf(1),
                receivedAt = 10_000L + SamsungSensorBridgeEvent.MAX_EVENT_LAG_MILLIS + 1L,
            )
        }
    }

    private fun gateContext() = SamsungSensorPilotGateContext(
        pilotFeatureEnabled = true,
        governanceDecision = governanceDecision(),
        inventory = inventory(),
        consent = SamsungSensorConsent(
            consentId = "consent-7",
            generation = 7L,
            participantPseudonym = "participant-1",
            protocolId = "pilot-protocol-1",
            allowedTrackers = setOf(SamsungTrackerId.ECG_ON_DEMAND),
            validFromEpochMillis = 1_000L,
            validUntilEpochMillis = 100_000L,
        ),
        nowEpochMillis = 10_000L,
        appInForeground = true,
    )

    private fun governanceDecision(
        capability: PilotCapability = PilotCapability.WATCH_RESEARCH_CAPTURE,
        consentGeneration: Long = 7L,
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
            deviceModel = "Galaxy Watch Ultra2 fixture",
            firmwareGeneration = "fixture-fw-1",
            dataSchemaVersion = "watch-raw-v1",
            evaluatedAtEpochMillis = 10_000L,
            collectionPaused = collectionPaused,
            recoveryRequired = false,
        ),
        consent = ConsentGrant(
            subjectPseudonym = subjectPseudonym,
            generation = consentGeneration,
            scopes = setOf(
                if (capability == PilotCapability.WATCH_RESEARCH_CAPTURE) {
                    ConsentScope.RAW_RESEARCH_SIGNALS
                } else {
                    ConsentScope.PASSIVE_WATCH_DATA
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
                receiptId = if (capability == PilotCapability.WATCH_RESEARCH_CAPTURE) {
                    "validation-watch-1"
                } else {
                    "validation-watch-passive-1"
                },
                capability = capability,
                appVersion = "0.5.0-research",
                deviceModel = "Galaxy Watch Ultra2 fixture",
                firmwareGeneration = "fixture-fw-1",
                dataSchemaVersion = "watch-raw-v1",
                issuedAtEpochMillis = 2_000L,
                expiresAtEpochMillis = 100_000L,
                evidenceIds = listOf("fixture-watch"),
                evidenceBundleSha256 = "b".repeat(64),
                issuerKeyId = "fixture-validation-key",
                signature = byteArrayOf(2),
            ),
        ),
    )

    private fun inventory() = SamsungSensorRuntimeInventory(
        bridgeState = SamsungSdkBridgeState.INSTALLED,
        sdkVersion = "1.4.1",
        watchModel = "Galaxy Watch Ultra2 fixture",
        firmwareVersion = "fixture-fw-1",
        observedAtEpochMillis = 9_000L,
        trackers = mapOf(
            SamsungTrackerId.ECG_ON_DEMAND to SamsungTrackerRuntimeCapability(
                SamsungTrackerId.ECG_ON_DEMAND,
                SamsungRuntimeSupport.SUPPORTED,
                SamsungRuntimePermission.GRANTED,
            ),
        ),
    )

    private fun bridgeEvent(
        payload: ByteArray,
        sampleCount: Int = 10,
        receivedAt: Long = 10_100L,
    ) = SamsungSensorBridgeEvent(
        trackerId = SamsungTrackerId.ECG_ON_DEMAND,
        participantPseudonym = "participant-1",
        consentGeneration = 7L,
        validationReceiptId = "validation-watch-1",
        sequence = 1L,
        sampleCount = sampleCount,
        sourceTimestampEpochMillis = 10_000L,
        receivedAtEpochMillis = receivedAt,
        payload = payload,
    )
}
