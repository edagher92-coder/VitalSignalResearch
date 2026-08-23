package au.com.elied.vitalsignal.phone.data.bridge

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementResult
import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashSafeReceiptDeliveryOutboxTest {
    @Test
    fun publisherFailureIsEncryptedAndRecoveredAfterProcessShapedRestart() {
        val root = Files.createTempDirectory("receipt-restart")
        val leaseProvider = MutableReceiptLeaseProvider(lease())
        val failedPublisher = ReceiptTestPublisher(alwaysFail = true)
        val first = coordinator(root, leaseProvider, failedPublisher)

        val staged = first.stageAndAttempt(lease(), acknowledgement(), NOW)

        assertTrue(staged is PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        staged as PhoneBridgeProcessingResult.ReceiptDeliveryPending
        assertEquals("simulated_radio_failure", staged.detailCode)
        assertEquals(1, failedPublisher.commands.size)

        val recoveredPublisher = ReceiptTestPublisher()
        val reopened = coordinator(root, leaseProvider, recoveredPublisher)
        val tooEarly = reopened.recoverAndRetry(NOW + 999L)
        assertTrue(tooEarly is ReceiptRecoveryRunResult.Completed)
        tooEarly as ReceiptRecoveryRunResult.Completed
        assertTrue(tooEarly.results.isEmpty())
        assertEquals(NOW + 1_000L, tooEarly.nextEligibleAtEpochMillis)
        assertTrue(recoveredPublisher.commands.isEmpty())

        val retried = reopened.recoverAndRetry(NOW + 1_000L)
        assertTrue(retried is ReceiptRecoveryRunResult.Completed)
        val result = (retried as ReceiptRecoveryRunResult.Completed).results.single()
        assertTrue(result is PhoneBridgeProcessingResult.AckPublished)
        assertEquals(1, recoveredPublisher.commands.size)
        assertExactAuthenticatedCommand(recoveredPublisher.commands.single(), acknowledgement())

        val finalRecovery = outbox(root).recover()
        assertTrue(finalRecovery is ReceiptOutboxRecovery.Ready)
        finalRecovery as ReceiptOutboxRecovery.Ready
        assertTrue(finalRecovery.pending.isEmpty())
        assertEquals(ReceiptDeliveryState.DELIVERED, finalRecovery.entries.single().state)
    }

    @Test
    fun missingAcknowledgementKeyRemainsPendingUntilKeyRecovery() {
        val root = Files.createTempDirectory("receipt-key-recovery")
        val leaseProvider = MutableReceiptLeaseProvider(lease())
        var keyAvailable = false
        val publisher = ReceiptTestPublisher()
        val delivery = CrashSafeReceiptDeliveryCoordinator(
            outbox(root),
            leaseProvider,
            BridgeAcknowledgementKeyResolver { keyId ->
                ACK_KEY.takeIf { keyAvailable && keyId == ACK_KEY_ID }
            },
            publisher,
        )

        val first = delivery.stageAndAttempt(lease(), acknowledgement(), NOW)
        assertTrue(first is PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        assertEquals("ack_key_unavailable", (first as PhoneBridgeProcessingResult.ReceiptDeliveryPending).detailCode)
        assertTrue(publisher.commands.isEmpty())

        keyAvailable = true
        val recovered = delivery.recoverAndRetry(NOW + 1_000L) as ReceiptRecoveryRunResult.Completed
        assertTrue(recovered.results.single() is PhoneBridgeProcessingResult.AckPublished)
        assertEquals(1, publisher.commands.size)
    }

    @Test
    fun crashShapedGapAfterRadioSendBeforeDeliveredMarkerRetriesAtLeastOnce() {
        val root = Files.createTempDirectory("receipt-post-send-crash")
        val publisher = ReceiptTestPublisher()
        val durableOutbox = outbox(root)
        val first = CrashSafeReceiptDeliveryCoordinator(
            RejectDeliveredMarkerOutbox(durableOutbox),
            MutableReceiptLeaseProvider(lease()),
            BridgeAcknowledgementKeyResolver { ACK_KEY },
            publisher,
        )

        val uncertain = first.stageAndAttempt(lease(), acknowledgement(), NOW)
        assertTrue(uncertain is PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        assertEquals(
            "receipt_delivery_state_unknown",
            (uncertain as PhoneBridgeProcessingResult.ReceiptDeliveryPending).detailCode,
        )
        assertEquals(1, publisher.commands.size)

        val restarted = coordinator(root, MutableReceiptLeaseProvider(lease()), publisher)
        val recovered = restarted.recoverAndRetry(NOW + 1L) as ReceiptRecoveryRunResult.Completed
        assertTrue(recovered.results.single() is PhoneBridgeProcessingResult.AckPublished)
        assertEquals(2, publisher.commands.size)
        assertEquals(ReceiptDeliveryState.DELIVERED,
            (outbox(root).recover() as ReceiptOutboxRecovery.Ready).entries.single().state)
    }

    @Test
    fun consentRotationTerminatesStaleGenerationWithoutPublishing() {
        val root = Files.createTempDirectory("receipt-stale-consent")
        val leaseProvider = MutableReceiptLeaseProvider(lease())
        val publisher = ReceiptTestPublisher(alwaysFail = true)
        val first = coordinator(root, leaseProvider, publisher)
        assertTrue(first.stageAndAttempt(lease(), acknowledgement(), NOW) is
            PhoneBridgeProcessingResult.ReceiptDeliveryPending)

        leaseProvider.lease = lease().copy(
            consentGeneration = GENERATION + 1L,
            transportKeyId = "transport-generation-8",
            acknowledgementKeyId = "ack-generation-8",
        )
        publisher.alwaysFail = false
        val recovered = first.recoverAndRetry(NOW + 1_000L) as ReceiptRecoveryRunResult.Completed

        val result = recovered.results.single()
        assertTrue(result is PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned)
        assertEquals("receipt_consent_stale", (result as PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned).detailCode)
        assertEquals(1, publisher.commands.size)
        val state = (outbox(root).recover() as ReceiptOutboxRecovery.Ready).entries.single()
        assertEquals(ReceiptDeliveryState.STALE_CONSENT, state.state)
    }

    @Test
    fun publisherFailuresHaveFiniteAttemptsAndDoNotRetryAfterExhaustion() {
        val root = Files.createTempDirectory("receipt-attempt-bound")
        val bounds = ReceiptDeliveryBounds(
            maximumPendingEntries = 4,
            maximumJournalRecords = 16,
            maximumAttemptsPerEntry = 2,
            initialRetryDelayMillis = 250L,
            maximumRetryDelayMillis = 250L,
        )
        val publisher = ReceiptTestPublisher(alwaysFail = true)
        val delivery = coordinator(root, MutableReceiptLeaseProvider(lease()), publisher, bounds)

        assertTrue(delivery.stageAndAttempt(lease(), acknowledgement(), NOW) is
            PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        val exhausted = delivery.recoverAndRetry(NOW + 250L) as ReceiptRecoveryRunResult.Completed
        assertTrue(exhausted.results.single() is PhoneBridgeProcessingResult.ReceiptDeliveryAbandoned)
        val after = delivery.recoverAndRetry(NOW + 10_000L) as ReceiptRecoveryRunResult.Completed

        assertTrue(after.results.isEmpty())
        assertEquals(2, publisher.commands.size)
        val state = (outbox(root, bounds).recover() as ReceiptOutboxRecovery.Ready).entries.single()
        assertEquals(ReceiptDeliveryState.RETRY_EXHAUSTED, state.state)
        assertEquals(2, state.attemptCount)
    }

    @Test
    fun duplicateStageIsIdempotentAndExportedBytesCannotMutateJournalState() {
        val root = Files.createTempDirectory("receipt-duplicate")
        val outbox = outbox(root)
        val binding = ReceiptDeliveryBinding.create(lease(), acknowledgement(), NOW)
        val originalWire = binding.acknowledgementWireCopy()

        val first = outbox.stage(binding)
        val exported = binding.acknowledgementWireCopy()
        exported.fill(0)
        val duplicate = outbox.stage(binding)

        assertTrue(first is ReceiptOutboxStageResult.Staged)
        assertTrue((first as ReceiptOutboxStageResult.Staged).newlyAppended)
        assertTrue(duplicate is ReceiptOutboxStageResult.Staged)
        assertFalse((duplicate as ReceiptOutboxStageResult.Staged).newlyAppended)
        assertArrayEquals(originalWire, duplicate.entry.binding.acknowledgementWireCopy())
        val recovered = outbox.recover() as ReceiptOutboxRecovery.Ready
        assertEquals(1, recovered.journalRecordCount)
        assertEquals(1, recovered.pending.size)
    }

    @Test
    fun alreadyDeliveredExactStageDoesNotRepublish() {
        val root = Files.createTempDirectory("receipt-delivered-duplicate")
        val publisher = ReceiptTestPublisher()
        val coordinator = coordinator(root, MutableReceiptLeaseProvider(lease()), publisher)

        val first = coordinator.stageAndAttempt(lease(), acknowledgement(), NOW)
        val duplicate = coordinator.stageAndAttempt(lease(), acknowledgement(), NOW)

        assertTrue(first is PhoneBridgeProcessingResult.AckPublished)
        assertTrue(duplicate is PhoneBridgeProcessingResult.AckPublished)
        assertEquals(1, publisher.commands.size)
    }

    @Test
    fun pendingAndJournalBoundsFailClosedWithoutPlaintextFallback() {
        val pendingRoot = Files.createTempDirectory("receipt-pending-bound")
        val bounds = ReceiptDeliveryBounds(
            maximumPendingEntries = 1,
            maximumJournalRecords = 4,
            maximumAttemptsPerEntry = 2,
            initialRetryDelayMillis = 250L,
            maximumRetryDelayMillis = 250L,
        )
        val pendingOutbox = outbox(pendingRoot, bounds)
        assertTrue(pendingOutbox.stage(ReceiptDeliveryBinding.create(lease(), acknowledgement("batch-1"), NOW)) is
            ReceiptOutboxStageResult.Staged)
        val overflow = pendingOutbox.stage(
            ReceiptDeliveryBinding.create(lease(), acknowledgement("batch-2"), NOW + 1L),
        )
        assertEquals(
            ReceiptOutboxStageResult.Rejected("receipt_outbox_pending_bound"),
            overflow,
        )

        val journalRoot = Files.createTempDirectory("receipt-journal-bound")
        val journalBounds = bounds.copy(maximumJournalRecords = 1)
        val journalOutbox = outbox(journalRoot, journalBounds)
        val binding = ReceiptDeliveryBinding.create(lease(), acknowledgement("batch-bound"), NOW)
        assertTrue(journalOutbox.stage(binding) is ReceiptOutboxStageResult.Staged)
        assertEquals(
            ReceiptOutboxTransitionResult.Rejected("receipt_outbox_journal_bound"),
            journalOutbox.recordFailure(binding.deliveryId, NOW, "simulated_failure"),
        )
        assertEquals(1, (journalOutbox.recover() as ReceiptOutboxRecovery.Ready).pending.size)
    }

    @Test
    fun durableStageFailureIsExplicitAndPreventsVolatilePublish() {
        val publisher = ReceiptTestPublisher()
        val coordinator = CrashSafeReceiptDeliveryCoordinator(
            outbox = RejectStageOutbox,
            consentLeaseProvider = MutableReceiptLeaseProvider(lease()),
            acknowledgementKeyResolver = BridgeAcknowledgementKeyResolver { ACK_KEY },
            receiptPublisher = publisher,
        )

        val result = coordinator.stageAndAttempt(lease(), acknowledgement(), NOW)

        assertTrue(result is PhoneBridgeProcessingResult.ReceiptDeliveryUnavailable)
        assertEquals(
            "simulated_stage_failure",
            (result as PhoneBridgeProcessingResult.ReceiptDeliveryUnavailable).detailCode,
        )
        assertTrue(publisher.commands.isEmpty())
    }

    @Test
    fun ciphertextMutationBlocksRecoveryAndFutureDelivery() {
        val root = Files.createTempDirectory("receipt-ciphertext-mutation")
        val outbox = outbox(root)
        outbox.stage(ReceiptDeliveryBinding.create(lease(), acknowledgement(), NOW))
        val committed = Files.list(root.resolve("records")).use { files ->
            files.filter { it.fileName.toString().endsWith(".vsr") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(committed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(committed, bytes)

        val recovered = outbox(root).recover()
        assertTrue(recovered is ReceiptOutboxRecovery.RecoveryRequired)
        assertEquals(
            "receipt_outbox_storage_quarantined",
            (recovered as ReceiptOutboxRecovery.RecoveryRequired).detailCode,
        )
    }

    @Test
    fun encryptedJournalDoesNotExposeReceiptIdentityOrTokensAsPlaintext() {
        val root = Files.createTempDirectory("receipt-plaintext-leak")
        val sensitiveSession = "session-unique-sensitive-84291"
        val sensitiveToken = "commit-unique-sensitive-97531"
        val acknowledgement = acknowledgement("batch-private").copy(
            sessionId = sensitiveSession,
            durableCommitToken = sensitiveToken,
        )
        outbox(root).stage(ReceiptDeliveryBinding.create(lease(), acknowledgement, NOW))

        val committedBytes = Files.list(root.resolve("records")).use { files ->
            files.filter { it.fileName.toString().endsWith(".vsr") }
                .map { Files.readAllBytes(it) }
                .toList()
        }
        assertEquals(1, committedBytes.size)
        val ciphertextText = String(committedBytes.single(), Charsets.ISO_8859_1)
        assertFalse(ciphertextText.contains(sensitiveSession))
        assertFalse(ciphertextText.contains(sensitiveToken))
    }

    private fun coordinator(
        root: Path,
        leaseProvider: MutableReceiptLeaseProvider,
        publisher: ReceiptTestPublisher,
        bounds: ReceiptDeliveryBounds = ReceiptDeliveryBounds(),
    ) = CrashSafeReceiptDeliveryCoordinator(
        outbox(root, bounds),
        leaseProvider,
        BridgeAcknowledgementKeyResolver { keyId -> ACK_KEY.takeIf { keyId == ACK_KEY_ID } },
        publisher,
    )

    private fun outbox(
        root: Path,
        bounds: ReceiptDeliveryBounds = ReceiptDeliveryBounds(),
    ) = EncryptedAppendOnlyReceiptDeliveryOutbox(
        EncryptedAppendOnlyRecordStore(
            root,
            STORAGE_KEY,
            STORAGE_KEY_ID,
            SecureRandom(),
            maxPayloadBytes = 24 * 1024,
        ),
        bounds,
    )

    private fun assertExactAuthenticatedCommand(
        command: AuthenticatedReceiptCommand,
        expected: BatchAcknowledgement,
    ) {
        assertEquals(WATCH_NODE, command.targetNodeId)
        assertEquals("/v1/research/receipts/${expected.batchId}", command.path)
        assertEquals(GENERATION, command.consentGeneration)
        val decoded = AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(
            command.authenticatedReceiptBytesCopy(),
            AcknowledgementKeyResolver { keyId -> ACK_KEY.takeIf { keyId == ACK_KEY_ID } },
        )
        assertTrue(decoded is AuthenticatedAcknowledgementResult.Authenticated)
        decoded as AuthenticatedAcknowledgementResult.Authenticated
        assertEquals(ACK_KEY_ID, decoded.keyId)
        assertEquals(expected, decoded.acknowledgement)
    }

    private fun acknowledgement(batchId: String = "batch-1") = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = ReceiptReason.DURABLY_COMMITTED,
        receiptId = "receipt-$batchId",
        batchId = batchId,
        sessionId = "session-1",
        sequence = 4L,
        receivedAtEpochMillis = NOW,
        wireSha256Hex = "a".repeat(64),
        durableCommitToken = "commit-$batchId",
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = "durable_commit",
    )

    private fun lease() = ActiveBridgeConsentLease(
        consentGeneration = GENERATION,
        pairedWatchNodeId = WATCH_NODE,
        pairedWatchDeviceId = "watch-device-1",
        transportKeyId = "transport-generation-7",
        acknowledgementKeyId = ACK_KEY_ID,
    )

    private companion object {
        const val NOW = 10_000L
        const val GENERATION = 7L
        const val WATCH_NODE = "watch-node-1"
        const val ACK_KEY_ID = "ack-generation-7"
        const val STORAGE_KEY_ID = "receipt-storage-v1"
        val STORAGE_KEY = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
        val ACK_KEY = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "HmacSHA256")
    }
}

private class MutableReceiptLeaseProvider(
    var lease: ActiveBridgeConsentLease?,
) : BridgeConsentLeaseProvider {
    override fun currentLease(): ActiveBridgeConsentLease? = lease
}

private class ReceiptTestPublisher(
    var alwaysFail: Boolean = false,
) : DataLayerReceiptPublisher {
    val commands = mutableListOf<AuthenticatedReceiptCommand>()

    override fun publish(command: AuthenticatedReceiptCommand): ReceiptPublishResult {
        commands += command
        return if (alwaysFail) {
            ReceiptPublishResult.Failed("simulated_radio_failure")
        } else {
            ReceiptPublishResult.Published("delivery-${commands.size}")
        }
    }
}

private class RejectDeliveredMarkerOutbox(
    private val delegate: ReceiptDeliveryOutbox,
) : ReceiptDeliveryOutbox by delegate {
    override fun recordDelivered(
        deliveryId: String,
        deliveredAtEpochMillis: Long,
        deliveryToken: String,
    ): ReceiptOutboxTransitionResult =
        ReceiptOutboxTransitionResult.Rejected("simulated_post_send_crash")
}

private object RejectStageOutbox : ReceiptDeliveryOutbox {
    override fun recover(): ReceiptOutboxRecovery =
        ReceiptOutboxRecovery.RecoveryRequired("simulated_stage_failure")

    override fun stage(binding: ReceiptDeliveryBinding): ReceiptOutboxStageResult =
        ReceiptOutboxStageResult.Rejected("simulated_stage_failure")

    override fun recordFailure(
        deliveryId: String,
        attemptedAtEpochMillis: Long,
        detailCode: String,
    ): ReceiptOutboxTransitionResult = error("must not run")

    override fun recordDelivered(
        deliveryId: String,
        deliveredAtEpochMillis: Long,
        deliveryToken: String,
    ): ReceiptOutboxTransitionResult = error("must not run")

    override fun discardStaleConsent(
        deliveryId: String,
        discardedAtEpochMillis: Long,
    ): ReceiptOutboxTransitionResult = error("must not run")
}
