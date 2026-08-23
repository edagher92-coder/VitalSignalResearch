package au.com.elied.vitalsignal.wear.capture

import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCaptureControllerTest {
    @Test
    fun simulatorPacketsAreBufferedAndNeverCalledDurablySaved() = runBlocking {
        val buffer = InMemorySimulatorPacketBuffer()
        val controller = DefaultResearchCaptureController(
            adapter = SimulatedSamsungSensorAdapter(now = { 10_000L }),
            packetSink = buffer::append,
            storageState = CaptureStorageState.SIMULATOR_MEMORY_ONLY,
            now = { 10_000L },
        )
        val config = ResearchCaptureConfig(
            sessionId = "sim-session",
            channels = setOf(WatchDataChannel.HEART_RATE_AND_IBI),
            plannedDurationSeconds = 10,
        )

        controller.start(config)
        withTimeout(1_000L) {
            while (buffer.snapshot().isEmpty()) delay(10L)
        }
        controller.stop()

        assertTrue(buffer.snapshot().isNotEmpty())
        assertTrue(controller.status.value.packetCount > 0)
        assertEquals(CaptureStorageState.SIMULATOR_MEMORY_ONLY, controller.status.value.storageState)
        assertTrue(controller.status.value.message.contains("simulator memory"))
        assertTrue(!controller.status.value.message.contains("saved", ignoreCase = true))
    }

    @Test
    fun configurationRejectsConcurrentOnDemandTrackers() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchCaptureConfig(
                sessionId = "invalid-session",
                channels = setOf(WatchDataChannel.ECG, WatchDataChannel.BIA),
                plannedDurationSeconds = 30,
            )
        }
    }

    @Test
    fun configurationAndCatalogChannelsCannotBeMutatedAfterValidation() {
        val callerChannels = mutableSetOf(WatchDataChannel.HEART_RATE_AND_IBI)
        val config = ResearchCaptureConfig(
            sessionId = "immutable-session",
            channels = callerChannels,
            plannedDurationSeconds = 30,
        )

        callerChannels += WatchDataChannel.ECG
        assertEquals(setOf(WatchDataChannel.HEART_RATE_AND_IBI), config.channels)

        assertThrows(UnsupportedOperationException::class.java) {
            (config.channels as MutableSet<WatchDataChannel>) += WatchDataChannel.ECG
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (au.com.elied.vitalsignal.wear.sensor.SensorCatalog.researchDefaults as
                MutableSet<WatchDataChannel>).clear()
        }
        assertEquals(setOf(WatchDataChannel.HEART_RATE_AND_IBI), config.channels)
        assertTrue(
            au.com.elied.vitalsignal.wear.sensor.SensorCatalog.researchDefaults.contains(
                WatchDataChannel.HEART_RATE_AND_IBI,
            ),
        )
    }

    @Test
    fun sensorPacketBoundsAndDefensivePayloadPreventMutableOrOversizedIngress() {
        val source = byteArrayOf(1, 2, 3)
        val packet = SensorPacket(
            channel = WatchDataChannel.HEART_RATE_AND_IBI,
            startEpochMillis = 1_000L,
            endEpochMillis = 2_000L,
            sampleCount = 1,
            encodedPayload = source,
            quality = SignalQuality(0.95),
        )
        val digest = packet.encodedPayloadSha256
        source[0] = 99
        packet.encodedPayloadCopy()[1] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3), packet.encodedPayloadCopy())
        assertEquals(digest, packet.encodedPayloadSha256)

        assertThrows(IllegalArgumentException::class.java) {
            packet.copy(encodedPayload = ByteArray(SensorPacket.MAX_ENCODED_PAYLOAD_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            packet.copy(sampleCount = SensorPacket.MAX_SAMPLES_PER_PACKET + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            packet.copy(endEpochMillis = packet.startEpochMillis + SensorPacket.MAX_PACKET_DURATION_MILLIS + 1L)
        }
    }
}
