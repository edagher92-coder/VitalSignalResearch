package au.com.elied.vitalsignal.phone.data.bridge.android

import au.com.elied.vitalsignal.phone.data.bridge.AuthenticatedReceiptCommand
import au.com.elied.vitalsignal.phone.data.bridge.DataLayerBatchEvent
import au.com.elied.vitalsignal.phone.data.bridge.PhoneBridgeProcessingResult
import au.com.elied.vitalsignal.phone.data.bridge.ReceiptPublishResult
import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataLayerAndroidRuntimeTest {
    @Test
    fun runtimeFailsClosedUntilExactHandlerIsInstalled() {
        val event = batchEvent(byteArrayOf(1, 2, 3))
        assertEquals(
            PhoneDataLayerDispatchResult.Rejected("phone_bridge_runtime_unavailable"),
            PhoneDataLayerAndroidRuntime.dispatch(event),
        )

        var observed: DataLayerBatchEvent? = null
        val lease = PhoneDataLayerAndroidRuntime.install(
            handler = {
                observed = it
                PhoneBridgeProcessingResult.Ignored()
            },
            receiptRecoveryRequestor = recoveryRequestor(),
        )
        requireNotNull(lease)
        try {
            assertNull(
                PhoneDataLayerAndroidRuntime.install(
                    handler = { PhoneBridgeProcessingResult.Ignored("replacement_must_not_run") },
                    receiptRecoveryRequestor = recoveryRequestor(),
                ),
            )
            assertTrue(PhoneDataLayerAndroidRuntime.dispatch(event) is PhoneDataLayerDispatchResult.Processed)
            assertEquals(event, observed)
        } finally {
            lease.close()
        }

        assertTrue(
            PhoneDataLayerAndroidRuntime.dispatch(event) is PhoneDataLayerDispatchResult.Rejected,
        )
    }

    @Test
    fun runtimeContainsHandlerExceptions() {
        val lease = PhoneDataLayerAndroidRuntime.install(
            handler = { throw IllegalStateException("no leak") },
            receiptRecoveryRequestor = recoveryRequestor(),
        )
        requireNotNull(lease)
        try {
            assertEquals(
                PhoneDataLayerDispatchResult.Rejected("phone_bridge_runtime_exception"),
                PhoneDataLayerAndroidRuntime.dispatch(batchEvent(byteArrayOf(9))),
            )
        } finally {
            lease.close()
        }
    }

    @Test
    fun staleLeaseCannotCloseNewRegistrationOfSameHandler() {
        val handler = PhoneDataLayerBatchHandler { PhoneBridgeProcessingResult.Ignored() }
        val firstLease = requireNotNull(PhoneDataLayerAndroidRuntime.install(handler, recoveryRequestor()))
        firstLease.close()
        val secondLease = requireNotNull(PhoneDataLayerAndroidRuntime.install(handler, recoveryRequestor()))
        try {
            firstLease.close()
            assertTrue(
                PhoneDataLayerAndroidRuntime.dispatch(batchEvent(byteArrayOf(4))) is
                    PhoneDataLayerDispatchResult.Processed,
            )
        } finally {
            secondLease.close()
        }
    }

    @Test
    fun dataMapFieldNamesAreExactWireContract() {
        assertEquals("consent_generation", PhoneDataLayerDataMapContract.CONSENT_GENERATION_KEY)
        assertEquals(
            "canonical_batch_envelope",
            PhoneDataLayerDataMapContract.CANONICAL_WIRE_KEY,
        )
    }

    @Test
    fun installAndPendingDispatchRequestExplicitReceiptRecovery() {
        val reasons = mutableListOf<PhoneReceiptRecoveryReason>()
        val requestor = PhoneReceiptRecoveryRequestor { reason ->
            reasons += reason
            PhoneReceiptRecoveryRequestResult.Requested("request-${reasons.size}")
        }
        val lease = requireNotNull(
            PhoneDataLayerAndroidRuntime.install(
                handler = {
                    PhoneBridgeProcessingResult.ReceiptDeliveryPending(
                        acknowledgement = acknowledgement(),
                        command = null,
                        detailCode = "simulated_pending",
                    )
                },
                receiptRecoveryRequestor = requestor,
            ),
        )
        try {
            assertEquals(
                PhoneReceiptRecoveryRequestResult.Requested("request-1"),
                lease.initialRecoveryRequestResult,
            )
            val dispatched = PhoneDataLayerAndroidRuntime.dispatch(batchEvent(byteArrayOf(1)))
            assertTrue(dispatched is PhoneDataLayerDispatchResult.Processed)
            dispatched as PhoneDataLayerDispatchResult.Processed
            assertEquals(
                PhoneReceiptRecoveryRequestResult.Requested("request-2"),
                dispatched.receiptRecoveryRequestResult,
            )
            assertEquals(
                listOf(
                    PhoneReceiptRecoveryReason.PROCESS_RUNTIME_INSTALLED,
                    PhoneReceiptRecoveryReason.RECEIPT_DELIVERY_PENDING,
                ),
                reasons,
            )
        } finally {
            lease.close()
        }
    }

    @Test
    fun boundedPublisherUsesExactCommandAndDefensiveBytes() {
        val source = byteArrayOf(7, 8, 9)
        val command = command(source)
        source[0] = 99
        var observedNode = ""
        var observedPath = ""
        var observedTimeout = -1L
        var observedPayload = byteArrayOf()
        val engine = BoundedReceiptPublishEngine(
            timeoutMillis = 2_000L,
            isMainThread = { false },
            sender = { node, path, payload, timeout ->
                observedNode = node
                observedPath = path
                observedPayload = payload.copyOf()
                payload[0] = 44
                observedTimeout = timeout
                42
            },
        )

        assertEquals(ReceiptPublishResult.Published("message-42"), engine.publish(command))
        assertEquals("phone-node-1", observedNode)
        assertEquals("/v1/research/receipts/batch-1", observedPath)
        assertEquals(2_000L, observedTimeout)
        assertArrayEquals(byteArrayOf(7, 8, 9), observedPayload)
        assertArrayEquals(byteArrayOf(7, 8, 9), command.authenticatedReceiptBytesCopy())
    }

    @Test
    fun boundedPublisherRejectsMainThreadWithoutSending() {
        var called = false
        val engine = BoundedReceiptPublishEngine(
            timeoutMillis = 2_000L,
            isMainThread = { true },
            sender = { _, _, _, _ -> called = true; 1 },
        )

        assertEquals(
            ReceiptPublishResult.Failed("main_thread_publish_rejected"),
            engine.publish(command(byteArrayOf(1))),
        )
        assertFalse(called)
    }

    @Test
    fun boundedPublisherClassifiesTimeout() {
        val engine = BoundedReceiptPublishEngine(
            timeoutMillis = 2_000L,
            isMainThread = { false },
            sender = { _, _, _, _ -> throw TimeoutException("bounded") },
        )

        assertEquals(
            ReceiptPublishResult.Failed("receipt_publish_timeout"),
            engine.publish(command(byteArrayOf(1))),
        )
    }

    private fun batchEvent(bytes: ByteArray) = DataLayerBatchEvent(
        path = "/v1/research/batches/batch-1",
        sourceNodeId = "watch-node-1",
        receivedAtEpochMillis = 100L,
        consentGeneration = 3L,
        wireBytes = bytes,
    )

    private fun command(bytes: ByteArray) = AuthenticatedReceiptCommand(
        targetNodeId = "phone-node-1",
        path = "/v1/research/receipts/batch-1",
        consentGeneration = 3L,
        authenticatedReceiptBytes = bytes,
    )

    private fun recoveryRequestor() = PhoneReceiptRecoveryRequestor { reason ->
        PhoneReceiptRecoveryRequestResult.Requested("request-${reason.name.lowercase()}")
    }

    private fun acknowledgement() = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = ReceiptReason.DURABLY_COMMITTED,
        receiptId = "receipt-1",
        batchId = "batch-1",
        sessionId = "session-1",
        sequence = 1L,
        receivedAtEpochMillis = 100L,
        wireSha256Hex = "a".repeat(64),
        durableCommitToken = "commit-1",
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = "durable_commit",
    )
}
