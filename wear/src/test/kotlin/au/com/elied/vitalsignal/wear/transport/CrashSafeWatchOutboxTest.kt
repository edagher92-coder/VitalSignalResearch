package au.com.elied.vitalsignal.wear.transport

import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.DeletionDenialReason
import au.com.elied.vitalsignal.transport.OutboxAcknowledgementDecision
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashSafeWatchOutboxTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun provenanceAndPublicSnapshotCollectionsAreImmutableSnapshots() {
        val sourceIds = mutableListOf("whs:record-1")
        val provenance = WatchBatchProvenance(
            source = WatchBatchSource.WEAR_HEALTH_SERVICES,
            consentGeneration = 1L,
            firstMeasurementEpochMillis = 900L,
            lastMeasurementEpochMillis = 1_000L,
            sourceRecordIds = sourceIds,
        )
        val snapshotItems = mutableListOf<WatchOutboxItem>()
        val snapshot = WatchOutboxSnapshot(0L, null, snapshotItems)

        sourceIds += "whs:record-2"
        assertEquals(listOf("whs:record-1"), provenance.sourceRecordIds)
        assertTrue(snapshot.items.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (provenance.sourceRecordIds as MutableList<String>) += "whs:record-2"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.items as MutableList<WatchOutboxItem>).clear()
        }
    }

    @Test
    fun failedTransferRetriesDeterministicallyAfterRestart() {
        val root = temporaryFolder.newFolder("retry-restart").toPath()
        var clock = 1_000L
        val firstStore = store(root)
        firstStore.installConsentFence(fence(1, clock))
        assertTrue(firstStore.enqueue(envelope("batch-1", 1), provenance(1), clock) is
            WatchOutboxEnqueueResult.Accepted)
        val failedTransport = FakeDataLayerTransport().apply { enqueueShouldFail = true }
        val firstCoordinator = coordinator(firstStore, failedTransport) { clock }

        assertEquals(WatchSendAttemptResult.Started("batch-1", 1), firstCoordinator.attemptNext())
        assertEquals(1, failedTransport.enqueueCalls)

        val reopened = store(root)
        val retryTransport = FakeDataLayerTransport()
        val restartedCoordinator = coordinator(reopened, retryTransport) { clock }
        clock = 5_999L
        assertEquals(WatchSendAttemptResult.NothingDue, restartedCoordinator.attemptNext())
        clock = 6_000L
        assertEquals(WatchSendAttemptResult.Started("batch-1", 2), restartedCoordinator.attemptNext())
        assertEquals(1, retryTransport.enqueueCalls)
        assertEquals(1L, retryTransport.lastConsentGeneration)
        val durable = reopened.snapshot().items.single()
        assertEquals(2, durable.attemptCount)
        assertEquals("/v1/research/batches/batch-1", java.net.URI(durable.dataItem!!.uri).path)
    }

    @Test
    fun queueLimitsAndConsentGenerationFailClosed() {
        val root = temporaryFolder.newFolder("limits-consent").toPath()
        val outbox = store(root, WatchOutboxLimits(maximumRecords = 2))
        assertTrue(outbox.installConsentFence(fence(3, 100L)) is ConsentFenceUpdateResult.Installed)

        assertEquals(
            "consent_generation_mismatch",
            (outbox.enqueue(envelope("old", 1), provenance(2), 1_000L) as
                WatchOutboxEnqueueResult.Rejected).code,
        )
        assertTrue(outbox.enqueue(envelope("batch-1", 1), provenance(3), 1_000L) is
            WatchOutboxEnqueueResult.Accepted)
        assertTrue(outbox.enqueue(envelope("batch-2", 2), provenance(3), 1_000L) is
            WatchOutboxEnqueueResult.Accepted)
        assertEquals(
            "record_limit_reached",
            (outbox.enqueue(envelope("batch-3", 3), provenance(3), 1_000L) as
                WatchOutboxEnqueueResult.Rejected).code,
        )

        assertTrue(outbox.installConsentFence(fence(3, 200L, transfer = false)) is
            ConsentFenceUpdateResult.Installed)
        assertEquals(
            "revoked_generation_cannot_be_reenabled",
            (outbox.installConsentFence(fence(3, 300L, transfer = true)) as
                ConsentFenceUpdateResult.Rejected).reason,
        )
        assertEquals(
            PendingAttemptResult.NothingDue,
            outbox.beginNextAttempt(10_000L, DeterministicRetryPolicy()),
        )
        assertTrue(outbox.installConsentFence(fence(4, 400L, transfer = true)) is
            ConsentFenceUpdateResult.Installed)
        assertEquals(2, outbox.snapshot().staleGenerationCount)
        assertEquals(
            PendingAttemptResult.NothingDue,
            outbox.beginNextAttempt(10_000L, DeterministicRetryPolicy()),
        )
    }

    @Test
    fun oversizedDataItemPayloadIsRejectedBeforeItCanBlockTheDurableQueue() {
        val root = temporaryFolder.newFolder("data-item-budget").toPath()
        val outbox = store(root)
        outbox.installConsentFence(fence(1, 1_000L))

        val result = outbox.enqueue(
            envelope(
                batchId = "oversized-batch",
                sequence = 1,
                payload = ByteArray(WearDataItemPayloadPolicy.MAX_CANONICAL_WIRE_BYTES),
            ),
            provenance(1),
            1_000L,
        )

        assertEquals(
            WearDataItemPayloadPolicy.OVERSIZE_CODE,
            (result as WatchOutboxEnqueueResult.Rejected).code,
        )
        assertTrue(outbox.snapshot().items.isEmpty())
    }

    @Test
    fun onlyExactAuthenticatedAckDeletesAndFailedRemoteDeleteSurvivesRestart() {
        val root = temporaryFolder.newFolder("ack-restart").toPath()
        var clock = 10_000L
        val outbox = store(root)
        outbox.installConsentFence(fence(1, clock))
        outbox.enqueue(envelope("batch-1", 7), provenance(1), clock)
        val firstTransport = FakeDataLayerTransport().apply { removeShouldFail = true }
        val firstCoordinator = coordinator(outbox, firstTransport) { clock }
        firstCoordinator.attemptNext()
        val wire = outbox.snapshot().items.single().canonicalWireCopy()

        val wrong = authenticatedAck(ack(wire).copy(sessionId = "different-session"))
        assertEquals(
            DeletionDenialReason.SESSION_ID_MISMATCH,
            (firstCoordinator.handleAcknowledgement(wrong, 1L) as WatchAcknowledgementResult.Denied).reason,
        )
        assertEquals(0, firstTransport.removeCalls)
        assertEquals(1, outbox.snapshot().items.size)

        assertEquals(
            WatchAcknowledgementResult.NotQueued("consent_generation_mismatch"),
            firstCoordinator.handleAcknowledgement(authenticatedAck(ack(wire)), 2L),
        )
        val exact = firstCoordinator.handleAcknowledgement(authenticatedAck(ack(wire)), 1L)
        assertEquals(WatchAcknowledgementResult.DeletionStarted("batch-1", "receipt-batch-1"), exact)
        assertEquals(1, firstTransport.removeCalls)
        assertEquals("receipt-batch-1", outbox.snapshot().items.single().pendingDeletion?.receiptId)

        val reopened = store(root)
        val recoveredTransport = FakeDataLayerTransport()
        val restarted = coordinator(reopened, recoveredTransport) { clock }
        assertEquals(1, restarted.retryPendingDeletions())
        assertEquals(1, recoveredTransport.removeCalls)
        assertTrue(reopened.snapshot().items.isEmpty())

        assertEquals(
            WatchAcknowledgementResult.NotQueued("batch_not_queued"),
            restarted.handleAcknowledgement(authenticatedAck(ack(wire)), 1L),
        )
    }

    @Test
    fun exactDeletionStoreGuardRejectsWrongDigestAndReceipt() {
        val root = temporaryFolder.newFolder("exact-store-delete").toPath()
        val outbox = store(root)
        outbox.installConsentFence(fence(1, 1_000L))
        outbox.enqueue(envelope("batch-1", 1), provenance(1), 1_000L)
        val item = (outbox.beginNextAttempt(1_000L, DeterministicRetryPolicy()) as
            PendingAttemptResult.Ready).item
        val dataItem = StoredDataItem(
            uri = "wear://node/v1/research/batches/batch-1",
            canonicalWireSha256 = item.canonicalWireSha256,
            queuedAtEpochMillis = 1_001L,
        )
        assertTrue(outbox.markDataItemQueued(item.batchId, item.canonicalWireSha256, dataItem))
        assertTrue(
            outbox.stageExactDeletion(
                item.batchId,
                item.canonicalWireSha256,
                PendingWatchDeletion("receipt-1", "commit-1", ACK_KEY_ID),
            ) is DeletionStagingResult.Staged,
        )

        assertFalse(outbox.deleteExactAuthorized(item.batchId, "f".repeat(64), "receipt-1"))
        assertFalse(outbox.deleteExactAuthorized(item.batchId, item.canonicalWireSha256, "receipt-other"))
        assertEquals(1, outbox.snapshot().items.size)
        assertTrue(outbox.deleteExactAuthorized(item.batchId, item.canonicalWireSha256, "receipt-1"))
        assertTrue(outbox.snapshot().items.isEmpty())
    }

    @Test
    fun authenticatedSnapshotRestartsAndTamperFailsClosed() {
        val root = temporaryFolder.newFolder("tamper").toPath()
        val outbox = store(root)
        outbox.installConsentFence(fence(1, 1_000L))
        outbox.enqueue(envelope("batch-1", 1), provenance(1), 1_000L)
        assertEquals("batch-1", store(root).snapshot().items.single().batchId)

        val snapshot = root.resolve("watch-outbox.vsob")
        val bytes = Files.readAllBytes(snapshot)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(snapshot, bytes, WRITE)

        assertThrows(WatchOutboxRecoveryException::class.java) { store(root) }
    }

    private fun store(
        root: java.nio.file.Path,
        limits: WatchOutboxLimits = WatchOutboxLimits(),
    ) = EncryptedSnapshotWatchOutbox(
        rootDirectory = root,
        secretKey = STORAGE_KEY,
        keyId = STORAGE_KEY_ID,
        secureRandom = SecureRandom(),
        limits = limits,
    )

    private fun coordinator(
        store: DurableWatchOutbox,
        transport: DataLayerBatchTransport,
        now: () -> Long,
    ) = WatchOutboxCoordinator(
        outbox = store,
        transport = transport,
        acknowledgementKeyResolver = AcknowledgementKeyResolver { id -> if (id == ACK_KEY_ID) ACK_KEY else null },
        retryPolicy = DeterministicRetryPolicy(initialDelayMillis = 5_000L, maximumDelayMillis = 20_000L),
        now = now,
    )

    private fun fence(generation: Long, installedAt: Long, transfer: Boolean = true) =
        WatchConsentFence(generation, installedAt, transfer)

    private fun provenance(generation: Long) = WatchBatchProvenance(
        source = WatchBatchSource.WEAR_HEALTH_SERVICES,
        consentGeneration = generation,
        firstMeasurementEpochMillis = 900L,
        lastMeasurementEpochMillis = 1_000L,
        sourceRecordIds = listOf("whs:record-1"),
    )

    private fun envelope(
        batchId: String,
        sequence: Long,
        payload: ByteArray = byteArrayOf(sequence.toByte(), 2, 3),
    ) = BatchEnvelope(
        batchId = batchId,
        sessionId = "session-1",
        deviceId = "watch-ultra2-1",
        sequence = sequence,
        createdAtEpochMillis = 1_000L,
        contentSchemaVersion = 1,
        contentType = "sensor.features",
        payload = payload,
    )

    private fun ack(wire: ByteArray): BatchAcknowledgement {
        val envelope = BatchEnvelopeCodec.decode(wire)
        return BatchAcknowledgement(
            disposition = ReceiptDisposition.ACK,
            reason = ReceiptReason.DURABLY_COMMITTED,
            receiptId = "receipt-${envelope.batchId}",
            batchId = envelope.batchId,
            sessionId = envelope.sessionId,
            sequence = envelope.sequence,
            receivedAtEpochMillis = 20_000L,
            wireSha256Hex = digest(wire),
            durableCommitToken = "commit-${envelope.batchId}",
            quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
            quarantineToken = null,
            detailCode = "durable_commit",
        )
    }

    private fun authenticatedAck(ack: BatchAcknowledgement): ByteArray =
        AuthenticatedAcknowledgementCodec.encode(ack, ACK_KEY_ID, ACK_KEY)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val STORAGE_KEY_ID = "watch-outbox-key-1"
        const val ACK_KEY_ID = "ack-key-1"
        val STORAGE_KEY = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
        val ACK_KEY = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "HmacSHA256")
    }
}

