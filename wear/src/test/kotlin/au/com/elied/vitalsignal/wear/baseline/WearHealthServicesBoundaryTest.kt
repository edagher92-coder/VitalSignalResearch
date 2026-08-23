package au.com.elied.vitalsignal.wear.baseline

import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WearHealthServicesBoundaryTest {
    @Test
    fun measurementClockAndExactSourceProvenanceArePreserved() {
        val point = point(
            recordId = "record-1",
            start = 1_000L,
            end = 1_100L,
            received = 1_500L,
        )

        val observation = WearHealthServicesObservationMapper().map(point, consentGeneration = 7L)!!

        assertEquals("whs-record-1", observation.id)
        assertEquals(SensorMetric.HEART_RATE, observation.metric)
        assertEquals(1_100L, observation.epochMillis)
        assertEquals(SensorSource.GALAXY_WATCH_ULTRA_2, observation.source)
        assertTrue(observation.provenanceIds.contains("wear-health-services:record-1"))
        assertTrue(observation.provenanceIds.contains("origin:com.google.android.wearable.healthservices"))
        assertTrue(observation.provenanceIds.contains("device:ultra2-pilot-1"))
        assertTrue(observation.provenanceIds.contains("firmware:ultra2-fw-1"))
        assertTrue(observation.provenanceIds.contains("measurement-start:1000"))
        assertTrue(observation.provenanceIds.contains("measurement-end:1100"))
        assertTrue(observation.provenanceIds.contains("received-at:1500"))
        assertTrue(observation.provenanceIds.contains("consent-generation:7"))
    }

    @Test
    fun implausibleFutureMeasurementIsRejectedInsteadOfRetimestamped() {
        val point = point(
            recordId = "future",
            start = 1_000L,
            end = 2_000L,
            received = 1_100L,
        )

        assertEquals(
            null,
            WearHealthServicesObservationMapper(maximumFutureSkewMillis = 100L)
                .map(point, consentGeneration = 1L),
        )
    }

    @Test
    fun stateMachineRequiresConsentCapabilityAndRejectsLateCallbacks() {
        val gateway = FakeHealthServicesGateway()
        val accepted = mutableListOf<SensorObservation>()
        val coordinator = WearHealthServicesCollectionCoordinator(
            gateway = gateway,
            observationMapper = WearHealthServicesObservationMapper(),
            observationSink = accepted::add,
        )
        val request = WearHealthServicesRequest(setOf(WatchDataChannel.PASSIVE_HEART_RATE))

        assertEquals(
            "consent_not_installed",
            (coordinator.start(request) as HealthServicesStartResult.Blocked).code,
        )
        assertTrue(coordinator.installConsent(consent(1, allowed = true)))
        gateway.heartRateCapability = CapabilityState.PERMISSION_REQUIRED
        assertEquals(
            "required_capability_unavailable",
            (coordinator.start(request) as HealthServicesStartResult.Blocked).code,
        )
        gateway.heartRateCapability = CapabilityState.AVAILABLE
        assertTrue(coordinator.start(request) is HealthServicesStartResult.Started)
        assertEquals(HealthServicesCollectionPhase.COLLECTING, coordinator.status().phase)

        gateway.deliver(point("accepted", 1_000L, 1_100L, 1_200L))
        assertEquals(1, accepted.size)
        assertEquals(1L, coordinator.status().acceptedPointCount)

        val oldCallback = gateway.onPoint!!
        assertTrue(coordinator.pause().isSuccess)
        oldCallback(point("late", 1_200L, 1_300L, 1_400L))
        assertEquals(1, accepted.size)
        assertEquals(1L, coordinator.status().rejectedPointCount)
        assertEquals(HealthServicesCollectionPhase.PAUSED, coordinator.status().phase)
    }

    @Test
    fun newConsentGenerationStopsOldRegistrationAndFencesOldCallback() {
        val gateway = FakeHealthServicesGateway()
        val accepted = mutableListOf<SensorObservation>()
        val coordinator = WearHealthServicesCollectionCoordinator(
            gateway,
            WearHealthServicesObservationMapper(),
            accepted::add,
        )
        val request = WearHealthServicesRequest(setOf(WatchDataChannel.PASSIVE_HEART_RATE))
        coordinator.installConsent(consent(4, allowed = true))
        coordinator.start(request)
        val generationFourCallback = gateway.onPoint!!

        assertTrue(coordinator.installConsent(consent(5, allowed = true)))
        assertEquals(1, gateway.stopCalls)
        assertEquals(HealthServicesCollectionPhase.READY, coordinator.status().phase)
        generationFourCallback(point("old-generation", 1_000L, 1_100L, 1_200L))
        assertTrue(accepted.isEmpty())
        assertEquals(1L, coordinator.status().rejectedPointCount)

        assertTrue(coordinator.start(request) is HealthServicesStartResult.Started)
        gateway.deliver(point("new-generation", 1_300L, 1_400L, 1_500L))
        assertEquals(1, accepted.size)
        assertTrue(accepted.single().provenanceIds.contains("consent-generation:5"))

        assertFalse(coordinator.installConsent(consent(4, allowed = true)))
    }

    @Test
    fun asynchronousGatewayFailureMovesCollectionToError() {
        val gateway = FakeHealthServicesGateway()
        val coordinator = WearHealthServicesCollectionCoordinator(
            gateway,
            WearHealthServicesObservationMapper(),
            {},
        )
        coordinator.installConsent(consent(1, allowed = true))
        coordinator.start(WearHealthServicesRequest(setOf(WatchDataChannel.PASSIVE_HEART_RATE)))

        gateway.fail(IllegalStateException("provider disconnected"))

        assertEquals(HealthServicesCollectionPhase.ERROR, coordinator.status().phase)
        assertTrue(coordinator.status().message.contains("provider disconnected"))
        assertTrue(coordinator.status().activeChannels.isEmpty())
    }

    @Test
    fun consentRequestAndStatusChannelsAreImmutableAuthorizationSnapshots() {
        val consentChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val requestChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val statusChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val consent = WearHealthServicesConsent(
            generation = 1L,
            grantedAtEpochMillis = 100L,
            allowedChannels = consentChannels,
            collectionAllowed = true,
        )
        val request = WearHealthServicesRequest(requestChannels)
        val status = HealthServicesCollectionStatus(activeChannels = statusChannels)
        val baselineChannels = mutableSetOf(WatchDataChannel.PASSIVE_HEART_RATE)
        val baselineRequest = PassiveBaselineRequest(baselineChannels)

        consentChannels += WatchDataChannel.PASSIVE_STEPS
        requestChannels += WatchDataChannel.PASSIVE_STEPS
        statusChannels += WatchDataChannel.PASSIVE_STEPS
        baselineChannels += WatchDataChannel.PASSIVE_STEPS

        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), consent.allowedChannels)
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), request.channels)
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), status.activeChannels)
        assertEquals(setOf(WatchDataChannel.PASSIVE_HEART_RATE), baselineRequest.channels)

        assertThrows(UnsupportedOperationException::class.java) {
            (consent.allowedChannels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (request.channels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (status.activeChannels as MutableSet<WatchDataChannel>) += WatchDataChannel.PASSIVE_STEPS
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (baselineRequest.channels as MutableSet<WatchDataChannel>) +=
                WatchDataChannel.PASSIVE_STEPS
        }
    }

    private fun consent(generation: Long, allowed: Boolean) = WearHealthServicesConsent(
        generation = generation,
        grantedAtEpochMillis = generation * 100L,
        allowedChannels = setOf(WatchDataChannel.PASSIVE_HEART_RATE),
        collectionAllowed = allowed,
    )

    private fun point(
        recordId: String,
        start: Long,
        end: Long,
        received: Long,
    ) = WearHealthServicesPoint(
        recordId = recordId,
        channel = WatchDataChannel.PASSIVE_HEART_RATE,
        measurementStartEpochMillis = start,
        measurementEndEpochMillis = end,
        receivedAtEpochMillis = received,
        value = 68.0,
        originPackage = "com.google.android.wearable.healthservices",
        device = WearHealthServicesDevice(
            stableDeviceAlias = "ultra2-pilot-1",
            manufacturer = "Samsung",
            model = "Galaxy Watch Ultra2",
            firmwareGeneration = "ultra2-fw-1",
        ),
        quality = SignalQuality(score = 0.92),
        activityState = ActivityState.RESTING,
    )
}

private class FakeHealthServicesGateway : PublicWearHealthServicesGateway {
    var heartRateCapability: CapabilityState = CapabilityState.AVAILABLE
    var onPoint: ((WearHealthServicesPoint) -> Unit)? = null
    var onFailure: ((Throwable) -> Unit)? = null
    var stopCalls: Int = 0

    override fun inspectCapabilities(): List<SensorCapability> = listOf(
        SensorCapability(
            channel = WatchDataChannel.PASSIVE_HEART_RATE,
            state = heartRateCapability,
            detail = null,
        ),
    )

    override fun start(
        request: WearHealthServicesRequest,
        onPoint: (WearHealthServicesPoint) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Result<Unit> {
        this.onPoint = onPoint
        this.onFailure = onFailure
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        stopCalls += 1
        return Result.success(Unit)
    }

    fun deliver(point: WearHealthServicesPoint) = requireNotNull(onPoint).invoke(point)
    fun fail(error: Throwable) = requireNotNull(onFailure).invoke(error)
}
