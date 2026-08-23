package au.com.elied.vitalsignal.wear.baseline

import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorObservation
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.CollectionMode
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel

/** Exact device identity is captured at the public API boundary, never inferred from a value. */
data class WearHealthServicesDevice(
    val stableDeviceAlias: String,
    val manufacturer: String,
    val model: String,
    val firmwareGeneration: String,
) {
    init {
        require(stableDeviceAlias.matches(SAFE_COMPONENT))
        require(manufacturer.isNotBlank())
        require(model.isNotBlank())
        require(firmwareGeneration.matches(SAFE_COMPONENT))
    }

    private companion object {
        val SAFE_COMPONENT = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

class WearHealthServicesConsent(
    val generation: Long,
    val grantedAtEpochMillis: Long,
    allowedChannels: Set<WatchDataChannel>,
    val collectionAllowed: Boolean,
) {
    val allowedChannels: Set<WatchDataChannel> = java.util.Set.copyOf(allowedChannels)

    init {
        require(generation > 0L)
        require(grantedAtEpochMillis >= 0L)
        require(this.allowedChannels.isNotEmpty())
        require(this.allowedChannels.all { it.mode == CollectionMode.PASSIVE })
    }

    override fun equals(other: Any?): Boolean = other is WearHealthServicesConsent &&
        generation == other.generation && grantedAtEpochMillis == other.grantedAtEpochMillis &&
        allowedChannels == other.allowedChannels && collectionAllowed == other.collectionAllowed

    override fun hashCode(): Int = listOf(
        generation,
        grantedAtEpochMillis,
        allowedChannels,
        collectionAllowed,
    ).hashCode()
}

/**
 * Platform-neutral representation of a point delivered by AndroidX Health Services. The later
 * Android adapter must populate both measurement and receipt clocks and the exact data origin.
 */
data class WearHealthServicesPoint(
    val recordId: String,
    val channel: WatchDataChannel,
    val measurementStartEpochMillis: Long,
    val measurementEndEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val value: Double,
    val originPackage: String,
    val device: WearHealthServicesDevice,
    val quality: SignalQuality,
    val activityState: ActivityState = ActivityState.UNKNOWN,
) {
    init {
        require(recordId.matches(SAFE_RECORD_ID))
        require(channel.mode == CollectionMode.PASSIVE)
        require(measurementStartEpochMillis >= 0L)
        require(measurementEndEpochMillis >= measurementStartEpochMillis)
        require(receivedAtEpochMillis >= 0L)
        require(value.isFinite())
        require(originPackage.matches(PACKAGE_NAME))
    }

    private companion object {
        val SAFE_RECORD_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

class WearHealthServicesRequest(
    channels: Set<WatchDataChannel>,
) {
    val channels: Set<WatchDataChannel> = java.util.Set.copyOf(channels)

    init {
        require(this.channels.isNotEmpty())
        require(this.channels.all { it.mode == CollectionMode.PASSIVE })
    }

    override fun equals(other: Any?): Boolean =
        other is WearHealthServicesRequest && channels == other.channels

    override fun hashCode(): Int = channels.hashCode()
}

/**
 * Public Wear OS collection port. A concrete adapter may use PassiveMonitoringClient and a
 * PassiveListenerService, but no Android or proprietary SDK type enters the domain layer.
 */
interface PublicWearHealthServicesGateway {
    fun inspectCapabilities(): List<SensorCapability>

    fun start(
        request: WearHealthServicesRequest,
        onPoint: (WearHealthServicesPoint) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Result<Unit>

    fun stop(): Result<Unit>
}

enum class HealthServicesCollectionPhase {
    IDLE,
    READY,
    STARTING,
    COLLECTING,
    PAUSED,
    BLOCKED,
    ERROR,
}

class HealthServicesCollectionStatus(
    val phase: HealthServicesCollectionPhase = HealthServicesCollectionPhase.IDLE,
    val consentGeneration: Long? = null,
    activeChannels: Set<WatchDataChannel> = emptySet(),
    val acceptedPointCount: Long = 0L,
    val rejectedPointCount: Long = 0L,
    val message: String = "Public Health Services collection is not configured",
) {
    val activeChannels: Set<WatchDataChannel> = java.util.Set.copyOf(activeChannels)

    fun copy(
        phase: HealthServicesCollectionPhase = this.phase,
        consentGeneration: Long? = this.consentGeneration,
        activeChannels: Set<WatchDataChannel> = this.activeChannels,
        acceptedPointCount: Long = this.acceptedPointCount,
        rejectedPointCount: Long = this.rejectedPointCount,
        message: String = this.message,
    ) = HealthServicesCollectionStatus(
        phase,
        consentGeneration,
        activeChannels,
        acceptedPointCount,
        rejectedPointCount,
        message,
    )

    override fun equals(other: Any?): Boolean = other is HealthServicesCollectionStatus &&
        phase == other.phase && consentGeneration == other.consentGeneration &&
        activeChannels == other.activeChannels && acceptedPointCount == other.acceptedPointCount &&
        rejectedPointCount == other.rejectedPointCount && message == other.message

    override fun hashCode(): Int = listOf(
        phase,
        consentGeneration,
        activeChannels,
        acceptedPointCount,
        rejectedPointCount,
        message,
    ).hashCode()
}

sealed interface HealthServicesStartResult {
    data class Started(val status: HealthServicesCollectionStatus) : HealthServicesStartResult
    data class Blocked(val code: String, val status: HealthServicesCollectionStatus) : HealthServicesStartResult
    data class Failed(val status: HealthServicesCollectionStatus) : HealthServicesStartResult
}

/**
 * Maps a point only when clocks and device identity remain auditable. The event clock is kept as
 * [SensorObservation.epochMillis]; receipt time remains in provenance and is never substituted.
 */
class WearHealthServicesObservationMapper(
    private val maximumFutureSkewMillis: Long = 5L * 60L * 1_000L,
) {
    init {
        require(maximumFutureSkewMillis >= 0L)
    }

    fun map(
        point: WearHealthServicesPoint,
        consentGeneration: Long,
    ): SensorObservation? {
        require(consentGeneration > 0L)
        if (point.measurementEndEpochMillis > point.receivedAtEpochMillis + maximumFutureSkewMillis) {
            return null
        }
        val metric = when (point.channel) {
            WatchDataChannel.PASSIVE_HEART_RATE -> SensorMetric.HEART_RATE
            WatchDataChannel.PASSIVE_STEPS -> SensorMetric.STEP_COUNT
            else -> return null
        }
        return SensorObservation(
            id = "whs-${point.recordId}",
            metric = metric,
            epochMillis = point.measurementEndEpochMillis,
            value = point.value,
            quality = point.quality,
            source = SensorSource.GALAXY_WATCH_ULTRA_2,
            activityState = point.activityState,
            provenanceIds = listOf(
                "wear-health-services:${point.recordId}",
                "origin:${point.originPackage}",
                "device:${point.device.stableDeviceAlias}",
                "firmware:${point.device.firmwareGeneration}",
                "measurement-start:${point.measurementStartEpochMillis}",
                "measurement-end:${point.measurementEndEpochMillis}",
                "received-at:${point.receivedAtEpochMillis}",
                "consent-generation:$consentGeneration",
            ),
        )
    }
}

/**
 * Consent-fenced state machine. Callback generations are captured on start so late deliveries from
 * a stopped or superseded registration are rejected even when the platform invokes them later.
 */
class WearHealthServicesCollectionCoordinator(
    private val gateway: PublicWearHealthServicesGateway,
    private val observationMapper: WearHealthServicesObservationMapper,
    private val observationSink: (SensorObservation) -> Unit,
) {
    private var consent: WearHealthServicesConsent? = null
    private var callbackGeneration: Long? = null
    private var statusValue = HealthServicesCollectionStatus()

    @Synchronized
    fun status(): HealthServicesCollectionStatus = statusValue.copy()

    @Synchronized
    fun installConsent(next: WearHealthServicesConsent): Boolean {
        val current = consent
        if (current != null && next.generation < current.generation) return false
        if (current != null && next.generation == current.generation &&
            !current.collectionAllowed && next.collectionAllowed
        ) return false
        if (current != null && next.generation == current.generation &&
            next.grantedAtEpochMillis < current.grantedAtEpochMillis
        ) return false

        val activeRegistrationMustStop = callbackGeneration != null &&
            (next.generation != callbackGeneration || !next.collectionAllowed)
        if (activeRegistrationMustStop) {
            gateway.stop()
            callbackGeneration = null
        }
        consent = next
        statusValue = if (next.collectionAllowed) {
            HealthServicesCollectionStatus(
                phase = HealthServicesCollectionPhase.READY,
                consentGeneration = next.generation,
                acceptedPointCount = statusValue.acceptedPointCount,
                rejectedPointCount = statusValue.rejectedPointCount,
                message = "Consent generation ${next.generation} is ready for capability checks",
            )
        } else {
            HealthServicesCollectionStatus(
                phase = HealthServicesCollectionPhase.PAUSED,
                consentGeneration = next.generation,
                acceptedPointCount = statusValue.acceptedPointCount,
                rejectedPointCount = statusValue.rejectedPointCount,
                message = "Collection is paused by consent",
            )
        }
        return true
    }

    @Synchronized
    fun start(request: WearHealthServicesRequest): HealthServicesStartResult {
        val activeConsent = consent
        if (activeConsent == null) return block("consent_not_installed")
        if (!activeConsent.collectionAllowed) return block("collection_not_consented")
        if (!activeConsent.allowedChannels.containsAll(request.channels)) {
            return block("channel_not_consented")
        }
        if (callbackGeneration != null) {
            return if (callbackGeneration == activeConsent.generation &&
                statusValue.activeChannels == request.channels
            ) {
                HealthServicesStartResult.Started(status())
            } else {
                block("collection_already_active")
            }
        }
        val capabilityMap = try {
            gateway.inspectCapabilities().associateBy(SensorCapability::channel)
        } catch (error: Throwable) {
            return fail(error.message ?: "capability_check_failed")
        }
        val unavailable = request.channels.filter { capabilityMap[it]?.state != CapabilityState.AVAILABLE }
        if (unavailable.isNotEmpty()) return block("required_capability_unavailable")

        val generationAtRegistration = activeConsent.generation
        statusValue = statusValue.copy(
            phase = HealthServicesCollectionPhase.STARTING,
            consentGeneration = generationAtRegistration,
            activeChannels = request.channels,
            message = "Registering public Health Services listener",
        )
        val result = try {
            gateway.start(
                request = request,
                onPoint = { point -> acceptPoint(generationAtRegistration, request.channels, point) },
                onFailure = { error -> failCallback(generationAtRegistration, error) },
            )
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (result.isFailure) return fail(result.exceptionOrNull()?.message ?: "registration_failed")
        callbackGeneration = generationAtRegistration
        statusValue = statusValue.copy(
            phase = HealthServicesCollectionPhase.COLLECTING,
            activeChannels = request.channels,
            message = "Public Health Services baseline collection active",
        )
        return HealthServicesStartResult.Started(status())
    }

    @Synchronized
    fun pause(): Result<Unit> {
        val result = if (callbackGeneration != null) gateway.stop() else Result.success(Unit)
        callbackGeneration = null
        statusValue = statusValue.copy(
            phase = if (result.isSuccess) HealthServicesCollectionPhase.PAUSED else HealthServicesCollectionPhase.ERROR,
            activeChannels = emptySet(),
            message = if (result.isSuccess) "Collection paused" else "Listener could not stop cleanly",
        )
        return result
    }

    @Synchronized
    private fun acceptPoint(
        deliveredGeneration: Long,
        registeredChannels: Set<WatchDataChannel>,
        point: WearHealthServicesPoint,
    ) {
        val activeConsent = consent
        val current = statusValue
        val validState = callbackGeneration == deliveredGeneration &&
            current.phase == HealthServicesCollectionPhase.COLLECTING &&
            activeConsent?.generation == deliveredGeneration &&
            activeConsent.collectionAllowed &&
            point.channel in registeredChannels &&
            point.channel in activeConsent.allowedChannels
        val mapped = if (validState) observationMapper.map(point, deliveredGeneration) else null
        if (mapped == null) {
            statusValue = current.copy(rejectedPointCount = current.rejectedPointCount + 1L)
            return
        }
        try {
            observationSink(mapped)
            statusValue = current.copy(acceptedPointCount = current.acceptedPointCount + 1L)
        } catch (error: Throwable) {
            callbackGeneration = null
            runCatching { gateway.stop() }
            statusValue = current.copy(
                phase = HealthServicesCollectionPhase.ERROR,
                activeChannels = emptySet(),
                message = error.message ?: "Observation sink failed",
            )
        }
    }

    @Synchronized
    private fun failCallback(deliveredGeneration: Long, error: Throwable) {
        if (callbackGeneration != deliveredGeneration) return
        callbackGeneration = null
        statusValue = statusValue.copy(
            phase = HealthServicesCollectionPhase.ERROR,
            activeChannels = emptySet(),
            message = error.message ?: "Health Services delivery failed",
        )
    }

    private fun block(code: String): HealthServicesStartResult.Blocked {
        statusValue = statusValue.copy(
            phase = HealthServicesCollectionPhase.BLOCKED,
            activeChannels = emptySet(),
            message = code,
        )
        return HealthServicesStartResult.Blocked(code, status())
    }

    private fun fail(message: String): HealthServicesStartResult.Failed {
        callbackGeneration = null
        statusValue = statusValue.copy(
            phase = HealthServicesCollectionPhase.ERROR,
            activeChannels = emptySet(),
            message = message,
        )
        return HealthServicesStartResult.Failed(status())
    }
}
