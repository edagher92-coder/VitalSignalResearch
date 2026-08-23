package au.com.elied.vitalsignal.wear.capture

import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.CollectionMode
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.SensorCatalog
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.security.MessageDigest

class ResearchCaptureConfig(
    val sessionId: String,
    channels: Set<WatchDataChannel>,
    val plannedDurationSeconds: Int,
) {
    val channels: Set<WatchDataChannel> = java.util.Set.copyOf(channels)

    init {
        require(sessionId.isNotBlank())
        require(this.channels.isNotEmpty())
        require(plannedDurationSeconds in 10..(8 * 60 * 60))
        require(this.channels.none { it.mode == CollectionMode.PASSIVE }) {
            "Passive channels belong to PassiveBaselineSource, not a research session"
        }
        val onDemand = this.channels.filter { it.mode == CollectionMode.ON_DEMAND }
        require(onDemand.size <= 1) { "Only one on-demand tracker may run at a time" }
        onDemand.singleOrNull()?.maximumCaptureSeconds?.let { maximum ->
            require(plannedDurationSeconds <= maximum) {
                "On-demand tracker duration cannot exceed $maximum seconds"
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ResearchCaptureConfig &&
        sessionId == other.sessionId && channels == other.channels &&
        plannedDurationSeconds == other.plannedDurationSeconds

    override fun hashCode(): Int = listOf(
        sessionId,
        channels,
        plannedDurationSeconds,
    ).hashCode()

    companion object {
        fun newPilotSession(): ResearchCaptureConfig = ResearchCaptureConfig(
            sessionId = UUID.randomUUID().toString(),
            channels = SensorCatalog.researchDefaults,
            plannedDurationSeconds = 20 * 60,
        )
    }
}

enum class CapturePhase {
    IDLE,
    STARTING,
    ACTIVE,
    STOPPING,
    BLOCKED,
    ERROR,
}

enum class CaptureStorageState {
    NONE,
    SIMULATOR_MEMORY_ONLY,
}

data class CaptureStatus(
    val phase: CapturePhase = CapturePhase.IDLE,
    val sessionId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val packetCount: Long = 0,
    val latestQuality: SignalQuality? = null,
    val storageState: CaptureStorageState = CaptureStorageState.NONE,
    val message: String = "Ready for a configured research session",
) {
    val isRunning: Boolean
        get() = phase == CapturePhase.STARTING ||
            phase == CapturePhase.ACTIVE ||
            phase == CapturePhase.STOPPING
}

class SensorPacket(
    val channel: WatchDataChannel,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val sampleCount: Int,
    encodedPayload: ByteArray,
    val quality: SignalQuality,
) {
    private val immutablePayload = encodedPayload.copyOf()
    val encodedPayloadSizeBytes: Int get() = immutablePayload.size
    val encodedPayloadSha256: String = sha256Hex(immutablePayload)

    init {
        require(startEpochMillis >= 0L)
        require(endEpochMillis >= startEpochMillis)
        require(endEpochMillis - startEpochMillis <= MAX_PACKET_DURATION_MILLIS)
        require(sampleCount in 1..MAX_SAMPLES_PER_PACKET)
        require(immutablePayload.size in 1..MAX_ENCODED_PAYLOAD_BYTES)
    }

    fun encodedPayloadCopy(): ByteArray = immutablePayload.copyOf()

    fun copy(
        channel: WatchDataChannel = this.channel,
        startEpochMillis: Long = this.startEpochMillis,
        endEpochMillis: Long = this.endEpochMillis,
        sampleCount: Int = this.sampleCount,
        encodedPayload: ByteArray = this.immutablePayload,
        quality: SignalQuality = this.quality,
    ) = SensorPacket(
        channel,
        startEpochMillis,
        endEpochMillis,
        sampleCount,
        encodedPayload,
        quality,
    )

    companion object {
        const val MAX_PACKET_DURATION_MILLIS = 60_000L
        const val MAX_SAMPLES_PER_PACKET = 60_000
        const val MAX_ENCODED_PAYLOAD_BYTES = 64 * 1024
    }
}

/**
 * The only surface the licensed Samsung integration must implement. No
 * proprietary type crosses this boundary.
 */
interface SamsungSensorAdapter {
    suspend fun inspectCapabilities(): List<SensorCapability>

    suspend fun start(
        config: ResearchCaptureConfig,
        onPacket: (SensorPacket) -> Unit,
    )

    suspend fun stop()
}

class MissingSamsungSensorAdapter : SamsungSensorAdapter {
    override suspend fun inspectCapabilities(): List<SensorCapability> =
        SensorCatalog.all.map { channel ->
            SensorCapability(
                channel = channel,
                state = CapabilityState.ADAPTER_NOT_INSTALLED,
                detail = "Install the licensed Samsung Health Sensor SDK adapter",
            )
        }

    override suspend fun start(
        config: ResearchCaptureConfig,
        onPacket: (SensorPacket) -> Unit,
    ) {
        error("Samsung sensor adapter is not installed")
    }

    override suspend fun stop() = Unit
}

class SensorAdapterRegistry(
    initial: SamsungSensorAdapter = MissingSamsungSensorAdapter(),
) : SamsungSensorAdapter {
    @Volatile
    private var delegate: SamsungSensorAdapter = initial

    fun install(adapter: SamsungSensorAdapter) {
        delegate = adapter
    }

    override suspend fun inspectCapabilities(): List<SensorCapability> =
        delegate.inspectCapabilities()

    override suspend fun start(
        config: ResearchCaptureConfig,
        onPacket: (SensorPacket) -> Unit,
    ) = delegate.start(config, onPacket)

    override suspend fun stop() = delegate.stop()
}

interface ResearchCaptureController {
    val status: StateFlow<CaptureStatus>

    suspend fun start(config: ResearchCaptureConfig)

    suspend fun stop()
}

class DefaultResearchCaptureController(
    private val adapter: SamsungSensorAdapter,
    private val packetSink: (SensorPacket) -> Unit,
    private val storageState: CaptureStorageState,
    private val now: () -> Long = System::currentTimeMillis,
) : ResearchCaptureController {
    private val operationLock = Mutex()
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    @Volatile
    private var adapterActive = false

    override val status: StateFlow<CaptureStatus> = mutableStatus.asStateFlow()

    override suspend fun start(config: ResearchCaptureConfig) = operationLock.withLock {
        if (mutableStatus.value.isRunning) return@withLock

        mutableStatus.value = CaptureStatus(
            phase = CapturePhase.STARTING,
            sessionId = config.sessionId,
            message = "Checking sensor capability and contact",
        )

        try {
            val capabilities = adapter.inspectCapabilities().associateBy(SensorCapability::channel)
            val unavailable = config.channels.filter { capabilities[it]?.canCollect != true }
            if (unavailable.isNotEmpty()) {
                mutableStatus.value = CaptureStatus(
                    phase = CapturePhase.BLOCKED,
                    sessionId = config.sessionId,
                    message = unavailable.joinToString(
                        prefix = "Unavailable: ",
                        transform = { it.name.lowercase().replace('_', ' ') },
                    ),
                )
                return@withLock
            }

            val startedAt = now()
            mutableStatus.value = CaptureStatus(
                phase = CapturePhase.STARTING,
                sessionId = config.sessionId,
                startedAtEpochMillis = startedAt,
                storageState = storageState,
                message = "Opening configured trackers",
            )
            adapterActive = true
            adapter.start(config) { packet ->
                packetSink(packet)
                mutableStatus.update { current -> current.copy(
                    packetCount = current.packetCount + 1L,
                    latestQuality = packet.quality,
                ) }
            }
            mutableStatus.update { current -> current.copy(
                phase = CapturePhase.ACTIVE,
                message = "Simulated high-fidelity research capture",
            ) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (adapterActive) runCatching { adapter.stop() }
            adapterActive = false
            mutableStatus.value = CaptureStatus(
                phase = CapturePhase.ERROR,
                sessionId = config.sessionId,
                storageState = storageState,
                message = error.message ?: "Sensor capture could not start",
            )
        }
    }

    override suspend fun stop() = operationLock.withLock {
        if (!mutableStatus.value.isRunning && !adapterActive) return@withLock
        val current = mutableStatus.value
        mutableStatus.value = current.copy(
            phase = CapturePhase.STOPPING,
            message = "Closing sensor trackers safely",
        )
        try {
            if (adapterActive) adapter.stop()
            adapterActive = false
            mutableStatus.value = CaptureStatus(
                packetCount = current.packetCount,
                latestQuality = current.latestQuality,
                storageState = storageState,
                message = if (storageState == CaptureStorageState.SIMULATOR_MEMORY_ONLY) {
                    "Session ended · ${current.packetCount} packets held in simulator memory"
                } else {
                    "Session ended · packet collection stopped"
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            adapterActive = false
            mutableStatus.value = current.copy(
                phase = CapturePhase.ERROR,
                message = error.message ?: "Sensor capture could not stop cleanly",
            )
        }
    }
}

object ResearchCaptureRuntime {
    const val isSimulationMode: Boolean = true
    val packetBuffer = InMemorySimulatorPacketBuffer()
    val adapters = SensorAdapterRegistry(SimulatedSamsungSensorAdapter())

    val controller: ResearchCaptureController = DefaultResearchCaptureController(
        adapter = adapters,
        packetSink = packetBuffer::append,
        storageState = CaptureStorageState.SIMULATOR_MEMORY_ONLY,
    )
}

private fun sha256Hex(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(payload)
    .joinToString("") { "%02x".format(it) }
