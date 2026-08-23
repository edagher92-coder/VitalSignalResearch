package au.com.elied.vitalsignal.wear.transport.android

import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import au.com.elied.vitalsignal.wear.transport.WatchAcknowledgementResult
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReceiptAndroidRuntimeTest {
    @Test
    fun messageEventCopiesReceiptBytes() {
        val source = byteArrayOf(1, 2, 3)
        val event = WatchReceiptMessageEvent(RECEIPT_PATH, PHONE_NODE, source)
        source[0] = 99
        val first = event.receiptBytesCopy()
        first[1] = 88

        assertArrayEquals(byteArrayOf(1, 2, 3), event.receiptBytesCopy())
    }

    @Test
    fun runtimeFailsClosedAndCannotBeSilentlyReplaced() {
        val event = WatchReceiptMessageEvent(RECEIPT_PATH, PHONE_NODE, byteArrayOf(1))
        assertEquals(
            WatchReceiptDispatchResult.Rejected("watch_receipt_runtime_unavailable"),
            WatchReceiptAndroidRuntime.dispatch(event),
        )
        val lease = WatchReceiptAndroidRuntime.install {
            WatchReceiptHandlingResult.Rejected("test_rejection")
        }
        requireNotNull(lease)
        try {
            assertNull(
                WatchReceiptAndroidRuntime.install {
                    WatchReceiptHandlingResult.Rejected("replacement_must_not_run")
                },
            )
            assertTrue(WatchReceiptAndroidRuntime.dispatch(event) is WatchReceiptDispatchResult.Processed)
        } finally {
            lease.close()
        }
        assertTrue(WatchReceiptAndroidRuntime.dispatch(event) is WatchReceiptDispatchResult.Rejected)
    }

    @Test
    fun authenticatedHandlerBindsSourcePathAndConsentBeforeProcessor() {
        val encoded = authenticatedAcknowledgement("batch-1")
        var calls = 0
        var observedGeneration = 0L
        var observedWire = byteArrayOf()
        val handler = handler { wire, generation ->
            calls += 1
            observedWire = wire.copyOf()
            observedGeneration = generation
            WatchAcknowledgementResult.NotQueued("test_processor_result")
        }

        val result = handler.handle(WatchReceiptMessageEvent(RECEIPT_PATH, PHONE_NODE, encoded))

        assertEquals(
            WatchReceiptHandlingResult.Processed(
                WatchAcknowledgementResult.NotQueued("test_processor_result"),
            ),
            result,
        )
        assertEquals(1, calls)
        assertEquals(CONSENT_GENERATION, observedGeneration)
        assertArrayEquals(encoded, observedWire)
    }

    @Test
    fun authenticatedHandlerRejectsWrongSourceBeforeAuthenticationOrDeletion() {
        var calls = 0
        val handler = handler { _, _ ->
            calls += 1
            WatchAcknowledgementResult.NotQueued("must_not_run")
        }

        assertEquals(
            WatchReceiptHandlingResult.Rejected("receipt_source_node_mismatch"),
            handler.handle(
                WatchReceiptMessageEvent(RECEIPT_PATH, "other-phone", byteArrayOf(1)),
            ),
        )
        assertEquals(0, calls)
    }

    @Test
    fun authenticatedHandlerRejectsPathBatchMismatchBeforeProcessor() {
        var called = false
        val handler = handler { _, _ ->
            called = true
            WatchAcknowledgementResult.NotQueued("must_not_run")
        }

        assertEquals(
            WatchReceiptHandlingResult.Rejected("receipt_path_batch_mismatch"),
            handler.handle(
                WatchReceiptMessageEvent(
                    "/v1/research/receipts/other-batch",
                    PHONE_NODE,
                    authenticatedAcknowledgement("batch-1"),
                ),
            ),
        )
        assertFalse(called)
    }

    @Test
    fun authenticatedHandlerRejectsInvalidMacBeforeProcessor() {
        val encoded = authenticatedAcknowledgement("batch-1").also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        var called = false
        val handler = handler { _, _ ->
            called = true
            WatchAcknowledgementResult.NotQueued("must_not_run")
        }

        assertEquals(
            WatchReceiptHandlingResult.Rejected("receipt_authentication_failed"),
            handler.handle(WatchReceiptMessageEvent(RECEIPT_PATH, PHONE_NODE, encoded)),
        )
        assertFalse(called)
    }

    private fun handler(
        processor: (ByteArray, Long) -> WatchAcknowledgementResult,
    ) = AuthenticatedOutboxWatchReceiptHandler(
        acknowledgementProcessor = WatchAcknowledgementProcessor(processor),
        acknowledgementKeyResolver = AcknowledgementKeyResolver { id ->
            if (id == ACK_KEY_ID) ACK_KEY else null
        },
        receiptLeaseProvider = WatchReceiptLeaseProvider {
            ActiveWatchReceiptLease(CONSENT_GENERATION, PHONE_NODE)
        },
        testBoundary = Unit,
    )

    private fun authenticatedAcknowledgement(batchId: String): ByteArray =
        AuthenticatedAcknowledgementCodec.encode(
            acknowledgement = BatchAcknowledgement(
                disposition = ReceiptDisposition.ACK,
                reason = ReceiptReason.DURABLY_COMMITTED,
                receiptId = "receipt-$batchId",
                batchId = batchId,
                sessionId = "session-1",
                sequence = 1L,
                receivedAtEpochMillis = 100L,
                wireSha256Hex = "a".repeat(64),
                durableCommitToken = "commit-$batchId",
                quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
                quarantineToken = null,
                detailCode = "committed",
            ),
            keyId = ACK_KEY_ID,
            authenticationKey = ACK_KEY,
        )

    private companion object {
        const val RECEIPT_PATH = "/v1/research/receipts/batch-1"
        const val PHONE_NODE = "phone-node-1"
        const val CONSENT_GENERATION = 4L
        const val ACK_KEY_ID = "ack-key-4"
        val ACK_KEY = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
    }
}
