package au.com.elied.vitalsignal.wear.baseline.android

import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesDevice
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesPoint
import au.com.elied.vitalsignal.wear.governance.GovernedWatchAccessLease
import au.com.elied.vitalsignal.wear.governance.governedWatchLeaseFixture
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentFencedPassiveRuntimeTest {
    @Test
    fun researchCaptureLeaseCannotArmPublicPassiveRuntime() {
        val runtime = ConsentFencedPassiveRuntime()

        val result = runtime.install(
            lease = lease(PilotCapability.WATCH_RESEARCH_CAPTURE),
            channels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
            device = device(),
            store = RecordingStore(7L),
        )

        assertEquals(
            "passive_activation_lease_required",
            (result as PassiveRuntimeInstallResult.Rejected).code,
        )
        assertNull(runtime.deliveryContext())
    }

    @Test
    fun storageMustBeReadyForExactConsentGeneration() {
        val runtime = ConsentFencedPassiveRuntime()

        val result = runtime.install(
            lease = lease(generation = 7L),
            channels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
            device = device(),
            store = RecordingStore(readinessGeneration = 6L),
        )

        assertEquals(
            "durable_storage_not_ready_for_generation",
            (result as PassiveRuntimeInstallResult.Rejected).code,
        )
    }

    @Test
    fun preActivationBatchIsRejectedAndMeasurementTimestampIsPreserved() {
        val runtime = ConsentFencedPassiveRuntime()
        val store = RecordingStore(7L)
        assertTrue(
            runtime.install(
                lease = lease(generation = 7L, activatedAt = 1_000L),
                channels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
                device = device(),
                store = store,
            ) is PassiveRuntimeInstallResult.Installed,
        )

        val result = runtime.dispatch(
            expectedConsentGeneration = 7L,
            points = listOf(
                point("old", measuredAt = 900L, receivedAt = 1_200L),
                point("new", measuredAt = 1_100L, receivedAt = 1_200L),
            ),
        ) as PassiveDispatchResult.Completed

        assertEquals(1, result.accepted)
        assertEquals(1, result.rejected)
        assertEquals(1_100L, store.observations.single().epochMillis)
        assertTrue(store.observations.single().provenanceIds.contains("received-at:1200"))
        assertTrue(store.observations.single().provenanceIds.contains("consent-generation:7"))
    }

    @Test
    fun mismatchedCommitReceiptClosesRuntime() {
        val runtime = ConsentFencedPassiveRuntime()
        val store = RecordingStore(7L, receiptObservationId = "wrong-id")
        runtime.install(
            lease = lease(),
            channels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
            device = device(),
            store = store,
        )

        val result = runtime.dispatch(7L, listOf(point("one", 1_100L, 1_200L)))

        assertEquals(
            "invalid_durable_commit_receipt",
            (result as PassiveDispatchResult.StorageFailed).code,
        )
        assertNull(runtime.deliveryContext())
        assertEquals(PassiveDispatchResult.RuntimeNotInstalled, runtime.dispatch(7L, emptyList()))
    }

    @Test
    fun dispatchAndClearAreGenerationFenced() {
        val runtime = ConsentFencedPassiveRuntime()
        runtime.install(
            lease = lease(),
            channels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
            device = device(),
            store = RecordingStore(7L),
        )

        assertTrue(runtime.dispatch(6L, emptyList()) is PassiveDispatchResult.GenerationMismatch)
        assertFalse(runtime.clear(6L))
        assertTrue(runtime.clear(7L))
        assertNull(runtime.deliveryContext())
    }

    @Test
    fun rebootRestoreIsExplicitlyDisabledUntilSignedStateIsRecoverable() {
        assertFalse(PassiveBootRestoreContract.automaticRestoreEnabled)
        val decision = PassiveBootRestoreContract.evaluate() as PassiveBootRestoreDecision.Disabled
        assertEquals("disabled_requires_signed_runtime_recovery", decision.code)
    }

    @Test
    fun installedAndDeliveryChannelSetsCannotBeBroadenedAfterAuthorization() {
        val callerChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val runtime = ConsentFencedPassiveRuntime()
        val result = runtime.install(
            lease = lease(),
            channels = callerChannels,
            device = device(),
            store = RecordingStore(7L),
        ) as PassiveRuntimeInstallResult.Installed

        callerChannels += WatchDataChannel.PASSIVE_STEPS
        val context = requireNotNull(runtime.deliveryContext())
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), result.channels)
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), context.allowedChannels)

        assertThrows(UnsupportedOperationException::class.java) {
            (result.channels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (context.allowedChannels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (ConsentFencedPassiveRuntime.SUPPORTED_CHANNELS as MutableSet<WatchDataChannel>).clear()
        }
    }

    private fun lease(
        capability: PilotCapability = PilotCapability.WATCH_PASSIVE_COLLECTION,
        generation: Long = 7L,
        activatedAt: Long = 1_000L,
    ): GovernedWatchAccessLease = governedWatchLeaseFixture(
        capability = capability,
        subjectPseudonym = "pilot-1",
        consentGeneration = generation,
        evaluatedAtEpochMillis = activatedAt,
    )

    private fun device() = WearHealthServicesDevice(
        stableDeviceAlias = "ultra2-pilot-1",
        manufacturer = "Samsung",
        model = "Galaxy Watch Ultra2",
        firmwareGeneration = "fw-1",
    )

    private fun point(
        id: String,
        measuredAt: Long,
        receivedAt: Long,
    ) = WearHealthServicesPoint(
        recordId = id,
        channel = WatchDataChannel.PASSIVE_HEART_RATE,
        measurementStartEpochMillis = measuredAt,
        measurementEndEpochMillis = measuredAt,
        receivedAtEpochMillis = receivedAt,
        value = 64.0,
        originPackage = "com.google.android.wearable.healthservices",
        device = device(),
        quality = SignalQuality(score = 0.55),
    )
}

private class RecordingStore(
    private val readinessGeneration: Long,
    private val receiptObservationId: String? = null,
) : DurablePassiveObservationStore {
    val observations = mutableListOf<SensorObservation>()

    override fun readiness(expectedConsentGeneration: Long) = PassiveStoreReadiness(
        ready = true,
        consentGeneration = readinessGeneration,
        storageKeyId = "watch-key-$readinessGeneration",
    )

    override fun commit(
        observation: SensorObservation,
        consentGeneration: Long,
    ): PassiveObservationCommit {
        observations += observation
        return PassiveObservationCommit(
            observationId = receiptObservationId ?: observation.id,
            consentGeneration = consentGeneration,
            state = PassiveCommitState.COMMITTED,
            committedAtEpochMillis = observation.epochMillis + 1L,
        )
    }
}