private class FakeDataLayerTransport : DataLayerBatchTransport {
    var enqueueShouldFail: Boolean = false
    var removeShouldFail: Boolean = false
    var enqueueCalls: Int = 0
    var removeCalls: Int = 0
    var lastConsentGeneration: Long? = null

    override fun enqueue(
        envelope: BatchEnvelope,
        consentGeneration: Long,
        onResult: (BatchQueueResult) -> Unit,
    ) {
        enqueueCalls += 1
        lastConsentGeneration = consentGeneration
        if (enqueueShouldFail) {
            onResult(BatchQueueResult.Failed(envelope.batchId, IllegalStateException("offline")))
            return
        }
        val wire = BatchEnvelopeCodec.encode(envelope)
        onResult(
            BatchQueueResult.Queued(
                batchId = envelope.batchId,
                dataItemUri = "wear://node/v1/research/batches/${envelope.batchId}",
                canonicalWireSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(wire)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                consentGeneration = consentGeneration,
            ),
        )
    }

    override fun removeAuthorized(
        queued: BatchQueueResult.Queued,
        authorization: OutboxAcknowledgementDecision.DeletionAuthorized,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        removeCalls += 1
        if (removeShouldFail) onComplete(Result.failure(IllegalStateException("offline")))
        else onComplete(Result.success(Unit))
    }
}
