package au.com.elied.vitalsignal.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSensingPlannerTest {
    private val planner = AdaptiveSensingPlanner()

    @Test
    fun twoQualifiedFamiliesRequestValidatedForegroundCapture() {
        val result = planner.plan(context())

        assertEquals(AdaptiveSensingState.REQUEST_FOREGROUND_CAPTURE, result.state)
        assertEquals(ResearchCaptureModality.ECG_WITH_EMBEDDED_PPG, result.requestedModality)
        assertTrue(result.requiresUserInitiation)
        assertEquals("validation-ecg-ppg", result.validationReceiptId)
    }

    @Test
    fun oneFamilyCanOnlyRequestLowPowerRecheck() {
        val result = planner.plan(context(evidence = listOf(evidence(PassiveEvidenceFamily.CARDIAC_AUTONOMIC))))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
        assertNull(result.requestedModality)
    }

    @Test
    fun poorQualityCannotCorroborate() {
        val poor = evidence(PassiveEvidenceFamily.RESPIRATORY_OXYGEN).copy(quality = 0.4)
        val result = planner.plan(context(evidence = listOf(
            evidence(PassiveEvidenceFamily.CARDIAC_AUTONOMIC),
            poor,
        )))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
    }

    @Test
    fun expiredEvidenceCannotTriggerCapture() {
        val old = evidence(PassiveEvidenceFamily.RESPIRATORY_OXYGEN).copy(observedAtEpochMillis = 1_000L)
        val result = planner.plan(context(evidence = listOf(
            evidence(PassiveEvidenceFamily.CARDIAC_AUTONOMIC),
            old,
        )))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
    }

    @Test
    fun unsupportedOrUnvalidatedCapabilityCannotBeUsed() {
        val result = planner.plan(context(capabilities = listOf(capability().copy(validationReceiptId = null))))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
        assertNull(result.requestedModality)
    }

    @Test
    fun lowBatteryCannotLaunchExpensiveCapture() {
        val result = planner.plan(context(batteryPercent = 9))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
    }

    @Test
    fun chargingThermalAndUnknownWristStatesAbstainBeforeCaptureSelection() {
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(charging = true)).state)
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(thermalSafe = false)).state)
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(onWrist = null)).state)
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(onWrist = false)).state)
    }

    @Test
    fun cooldownPreventsAlertLoop() {
        val result = planner.plan(context(lastEscalationAtEpochMillis = NOW - 1_000L))

        assertEquals(AdaptiveSensingState.LOW_POWER_RECHECK, result.state)
        assertFalse(result.requiresUserInitiation)
    }

    @Test
    fun pauseRecoveryAndStorageFailureAllAbstain() {
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(collectionPaused = true)).state)
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(recoveryRequired = true)).state)
        assertEquals(AdaptiveSensingState.ABSTAINED, planner.plan(context(storageAvailable = false)).state)
    }

    private fun context(
        batteryPercent: Int = 80,
        charging: Boolean = false,
        thermalSafe: Boolean = true,
        onWrist: Boolean? = true,
        storageAvailable: Boolean = true,
        collectionPaused: Boolean = false,
        recoveryRequired: Boolean = false,
        lastEscalationAtEpochMillis: Long? = null,
        evidence: List<PassiveChangeEvidence> = listOf(
            evidence(PassiveEvidenceFamily.CARDIAC_AUTONOMIC),
            evidence(PassiveEvidenceFamily.RESPIRATORY_OXYGEN),
        ),
        capabilities: List<ValidatedCaptureCapability> = listOf(capability()),
    ) = AdaptiveSensingContext(
        evaluatedAtEpochMillis = NOW,
        batteryPercent = batteryPercent,
        charging = charging,
        thermalSafe = thermalSafe,
        onWrist = onWrist,
        storageAvailable = storageAvailable,
        transportBacklogWithinLimit = true,
        collectionPaused = collectionPaused,
        recoveryRequired = recoveryRequired,
        lastEscalationAtEpochMillis = lastEscalationAtEpochMillis,
        evidence = evidence,
        capabilities = capabilities,
    )

    private fun evidence(family: PassiveEvidenceFamily) = PassiveChangeEvidence(
        evidenceId = "evidence-${family.name.lowercase()}",
        family = family,
        absoluteRobustZ = 3.0,
        quality = 0.95,
        persistentMinutes = 20,
        observedAtEpochMillis = NOW - 60_000L,
        provenanceIds = listOf("provenance-${family.name.lowercase()}"),
    )

    private fun capability() = ValidatedCaptureCapability(
        modality = ResearchCaptureModality.ECG_WITH_EMBEDDED_PPG,
        runtimeSupported = true,
        validationReceiptId = "validation-ecg-ppg",
        minimumBatteryPercent = 15,
        foregroundUserInitiated = true,
    )

    private companion object {
        const val NOW = 10_000_000L
    }
}
