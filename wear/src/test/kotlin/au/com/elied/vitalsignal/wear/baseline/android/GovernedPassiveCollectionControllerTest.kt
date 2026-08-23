package au.com.elied.vitalsignal.wear.baseline.android

import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesDevice
import au.com.elied.vitalsignal.wear.governance.GovernedWatchAccessLease
import au.com.elied.vitalsignal.wear.governance.governedWatchLeaseFixture
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedPassiveCollectionControllerTest {
    @Test
    fun missingPermissionLeavesPlatformAndRuntimeInactive() = runBlocking {
        val platform = FakePassivePlatform()
        val runtime = ConsentFencedPassiveRuntime()
        val controller = GovernedPassiveCollectionController(
            platform = platform,
            permissionGate = PassivePermissionGate { setOf("android.permission.health.READ_HEART_RATE") },
            runtime = runtime,
        )

        val result = controller.activate(
            lease = lease(),
            channels = HEART_RATE,
            device = device(),
            store = ControllerStore(4L),
        )

        assertEquals(
            "required_permission_missing",
            (result as PassiveActivationResult.Blocked).code,
        )
        assertEquals(1, platform.clearCalls)
        assertEquals(0, platform.registerCalls)
        assertNull(runtime.deliveryContext())
    }

    @Test
    fun registrationFailureRevokesStorageRuntimeAndBestEffortClearsAgain() = runBlocking {
        val platform = FakePassivePlatform(failRegistration = true)
        val runtime = ConsentFencedPassiveRuntime()
        val controller = controller(platform, runtime)

        val result = controller.activate(lease(), HEART_RATE, device(), ControllerStore(4L))

        assertEquals(
            "platform_listener_registration_failed",
            (result as PassiveActivationResult.Failed).code,
        )
        assertEquals(2, platform.clearCalls)
        assertNull(runtime.deliveryContext())
    }

    @Test
    fun exactLeaseCapabilityAndStorageActivateThenDeactivateService() = runBlocking {
        val platform = FakePassivePlatform()
        val runtime = ConsentFencedPassiveRuntime()
        val controller = controller(platform, runtime)

        val activation = controller.activate(
            lease(),
            setOf(WatchDataChannel.PASSIVE_HEART_RATE, WatchDataChannel.PASSIVE_STEPS),
            device(),
            ControllerStore(4L),
        )

        assertTrue(activation is PassiveActivationResult.Active)
        assertEquals(1, platform.registerCalls)
        assertEquals(4L, runtime.deliveryContext()!!.consentGeneration)
        assertEquals(PassiveDeactivationResult.Inactive, controller.deactivate(4L))
        assertNull(runtime.deliveryContext())
        assertEquals(2, platform.clearCalls)

        val replay = controller.activate(lease(), HEART_RATE, device(), ControllerStore(4L))
        assertEquals(
            "consent_generation_revoked",
            (replay as PassiveActivationResult.Blocked).code,
        )
    }

    @Test
    fun unavailableCapabilityNeverArmsStorageRuntime() = runBlocking {
        val platform = FakePassivePlatform(supported = emptySet())
        val runtime = ConsentFencedPassiveRuntime()

        val result = controller(platform, runtime).activate(
            lease(),
            HEART_RATE,
            device(),
            ControllerStore(4L),
        )

        assertEquals(
            "required_capability_unavailable",
            (result as PassiveActivationResult.Blocked).code,
        )
        assertNull(runtime.deliveryContext())
    }

    @Test
    fun activationResultCollectionsAreImmutableSnapshots() {
        val activeChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val blockedDetail = mutableSetOf("permission-one")
        val active = PassiveActivationResult.Active(4L, activeChannels)
        val blocked = PassiveActivationResult.Blocked("missing", blockedDetail)

        activeChannels += WatchDataChannel.PASSIVE_STEPS
        blockedDetail += "permission-two"
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), active.channels)
        assertEquals(setOf("permission-one"), blocked.detail)

        assertThrows(UnsupportedOperationException::class.java) {
            (active.channels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (blocked.detail as MutableSet<String>) += "permission-two"
        }
    }

    private fun controller(
        platform: FakePassivePlatform,
        runtime: ConsentFencedPassiveRuntime,
    ) = GovernedPassiveCollectionController(
        platform = platform,
        permissionGate = PassivePermissionGate { emptySet() },
        runtime = runtime,
    )

    private fun lease(): GovernedWatchAccessLease = governedWatchLeaseFixture(
        capability = PilotCapability.WATCH_PASSIVE_COLLECTION,
        subjectPseudonym = "pilot-1",
        consentGeneration = 4L,
        evaluatedAtEpochMillis = 1_000L,
    )

    private fun device() = WearHealthServicesDevice(
        stableDeviceAlias = "ultra2-pilot-1",
        manufacturer = "Samsung",
        model = "Galaxy Watch Ultra2",
        firmwareGeneration = "fw-1",
    )

    private companion object {
        val HEART_RATE = setOf(WatchDataChannel.PASSIVE_HEART_RATE)
    }
}

private class FakePassivePlatform(
    private val supported: Set<WatchDataChannel> = ConsentFencedPassiveRuntime.SUPPORTED_CHANNELS,
    private val failRegistration: Boolean = false,
) : PassivePlatformRegistration {
    var registerCalls = 0
    var clearCalls = 0

    override suspend fun supportedChannels(): Set<WatchDataChannel> = supported

    override suspend fun registerService(channels: Set<WatchDataChannel>) {
        registerCalls += 1
        if (failRegistration) error("provider unavailable")
    }

    override suspend fun clearService() {
        clearCalls += 1
    }
}

private class ControllerStore(
    private val generation: Long,
) : DurablePassiveObservationStore {
    override fun readiness(expectedConsentGeneration: Long) = PassiveStoreReadiness(
        ready = generation == expectedConsentGeneration,
        consentGeneration = generation,
        storageKeyId = "watch-key-$generation",
    )

    override fun commit(
        observation: SensorObservation,
        consentGeneration: Long,
    ) = PassiveObservationCommit(
        observationId = observation.id,
        consentGeneration = consentGeneration,
        state = PassiveCommitState.COMMITTED,
        committedAtEpochMillis = observation.epochMillis,
    )
}
