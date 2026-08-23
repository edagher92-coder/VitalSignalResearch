package au.com.elied.vitalsignal.wear.baseline.android

import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesDevice
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesObservationMapper
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesPoint
import au.com.elied.vitalsignal.wear.governance.GovernedWatchAccessLease
import au.com.elied.vitalsignal.wear.sensor.CollectionMode
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel

/**
 * Storage readiness is generation-bound. A generic "database open" flag is insufficient because
 * a callback from a revoked consent generation must never be committed under a newer key/fence.
 */
data class PassiveStoreReadiness(
    val ready: Boolean,
    val consentGeneration: Long?,
    val storageKeyId: String?,
) {
    init {
        require(consentGeneration == null || consentGeneration > 0L)
        require(storageKeyId == null || storageKeyId.matches(SAFE_ID))
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

enum class PassiveCommitState {
    COMMITTED,
    ALREADY_COMMITTED,
    REJECTED,
}

/** A receipt is accepted only when it names the exact observation and consent generation. */
data class PassiveObservationCommit(
    val observationId: String,
    val consentGeneration: Long,
    val state: PassiveCommitState,
    val committedAtEpochMillis: Long,
) {
    init {
        require(observationId.isNotBlank())
        require(consentGeneration > 0L)
        require(committedAtEpochMillis >= 0L)
    }

    val durable: Boolean
        get() = state == PassiveCommitState.COMMITTED || state == PassiveCommitState.ALREADY_COMMITTED
}

/**
 * Synchronous by design: PassiveListenerService must not acknowledge a batch and then lose an
 * in-memory coroutine. Implementations should make a small encrypted append and return a receipt.
 */
interface DurablePassiveObservationStore {
    fun readiness(expectedConsentGeneration: Long): PassiveStoreReadiness

    fun commit(
        observation: SensorObservation,
        consentGeneration: Long,
    ): PassiveObservationCommit
}

sealed interface PassiveRuntimeInstallResult {
    class Installed(
        val consentGeneration: Long,
        channels: Set<WatchDataChannel>,
    ) : PassiveRuntimeInstallResult {
        val channels: Set<WatchDataChannel> = java.util.Set.copyOf(channels)

        override fun equals(other: Any?): Boolean = other is Installed &&
            consentGeneration == other.consentGeneration && channels == other.channels

        override fun hashCode(): Int = 31 * consentGeneration.hashCode() + channels.hashCode()

        override fun toString(): String =
            "Installed(consentGeneration=$consentGeneration, channels=$channels)"
    }

    data class Rejected(val code: String) : PassiveRuntimeInstallResult
}

sealed interface PassiveDispatchResult {
    data object RuntimeNotInstalled : PassiveDispatchResult

    data class GenerationMismatch(
        val expectedGeneration: Long,
        val installedGeneration: Long,
    ) : PassiveDispatchResult

    data class Completed(
        val accepted: Int,
        val rejected: Int,
    ) : PassiveDispatchResult

    data class StorageFailed(
        val code: String,
        val acceptedBeforeFailure: Int,
        val rejectedBeforeFailure: Int,
    ) : PassiveDispatchResult
}

class PassiveDeliveryContext(
    val consentGeneration: Long,
    val activatedAtEpochMillis: Long,
    allowedChannels: Set<WatchDataChannel>,
    val device: WearHealthServicesDevice,
) {
    val allowedChannels: Set<WatchDataChannel> = java.util.Set.copyOf(allowedChannels)

    fun copy(
        consentGeneration: Long = this.consentGeneration,
        activatedAtEpochMillis: Long = this.activatedAtEpochMillis,
        allowedChannels: Set<WatchDataChannel> = this.allowedChannels,
        device: WearHealthServicesDevice = this.device,
    ) = PassiveDeliveryContext(
        consentGeneration,
        activatedAtEpochMillis,
        allowedChannels,
        device,
    )

    override fun equals(other: Any?): Boolean = other is PassiveDeliveryContext &&
        consentGeneration == other.consentGeneration &&
        activatedAtEpochMillis == other.activatedAtEpochMillis &&
        allowedChannels == other.allowedChannels && device == other.device

    override fun hashCode(): Int = listOf(
        consentGeneration,
        activatedAtEpochMillis,
        allowedChannels,
        device,
    ).hashCode()
}

/**
 * Pure consent/storage fence shared by the real Android service and JVM tests.
 *
 * Health Services may deliver a batch collected before registration. On each consent-generation
 * change, points older than [GovernedWatchAccessLease.evaluatedAtEpochMillis] are rejected. This
 * prevents late data from an earlier registration being silently relabelled as newly consented.
 */
class ConsentFencedPassiveRuntime(
    private val mapper: WearHealthServicesObservationMapper = WearHealthServicesObservationMapper(),
) {
    private var installation: Installation? = null
    private var highestInstalledGeneration: Long = 0L
    private var revokedThroughGeneration: Long = 0L

    @Synchronized
    fun install(
        lease: GovernedWatchAccessLease,
        channels: Set<WatchDataChannel>,
        device: WearHealthServicesDevice,
        store: DurablePassiveObservationStore,
    ): PassiveRuntimeInstallResult {
        if (lease.capability != PilotCapability.WATCH_PASSIVE_COLLECTION) {
            return PassiveRuntimeInstallResult.Rejected("passive_activation_lease_required")
        }
        if (channels.isEmpty() || channels.any { it.mode != CollectionMode.PASSIVE } ||
            channels.any { it !in SUPPORTED_CHANNELS }
        ) {
            return PassiveRuntimeInstallResult.Rejected("unsupported_passive_channel")
        }
        val current = installation
        if (lease.consentGeneration < highestInstalledGeneration) {
            return PassiveRuntimeInstallResult.Rejected("consent_generation_rollback")
        }
        if (lease.consentGeneration <= revokedThroughGeneration) {
            return PassiveRuntimeInstallResult.Rejected("consent_generation_revoked")
        }
        if (current != null) {
            return PassiveRuntimeInstallResult.Rejected("runtime_already_installed")
        }

        val readiness = try {
            store.readiness(lease.consentGeneration)
        } catch (_: Throwable) {
            return PassiveRuntimeInstallResult.Rejected("storage_readiness_failed")
        }
        if (!readiness.ready || readiness.consentGeneration != lease.consentGeneration ||
            readiness.storageKeyId.isNullOrBlank()
        ) {
            return PassiveRuntimeInstallResult.Rejected("durable_storage_not_ready_for_generation")
        }

        installation = Installation(
            context = PassiveDeliveryContext(
                consentGeneration = lease.consentGeneration,
                activatedAtEpochMillis = lease.evaluatedAtEpochMillis,
                allowedChannels = channels,
                device = device,
            ),
            store = store,
        )
        highestInstalledGeneration = maxOf(highestInstalledGeneration, lease.consentGeneration)
        return PassiveRuntimeInstallResult.Installed(lease.consentGeneration, channels)
    }

    @Synchronized
    fun deliveryContext(): PassiveDeliveryContext? {
        val context = installation?.context ?: return null
        return context.copy()
    }

    /** Clears storage access before the platform listener is unregistered, so late calls drop. */
    @Synchronized
    fun clear(
        expectedConsentGeneration: Long,
        revokeGeneration: Boolean = false,
    ): Boolean {
        require(expectedConsentGeneration > 0L)
        val current = installation
        if (current == null) {
            if (expectedConsentGeneration < highestInstalledGeneration) return false
            if (revokeGeneration) {
                highestInstalledGeneration = maxOf(highestInstalledGeneration, expectedConsentGeneration)
                revokedThroughGeneration = maxOf(revokedThroughGeneration, expectedConsentGeneration)
            }
            return true
        }
        if (current.context.consentGeneration != expectedConsentGeneration) return false
        installation = null
        if (revokeGeneration) {
            revokedThroughGeneration = maxOf(revokedThroughGeneration, expectedConsentGeneration)
        }
        return true
    }

    @Synchronized
    fun dispatch(
        expectedConsentGeneration: Long,
        points: List<WearHealthServicesPoint>,
    ): PassiveDispatchResult {
        val active = installation ?: return PassiveDispatchResult.RuntimeNotInstalled
        val context = active.context
        if (expectedConsentGeneration != context.consentGeneration) {
            return PassiveDispatchResult.GenerationMismatch(
                expectedGeneration = expectedConsentGeneration,
                installedGeneration = context.consentGeneration,
            )
        }

        var accepted = 0
        var rejected = 0
        for (point in points) {
            val eligible = point.channel in context.allowedChannels &&
                point.channel in SUPPORTED_CHANNELS &&
                point.device == context.device &&
                point.measurementStartEpochMillis >= context.activatedAtEpochMillis
            val observation = if (eligible) {
                mapper.map(point, context.consentGeneration)
            } else {
                null
            }
            if (observation == null) {
                rejected += 1
                continue
            }

            val receipt = try {
                active.store.commit(observation, context.consentGeneration)
            } catch (_: Throwable) {
                installation = null
                return PassiveDispatchResult.StorageFailed(
                    code = "storage_commit_threw",
                    acceptedBeforeFailure = accepted,
                    rejectedBeforeFailure = rejected,
                )
            }
            val exactDurableCommit = receipt.durable &&
                receipt.observationId == observation.id &&
                receipt.consentGeneration == context.consentGeneration
            if (!exactDurableCommit) {
                installation = null
                return PassiveDispatchResult.StorageFailed(
                    code = "invalid_durable_commit_receipt",
                    acceptedBeforeFailure = accepted,
                    rejectedBeforeFailure = rejected,
                )
            }
            accepted += 1
        }
        return PassiveDispatchResult.Completed(accepted = accepted, rejected = rejected)
    }

    private data class Installation(
        val context: PassiveDeliveryContext,
        val store: DurablePassiveObservationStore,
    )

    companion object {
        val SUPPORTED_CHANNELS: Set<WatchDataChannel> = java.util.Set.copyOf(
            setOf(
                WatchDataChannel.PASSIVE_HEART_RATE,
                WatchDataChannel.PASSIVE_STEPS,
            ),
        )
    }
}

/**
 * Reboot restoration is deliberately disabled in this pilot.
 *
 * AndroidX passive registrations do not survive reboot, but a correct WorkManager restore also
 * needs recoverable signed consent, the exact validation receipt, and the generation-bound
 * encrypted storage key. Those are not yet durably provisioned on the watch. The manifest must
 * therefore not register a BOOT_COMPLETED receiver until this contract can return Enabled.
 */
object PassiveBootRestoreContract {
    const val automaticRestoreEnabled: Boolean = false
    const val code: String = "disabled_requires_signed_runtime_recovery"

    fun evaluate(): PassiveBootRestoreDecision = PassiveBootRestoreDecision.Disabled(code)
}

sealed interface PassiveBootRestoreDecision {
    data class Disabled(val code: String) : PassiveBootRestoreDecision
}
