package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionRecoveryCaptureGateTest {
    private val gate = FunctionRecoveryCaptureGate(
        reviewReceiptVerifier = FunctionRecoveryReviewReceiptVerifier { input ->
            val timing = input.timingEvidence
            input.contract.externalProtocolReviewReceiptId == "reviewed-protocol-1" &&
                input.externalSessionReviewReceiptId == "reviewed-session-1" &&
                timing != null &&
                timing.timingRecordSha256 == "a".repeat(64) &&
                timing.recordedDurationMillis == 12_000L
        },
    )
    private val requiredMarkers = setOf(
        FunctionRecoveryStandardizationMarker.INSTRUCTION_SCRIPT,
        FunctionRecoveryStandardizationMarker.TIMING_METHOD,
        FunctionRecoveryStandardizationMarker.EQUIPMENT_CONFIGURATION,
        FunctionRecoveryStandardizationMarker.ROUTE_OR_CHAIR_CONFIGURATION,
        FunctionRecoveryStandardizationMarker.PRE_CAPTURE_REST_WINDOW,
        FunctionRecoveryStandardizationMarker.POST_CAPTURE_RECOVERY_WINDOW,
    )

    @Test
    fun humanConcernHoldsCaptureBeforeSensorQualityIsConsidered() {
        val decision = gate.qualify(input(humanConcern = HumanConcernState.CONCERN_REPORTED, sensorQuality = 0.99))

        assertEquals(FunctionRecoveryCaptureGateState.HOLD_FOR_HUMAN_REVIEW, decision.state)
        assertFalse(decision.mayCreateResearchEpisode)
        assertTrue(decision.reason.contains("cannot override"))
    }

    @Test
    fun absentExternalReviewAbstainsRatherThanCreatingEligibility() {
        val decision = gate.qualify(input(externalSessionReviewReceiptId = ""))

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertFalse(decision.mayCreateResearchEpisode)
    }

    @Test
    fun callerSuppliedButUnverifiedReviewIdentifierCannotAuthorizeCapture() {
        val decision = gate.qualify(input(externalSessionReviewReceiptId = "forged-session"))

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertFalse(decision.mayCreateResearchEpisode)
    }

    @Test
    fun stoppedOrDeviatedCaptureIsNotComparable() {
        val decision = gate.qualify(input(completion = FunctionRecoveryCaptureCompletion.STOPPED_BY_PARTICIPANT))

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertTrue(decision.reason.contains("incomplete"))
    }

    @Test
    fun participantDeclineIsExplicitAndNeverCreatesAnEpisode() {
        val decision = gate.qualify(
            input(
                completion = FunctionRecoveryCaptureCompletion.DECLINED_BY_PARTICIPANT,
                timingEvidence = null,
            ),
        )

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertFalse(decision.mayCreateResearchEpisode)
    }

    @Test
    fun inconsistentOrUnagreedTimingCannotEnterComparison() {
        val inconsistent = gate.qualify(
            input(timingEvidence = timing(durationMillis = 40_000L, timingHash = "a".repeat(64))),
        )
        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, inconsistent.state)

        val noAgreement = gate.qualify(
            input(
                timingEvidence = timing(
                    agreement = FunctionRecoveryObserverAgreement.NOT_RECORDED,
                ),
            ),
        )
        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, noAgreement.state)
    }

    @Test
    fun forgedTimingFailsExactInputReceiptVerification() {
        val decision = gate.qualify(
            input(timingEvidence = timing(timingHash = "b".repeat(64))),
        )

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertTrue(decision.reason.contains("External protocol"))
    }

    @Test
    fun missingRepeatabilityMarkerAbstains() {
        val decision = gate.qualify(
            input(markerIds = markerIds() - FunctionRecoveryStandardizationMarker.TIMING_METHOD),
        )

        assertEquals(FunctionRecoveryCaptureGateState.ABSTAINED, decision.state)
        assertEquals(
            setOf(FunctionRecoveryStandardizationMarker.TIMING_METHOD),
            decision.missingMarkers,
        )
    }

    @Test
    fun completeReviewedCaptureWithMarkersAndQualityCanEnterResearchComparison() {
        val decision = gate.qualify(input())

        assertEquals(FunctionRecoveryCaptureGateState.QUALIFIED_FOR_RESEARCH_COMPARISON, decision.state)
        assertTrue(decision.mayCreateResearchEpisode)
    }

    @Test
    fun exactReviewInputAndDecisionCollectionsAreImmutableSnapshots() {
        val markers = mutableSetOf(FunctionRecoveryStandardizationMarker.INSTRUCTION_SCRIPT)
        val markerIds = mutableMapOf(
            FunctionRecoveryStandardizationMarker.INSTRUCTION_SCRIPT to "fixture-instruction",
        )
        val contract = FunctionRecoveryProtocolContract(
            protocol = FunctionRecoveryProtocol.FIVE_TIMES_SIT_TO_STAND,
            version = "research-v1",
            reviewState = FunctionRecoveryProtocolReviewState.REVIEWED_FOR_RESEARCH_CAPTURE,
            externalProtocolReviewReceiptId = "reviewed-protocol-1",
            requiredMarkers = markers,
        )
        val capture = FunctionRecoveryCaptureInput(
            contract = contract,
            externalSessionReviewReceiptId = "reviewed-session-1",
            humanConcern = HumanConcernState.NO_CONCERN_REPORTED,
            completion = FunctionRecoveryCaptureCompletion.COMPLETED_AS_REVIEWED,
            sensorQuality = 0.90,
            markerIds = markerIds,
            timingEvidence = timing(),
        )
        val missing = mutableSetOf(FunctionRecoveryStandardizationMarker.TIMING_METHOD)
        val decision = FunctionRecoveryCaptureGateDecision(
            state = FunctionRecoveryCaptureGateState.ABSTAINED,
            reason = "missing",
            missingMarkers = missing,
        )

        markers += FunctionRecoveryStandardizationMarker.TIMING_METHOD
        markerIds.clear()
        missing.clear()
        assertEquals(
            setOf(FunctionRecoveryStandardizationMarker.INSTRUCTION_SCRIPT),
            contract.requiredMarkers,
        )
        assertEquals(1, capture.markerIds.size)
        assertEquals(setOf(FunctionRecoveryStandardizationMarker.TIMING_METHOD), decision.missingMarkers)
        assertThrows(UnsupportedOperationException::class.java) {
            (contract.requiredMarkers as MutableSet<FunctionRecoveryStandardizationMarker>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (capture.markerIds as MutableMap<FunctionRecoveryStandardizationMarker, String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (decision.missingMarkers as MutableSet<FunctionRecoveryStandardizationMarker>).clear()
        }
    }

    private fun input(
        externalSessionReviewReceiptId: String = "reviewed-session-1",
        humanConcern: HumanConcernState = HumanConcernState.NO_CONCERN_REPORTED,
        completion: FunctionRecoveryCaptureCompletion = FunctionRecoveryCaptureCompletion.COMPLETED_AS_REVIEWED,
        sensorQuality: Double = 0.90,
        markerIds: Map<FunctionRecoveryStandardizationMarker, String> = markerIds(),
        timingEvidence: FunctionRecoveryTimingEvidence? = timing(),
    ) = FunctionRecoveryCaptureInput(
        contract = FunctionRecoveryProtocolContract(
            protocol = FunctionRecoveryProtocol.FIVE_TIMES_SIT_TO_STAND,
            version = "research-v1",
            reviewState = FunctionRecoveryProtocolReviewState.REVIEWED_FOR_RESEARCH_CAPTURE,
            externalProtocolReviewReceiptId = "reviewed-protocol-1",
            requiredMarkers = requiredMarkers,
        ),
        externalSessionReviewReceiptId = externalSessionReviewReceiptId,
        humanConcern = humanConcern,
        completion = completion,
        sensorQuality = sensorQuality,
        markerIds = markerIds,
        timingEvidence = timingEvidence,
    )

    private fun markerIds() = requiredMarkers.associateWith { marker -> "fixture-${marker.name}" }

    private fun timing(
        durationMillis: Long = 12_000L,
        agreement: FunctionRecoveryObserverAgreement =
            FunctionRecoveryObserverAgreement.AGREES_COMPLETED_AS_REVIEWED,
        timingHash: String = "a".repeat(64),
    ) = FunctionRecoveryTimingEvidence(
        taskStartedAtEpochMillis = 10_000L,
        taskEndedAtEpochMillis = 22_000L,
        recordedDurationMillis = durationMillis,
        timingSource = FunctionRecoveryTimingSource.APP_MONOTONIC_TIMER,
        observerPrincipalId = "observer-1",
        observerAgreement = agreement,
        timingRecordSha256 = timingHash,
    )
}
