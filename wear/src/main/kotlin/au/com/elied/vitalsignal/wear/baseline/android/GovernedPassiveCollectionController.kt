package au.com.elied.vitalsignal.wear.baseline.android

import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesDevice
import au.com.elied.vitalsignal.wear.governance.GovernedWatchAccessLease
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small platform seam so registration sequencing can be tested without Android or a watch. */
interface PassivePlatformRegistration {
    suspend fun supportedChannels(): Set<WatchDataChannel>

    suspend fun registerService(channels: Set<WatchDataChannel>)

    suspend fun clearService()
}

fun interface PassivePermissionGate {
    /** Returns stable permission identifiers that are missing for this exact request. */
    fun missingPermissions(channels: Set<WatchDataChannel>): Set<String>
}

sealed interface PassiveActivationResult {
    class Active(
        val consentGeneration: Long,
        channels: Set<WatchDataChannel>,
    ) : PassiveActivationResult {
        val channels: Set<WatchDataChannel> = java.util.Set.copyOf(channels)
    }

    class Blocked(
        val code: String,
        detail: Set<String> = emptySet(),
    ) : PassiveActivationResult {
        val detail: Set<String> = java.util.Set.copyOf(detail)
    }

    data class Failed(val code: String) : PassiveActivationResult
}

sealed interface PassiveDeactivationResult {
    data object Inactive : PassiveDeactivationResult
    data class Blocked(val code: String) : PassiveDeactivationResult
    data class Failed(val code: String) : PassiveDeactivationResult
}

/**
 * Governs the order in which a real PassiveListenerService is armed.
 *
 * Runtime storage access is cleared before unregistering the platform service. If unregistering
 * fails, late callbacks therefore still fail closed. Conversely, storage access is installed only
 * after permission/capability checks and immediately before the platform registration call.
 */
class GovernedPassiveCollectionController(
    private val platform: PassivePlatformRegistration,
    private val permissionGate: PassivePermissionGate,
    private val runtime: ConsentFencedPassiveRuntime,
) {
    private val operationMutex = Mutex()

    suspend fun activate(
        lease: GovernedWatchAccessLease,
        channels: Set<WatchDataChannel>,
        device: WearHealthServicesDevice,
        store: DurablePassiveObservationStore,
    ): PassiveActivationResult = operationMutex.withLock {
        if (lease.capability != PilotCapability.WATCH_PASSIVE_COLLECTION) {
            return@withLock PassiveActivationResult.Blocked("passive_activation_lease_required")
        }
        if (channels.isEmpty() || channels.any { it !in ConsentFencedPassiveRuntime.SUPPORTED_CHANNELS }) {
            return@withLock PassiveActivationResult.Blocked("unsupported_passive_channel")
        }

        val installed = runtime.deliveryContext()
        if (installed != null && lease.consentGeneration < installed.consentGeneration) {
            return@withLock PassiveActivationResult.Blocked("consent_generation_rollback")
        }
        if (installed != null && !runtime.clear(installed.consentGeneration)) {
            return@withLock PassiveActivationResult.Failed("runtime_clear_failed")
        }

        // Also clears a registration left by a prior process whose in-memory runtime is gone.
        try {
            platform.clearService()
        } catch (_: Throwable) {
            return@withLock PassiveActivationResult.Failed("platform_listener_clear_failed")
        }

        val missingPermissions = permissionGate.missingPermissions(channels)
        if (missingPermissions.isNotEmpty()) {
            return@withLock PassiveActivationResult.Blocked(
                code = "required_permission_missing",
                detail = missingPermissions.toSortedSet(),
            )
        }
        val supported = try {
            platform.supportedChannels()
        } catch (_: Throwable) {
            return@withLock PassiveActivationResult.Failed("capability_query_failed")
        }
        val missingCapabilities = channels - supported
        if (missingCapabilities.isNotEmpty()) {
            return@withLock PassiveActivationResult.Blocked(
                code = "required_capability_unavailable",
                detail = missingCapabilities.mapTo(sortedSetOf(), WatchDataChannel::name),
            )
        }

        val install = runtime.install(lease, channels, device, store)
        if (install is PassiveRuntimeInstallResult.Rejected) {
            return@withLock PassiveActivationResult.Blocked(install.code)
        }
        try {
            platform.registerService(channels)
        } catch (_: Throwable) {
            runtime.clear(lease.consentGeneration)
            runCatching { platform.clearService() }
            return@withLock PassiveActivationResult.Failed("platform_listener_registration_failed")
        }
        PassiveActivationResult.Active(lease.consentGeneration, channels)
    }

    suspend fun deactivate(expectedConsentGeneration: Long): PassiveDeactivationResult =
        operationMutex.withLock {
            require(expectedConsentGeneration > 0L)
            if (!runtime.clear(expectedConsentGeneration, revokeGeneration = true)) {
                return@withLock PassiveDeactivationResult.Blocked("consent_generation_mismatch")
            }
            try {
                platform.clearService()
                PassiveDeactivationResult.Inactive
            } catch (_: Throwable) {
                // The runtime is already absent, so callbacks cannot reach storage.
                PassiveDeactivationResult.Failed("platform_listener_clear_failed_runtime_is_closed")
            }
        }
}
