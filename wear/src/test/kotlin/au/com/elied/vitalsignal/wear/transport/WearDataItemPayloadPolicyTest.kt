package au.com.elied.vitalsignal.wear.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WearDataItemPayloadPolicyTest {
    @Test
    fun `empty and small canonical payloads fit the DataItem budget`() {
        assertNull(WearDataItemPayloadPolicy.rejectionCode(0))
        assertNull(WearDataItemPayloadPolicy.rejectionCode(12_345))
    }

    @Test
    fun `exact budget boundary is accepted`() {
        assertNull(
            WearDataItemPayloadPolicy.rejectionCode(
                WearDataItemPayloadPolicy.MAX_CANONICAL_WIRE_BYTES,
            ),
        )
    }

    @Test
    fun `one byte over budget is rejected with stable code`() {
        assertEquals(
            WearDataItemPayloadPolicy.OVERSIZE_CODE,
            WearDataItemPayloadPolicy.rejectionCode(
                WearDataItemPayloadPolicy.MAX_CANONICAL_WIRE_BYTES + 1,
            ),
        )
    }

    @Test
    fun `negative size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WearDataItemPayloadPolicy.rejectionCode(-1)
        }
    }
}
