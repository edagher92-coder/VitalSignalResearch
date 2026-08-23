package au.com.elied.vitalsignal.phone.data.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataLayerBatchEventTest {
    @Test
    fun canonicalWireBytesAreSnapshottedAndNeverExposedMutable() {
        val callerBytes = byteArrayOf(1, 2, 3, 4)
        val event = event(callerBytes)
        callerBytes.fill(9)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), event.wireBytesCopy())
        val exported = event.wireBytesCopy()
        exported.fill(8)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), event.wireBytesCopy())
        assertEquals(4, event.wireSizeBytes)
    }

    @Test
    fun emptyAndOversizeCanonicalEnvelopesAreRejectedBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) { event(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            event(ByteArray(DataLayerBatchEvent.MAX_CANONICAL_WIRE_BYTES + 1))
        }
    }

    @Test
    fun ingressMetadataHasFiniteUtf8Budgets() {
        assertThrows(IllegalArgumentException::class.java) {
            DataLayerBatchEvent(
                path = "/" + "p".repeat(DataLayerBatchEvent.MAX_PATH_BYTES),
                sourceNodeId = "watch-node",
                receivedAtEpochMillis = 1L,
                consentGeneration = 1L,
                wireBytes = byteArrayOf(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataLayerBatchEvent(
                path = "/v1/research/batches/one",
                sourceNodeId = "n".repeat(DataLayerBatchEvent.MAX_NODE_ID_BYTES + 1),
                receivedAtEpochMillis = 1L,
                consentGeneration = 1L,
                wireBytes = byteArrayOf(1),
            )
        }
    }

    private fun event(bytes: ByteArray) = DataLayerBatchEvent(
        path = "/v1/research/batches/one",
        sourceNodeId = "watch-node",
        receivedAtEpochMillis = 1L,
        consentGeneration = 1L,
        wireBytes = bytes,
    )
}
