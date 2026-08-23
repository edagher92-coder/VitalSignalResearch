package au.com.elied.vitalsignal.wear.baseline

import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.CollectionMode
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class PassiveBaselineRequest(
    channels: Set<WatchDataChannel> = java.util.Set.copyOf(
        setOf(
            WatchDataChannel.PASSIVE_HEART_RATE,
            WatchDataChannel.PASSIVE_STEPS,
        ),
    ),
) {
    val channels: Set<WatchDataChannel> = java.util.Set.copyOf(channels)

    init {
        require(this.channels.isNotEmpty())
        require(this.channels.all { it.mode == CollectionMode.PASSIVE })
    }
}

/**
 * Boundary for low-power, all-day Health Services monitoring. Implementations
 * should register a PassiveListenerService so delivery survives UI process loss.
 * High-rate Samsung trackers must never be routed through this interface.
 */
interface PassiveBaselineSource {
    val observations: Flow<SensorObservation>

    suspend fun inspectCapabilities(): List<SensorCapability>

    suspend fun register(request: PassiveBaselineRequest)

    suspend fun unregister()
}

/** Build-safe placeholder until the Health Services passive service is wired. */
class UnconfiguredPassiveBaselineSource : PassiveBaselineSource {
    override val observations: Flow<SensorObservation> = emptyFlow()

    override suspend fun inspectCapabilities(): List<SensorCapability> = listOf(
        SensorCapability(
            WatchDataChannel.PASSIVE_HEART_RATE,
            CapabilityState.TEMPORARILY_UNAVAILABLE,
            "Health Services passive listener is not registered",
        ),
        SensorCapability(
            WatchDataChannel.PASSIVE_STEPS,
            CapabilityState.TEMPORARILY_UNAVAILABLE,
            "Health Services passive listener is not registered",
        ),
    )

    override suspend fun register(request: PassiveBaselineRequest) = Unit

    override suspend fun unregister() = Unit
}
