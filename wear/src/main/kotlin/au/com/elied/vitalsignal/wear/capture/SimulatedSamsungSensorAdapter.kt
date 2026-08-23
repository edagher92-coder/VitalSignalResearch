package au.com.elied.vitalsignal.wear.capture

import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.SensorCatalog
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import java.nio.ByteBuffer
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Deterministic watch-side source for emulator and UX testing. */
class SimulatedSamsungSensorAdapter(
    private val now: () -> Long = System::currentTimeMillis,
) : SamsungSensorAdapter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    override suspend fun inspectCapabilities(): List<SensorCapability> =
        SensorCatalog.all.map { channel ->
            if (channel in SensorCatalog.researchDefaults) {
                SensorCapability(
                    channel = channel,
                    state = CapabilityState.AVAILABLE,
                    detail = "Deterministic simulator channel · no hardware sensor",
                )
            } else {
                SensorCapability(
                    channel = channel,
                    state = CapabilityState.UNSUPPORTED_DEVICE,
                    detail = "Not enabled in the simulator fixture",
                )
            }
        }

    override suspend fun start(
        config: ResearchCaptureConfig,
        onPacket: (SensorPacket) -> Unit,
    ) {
        check(captureJob?.isActive != true) { "Simulator capture is already active" }
        captureJob = scope.launch {
            var sequence = 0L
            while (currentCoroutineContext().isActive) {
                val end = now()
                config.channels.sortedBy(WatchDataChannel::name).forEach { channel ->
                    val sampleCount = channel.nominalSampleRateHz ?: 1
                    onPacket(
                        SensorPacket(
                            channel = channel,
                            startEpochMillis = end - 1_000L,
                            endEpochMillis = end,
                            sampleCount = sampleCount,
                            encodedPayload = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
                                .putLong(sequence++)
                                .putLong(channel.ordinal.toLong())
                                .array(),
                            quality = SIMULATOR_QUALITY,
                        ),
                    )
                }
                delay(1_000L)
            }
        }
    }

    override suspend fun stop() {
        captureJob?.cancelAndJoin()
        captureJob = null
    }

    private companion object {
        val SIMULATOR_QUALITY = SignalQuality(
            score = 0.96,
            coverage = 0.98,
            contact = 0.97,
            motionContamination = 0.03,
            validity = 0.98,
            clipping = 0.01,
            timestampContinuity = 0.98,
            reasons = listOf("simulator fixture; not a sensor measurement"),
            evaluatorVersion = "watch-simulator-v2",
        )
    }
}

/** Volatile packet sink. Its name and state prevent it being mistaken for durable storage. */
class InMemorySimulatorPacketBuffer {
    private val packets = Collections.synchronizedList(mutableListOf<SensorPacket>())

    fun append(packet: SensorPacket) {
        packets += packet.copy(encodedPayload = packet.encodedPayloadCopy())
    }

    fun snapshot(): List<SensorPacket> = synchronized(packets) {
        packets.map { it.copy(encodedPayload = it.encodedPayloadCopy()) }
    }

    fun clear() = packets.clear()
}
