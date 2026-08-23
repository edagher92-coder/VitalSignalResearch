package au.com.elied.vitalsignal.phone.data.bridge

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.EncryptedBatchJournalSink
import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AesGcmBatchPayloadAuthenticator
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementResult
import au.com.elied.vitalsignal.transport.AuthenticatedBatchPayloadCipher
import au.com.elied.vitalsignal.transport.BatchCommitCandidate
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.BatchQuarantineRecord
import au.com.elied.vitalsignal.transport.DurableBatchSink
import au.com.elied.vitalsignal.transport.DurableCommitResult
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.QuarantineWriteResult
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import au.com.elied.vitalsignal.transport.TransportKeyResolver
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataLayerBridgeCoordinatorTest {
    @Test
    fun exactAuthenticatedAckIsPublishedOnlyAfterDurableCommit() {
        val order = mutableListOf<String>()
        val backend = RestartSafeBackend(order)
        val publisher = RecordingPublisher(order)
        val sourceWire = wire("batch-1", sequence = 4)
        val event = event("batch-1", sourceWire)
        sourceWire.fill(0) // The ingress event must own an immutable copy.

        val result = bridge(backend, publisher).onDataItem(event)

        assertTrue(result is PhoneBridgeProcessingResult.AckPublished)
        result as PhoneBridgeProcessingResult.AckPublished
        assertEquals(listOf("commit:batch-1", "publish:/v1/research/receipts/batch-1"), order)
        assertEquals(ReceiptReason.DURABLY_COMMITTED, result.acknowledgement.reason)
        assertEquals("commit-batch-1", result.acknowledgement.durableCommitToken)
        assertEquals(1, backend.records.size)

        val command = publisher.commands.single()
        assertEquals(WATCH_NODE, command.targetNodeId)
        assertEquals("/v1/research/receipts/batch-1", command.path)
        assertEquals(CONSENT_GENERATION, command.consentGeneration)
        val authenticated = authenticateReceipt(command)
        assertEquals(ACK_KEY_ID, authenticated.keyId)
        with(authenticated.acknowledgement) {
            assertEquals(ReceiptDisposition.ACK, disposition)
            assertEquals(ReceiptReason.DURABLY_COMMITTED, reason)
            assertEquals("batch-1", batchId)
            assertEquals("session-1", sessionId)
            assertEquals(4L, sequence)
            assertEquals(sha256Hex(event.wireBytesCopy()), wireSha256Hex)
            assertEquals("commit-batch-1", durableCommitToken)
            assertEquals(QuarantineDisposition.NOT_APPLICABLE, quarantineDisposition)
            assertNull(quarantineToken)
        }

        val exported = command.authenticatedReceiptBytesCopy()
        val expected = exported.copyOf()
        exported.fill(0)
        assertArrayEquals(expected, command.authenticatedReceiptBytesCopy())
    }

    @Test
    fun authenticatedMetadataTamperIsQuarantinedAndCanOnlyEmitNack() {
        val backend = RestartSafeBackend()
        val publisher = RecordingPublisher()
        val original = BatchEnvelopeCodec.decode(wire("batch-tamper", sequence = 5))
        val tampered = BatchEnvelopeCodec.encode(
            BatchEnvelope(
                batchId = original.batchId,
                sessionId = "session-tampered",
                deviceId = original.deviceId,
                sequence = original.sequence,
                createdAtEpochMillis = original.createdAtEpochMillis,
                contentSchemaVersion = original.contentSchemaVersion,
                contentType = original.contentType,
                payload = original.payloadCopy(),
            ),
        )

        val result = bridge(backend, publisher).onDataItem(event("batch-tamper", tampered))

        assertTrue(result is PhoneBridgeProcessingResult.NackPublished)
        result as PhoneBridgeProcessingResult.NackPublished
        assertEquals(ReceiptDisposition.NACK, result.acknowledgement.disposition)
        assertEquals(ReceiptReason.AUTHENTICATION_FAILED, result.acknowledgement.reason)
        assertNull(result.acknowledgement.durableCommitToken)
        assertEquals(QuarantineDisposition.RECORDED, result.acknowledgement.quarantineDisposition)
        assertEquals(0, backend.commitCalls)
        assertEquals(1, backend.quarantines.size)
        val authenticated = authenticateReceipt(publisher.commands.single())
        assertEquals(ReceiptReason.AUTHENTICATION_FAILED, authenticated.acknowledgement.reason)
        assertEquals(ReceiptDisposition.NACK, authenticated.acknowledgement.disposition)
    }

    @Test
    fun lostReceiptRetryAfterCoordinatorRestartIsIdempotentlyReAcked() {
        val backend = RestartSafeBackend()
        val firstPublisher = RecordingPublisher(failNext = true)
        val encoded = wire("batch-retry", sequence = 8)

        val first = bridge(backend, firstPublisher).onDataItem(event("batch-retry", encoded, receivedAt = 2_000L))
        val restartedPublisher = RecordingPublisher()
        val afterRestart = bridge(backend, restartedPublisher)
            .onDataItem(event("batch-retry", encoded.copyOf(), receivedAt = 3_000L))

        assertTrue(first is PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        first as PhoneBridgeProcessingResult.ReceiptDeliveryPending
        assertEquals(ReceiptReason.DURABLY_COMMITTED, first.acknowledgement.reason)
        assertNotNull(first.command)
        assertTrue(afterRestart is PhoneBridgeProcessingResult.AckPublished)
        afterRestart as PhoneBridgeProcessingResult.AckPublished
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, afterRestart.acknowledgement.reason)
        assertEquals(first.acknowledgement.durableCommitToken, afterRestart.acknowledgement.durableCommitToken)
        assertEquals(1, backend.records.size)
        assertEquals(2, backend.commitCalls)
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, authenticateReceipt(restartedPublisher.commands.single()).acknowledgement.reason)
    }

    @Test
    fun encryptedJournalReopenReissuesExactDuplicateAckAfterProcessShapedRestart() {
        val root = Files.createTempDirectory("vitalsignal-phone-bridge-restart")
        val storageKey = KeyGenerator.getInstance("AES").run {
            init(256)
            generateKey()
        }
        val leaseProvider = MutableLeaseProvider(activeLease())
        val encoded = wire("batch-encrypted-restart", sequence = 9)
        val firstPublisher = RecordingPublisher(failNext = true)
        val first = encryptedBridge(root, storageKey, leaseProvider, firstPublisher)
            .onDataItem(event("batch-encrypted-restart", encoded, receivedAt = 4_000L))

        val restartedPublisher = RecordingPublisher()
        val reopened = encryptedBridge(root, storageKey, leaseProvider, restartedPublisher)
            .onDataItem(event("batch-encrypted-restart", encoded.copyOf(), receivedAt = 5_000L))

        assertTrue(first is PhoneBridgeProcessingResult.ReceiptDeliveryPending)
        first as PhoneBridgeProcessingResult.ReceiptDeliveryPending
        assertEquals(ReceiptReason.DURABLY_COMMITTED, first.acknowledgement.reason)
        assertTrue(reopened is PhoneBridgeProcessingResult.AckPublished)
        reopened as PhoneBridgeProcessingResult.AckPublished
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, reopened.acknowledgement.reason)
        assertEquals(first.acknowledgement.durableCommitToken, reopened.acknowledgement.durableCommitToken)
        assertEquals(ReceiptReason.DURABLE_DUPLICATE, authenticateReceipt(restartedPublisher.commands.single()).acknowledgement.reason)
        val committedFiles = Files.list(root.resolve("batches").resolve("records")).use { files ->
            files.filter { it.fileName.toString().endsWith(".vsr") }.count()
        }
        assertEquals(1L, committedFiles)
    }

    @Test
    fun consentGenerationMismatchFailsBeforeAuthenticationOrCommit() {
        val backend = RestartSafeBackend()
        val publisher = RecordingPublisher()
        val result = bridge(backend, publisher).onDataItem(
            event(
                batchId = "batch-consent",
                encoded = wire("batch-consent", sequence = 1),
                consentGeneration = CONSENT_GENERATION - 1,
            ),
        )

        assertTrue(result is PhoneBridgeProcessingResult.GuardRejected)
        result as PhoneBridgeProcessingResult.GuardRejected
        assertEquals("consent_generation_mismatch", result.detailCode)
        assertEquals(QuarantineDisposition.RECORDED, result.quarantineDisposition)
        assertEquals(0, backend.commitCalls)
        assertEquals(1, backend.quarantines.size)
        assertEquals("consent_generation_mismatch", backend.quarantines.single().detailCode)
        assertTrue(publisher.commands.isEmpty())
    }

    @Test
    fun legacyIngressWithMissingConsentGenerationIsDurablyQuarantined() {
        val backend = RestartSafeBackend()
        val publisher = RecordingPublisher()

        val result = bridge(backend, publisher).onDataItem(
            event(
                batchId = "batch-legacy",
                encoded = wire("batch-legacy", sequence = 1),
                consentGeneration = 0L,
            ),
        )

        assertTrue(result is PhoneBridgeProcessingResult.GuardRejected)
        result as PhoneBridgeProcessingResult.GuardRejected
        assertEquals("consent_generation_missing", result.detailCode)
        assertEquals(QuarantineDisposition.RECORDED, result.quarantineDisposition)
        assertEquals(0, backend.commitCalls)
        assertEquals("consent_generation_missing", backend.quarantines.single().detailCode)
        assertTrue(publisher.commands.isEmpty())
    }

    @Test
    fun authenticatedStaleTransportKeyCannotCrossCurrentConsentFence() {
        val backend = RestartSafeBackend()
        val publisher = RecordingPublisher()
        val staleWire = wire(
            batchId = "batch-stale",
            sequence = 2,
            keyId = STALE_TRANSPORT_KEY_ID,
            key = STALE_TRANSPORT_KEY,
        )

        val result = bridge(backend, publisher, resolveStaleKey = true)
            .onDataItem(event("batch-stale", staleWire))

        assertTrue(result is PhoneBridgeProcessingResult.GuardRejected)
        result as PhoneBridgeProcessingResult.GuardRejected
        assertEquals("consent_transport_key_mismatch", result.detailCode)
        assertEquals(0, backend.commitCalls)
        assertEquals(1, backend.quarantines.size)
        assertTrue(publisher.commands.isEmpty())
    }

    @Test
    fun commitFailureProducesAuthenticatedQuarantinedNackNeverAck() {
        val backend = RestartSafeBackend().also { it.failNextCommit = true }
        val publisher = RecordingPublisher()

        val result = bridge(backend, publisher)
            .onDataItem(event("batch-store-fail", wire("batch-store-fail", sequence = 6)))

        assertTrue(result is PhoneBridgeProcessingResult.NackPublished)
        result as PhoneBridgeProcessingResult.NackPublished
        assertEquals(ReceiptDisposition.NACK, result.acknowledgement.disposition)
        assertEquals(ReceiptReason.STORE_FAILURE, result.acknowledgement.reason)
        assertNull(result.acknowledgement.durableCommitToken)
        assertEquals(0, backend.records.size)
        assertEquals(1, backend.quarantines.size)
        val authenticated = authenticateReceipt(publisher.commands.single()).acknowledgement
        assertEquals(ReceiptDisposition.NACK, authenticated.disposition)
        assertEquals(ReceiptReason.STORE_FAILURE, authenticated.reason)
    }

    @Test
    fun reusedBatchIdWithDifferentAuthenticatedBytesIsConflictQuarantined() {
        val backend = RestartSafeBackend()
        val publisher = RecordingPublisher()
        val coordinator = bridge(backend, publisher)
        val first = coordinator.onDataItem(
            event("batch-conflict", wire("batch-conflict", sequence = 10, plaintext = byteArrayOf(1))),
        )
        val conflict = coordinator.onDataItem(
            event("batch-conflict", wire("batch-conflict", sequence = 11, plaintext = byteArrayOf(2))),
        )

        assertTrue(first is PhoneBridgeProcessingResult.AckPublished)
        assertTrue(conflict is PhoneBridgeProcessingResult.NackPublished)
        conflict as PhoneBridgeProcessingResult.NackPublished
        assertEquals(ReceiptReason.ID_CONFLICT, conflict.acknowledgement.reason)
        assertEquals(QuarantineDisposition.RECORDED, conflict.acknowledgement.quarantineDisposition)
        assertEquals(1, backend.records.size)
        assertEquals(1, backend.quarantines.size)
    }

    @Test
    fun consentRotationAfterCommitSuppressesReceiptDelivery() {
        val leaseProvider = MutableLeaseProvider(activeLease())
        val backend = RestartSafeBackend().also { storage ->
            storage.afterSuccessfulCommit = {
                leaseProvider.lease = activeLease().copy(
                    consentGeneration = CONSENT_GENERATION + 1,
                    transportKeyId = "transport-generation-8",
                    acknowledgementKeyId = "ack-generation-8",
                )
            }
        }
        val publisher = RecordingPublisher()

        val result = bridge(backend, publisher, leaseProvider = leaseProvider)
            .onDataItem(event("batch-race", wire("batch-race", sequence = 12)))

        assertTrue(result is PhoneBridgeProcessingResult.GuardRejected)
        result as PhoneBridgeProcessingResult.GuardRejected
        assertEquals("consent_changed_before_receipt", result.detailCode)
        assertEquals(1, backend.records.size)
        assertTrue(publisher.commands.isEmpty())
    }

    private fun bridge(
        backend: RestartSafeBackend,
        publisher: RecordingPublisher,
        resolveStaleKey: Boolean = false,
        leaseProvider: MutableLeaseProvider = MutableLeaseProvider(activeLease()),
    ) = PhoneDataLayerBridgeCoordinator(
        durableSink = RestartSafeSink(backend),
        payloadAuthenticator = AesGcmBatchPayloadAuthenticator(
            cipher = AuthenticatedBatchPayloadCipher(SecureRandom()),
            keyResolver = TransportKeyResolver { keyId ->
                when (keyId) {
                    TRANSPORT_KEY_ID -> TRANSPORT_KEY
                    STALE_TRANSPORT_KEY_ID -> STALE_TRANSPORT_KEY.takeIf { resolveStaleKey }
                    else -> null
                }
            },
        ),
        consentLeaseProvider = leaseProvider,
        acknowledgementKeyResolver = BridgeAcknowledgementKeyResolver { keyId ->
            ACK_KEY.takeIf { keyId == ACK_KEY_ID }
        },
        receiptPublisher = publisher,
        receiptDeliveryOutbox = encryptedReceiptOutbox(
            Files.createTempDirectory("vitalsignal-receipt-outbox"),
            SecretKeySpec(ByteArray(32) { (it + 71).toByte() }, "AES"),
        ),
    )

    private fun encryptedBridge(
        root: Path,
        storageKey: SecretKey,
        leaseProvider: MutableLeaseProvider,
        publisher: RecordingPublisher,
    ): PhoneDataLayerBridgeCoordinator {
        val authenticator = AesGcmBatchPayloadAuthenticator(
            cipher = AuthenticatedBatchPayloadCipher(SecureRandom()),
            keyResolver = TransportKeyResolver { keyId -> TRANSPORT_KEY.takeIf { keyId == TRANSPORT_KEY_ID } },
        )
        val sink = EncryptedBatchJournalSink(
            batchStore = EncryptedAppendOnlyRecordStore(
                root.resolve("batches"),
                storageKey,
                "bridge-storage-batches-v1",
                SecureRandom(),
            ),
            quarantineStore = EncryptedAppendOnlyRecordStore(
                root.resolve("quarantine"),
                storageKey,
                "bridge-storage-quarantine-v1",
                SecureRandom(),
            ),
            payloadAuthenticator = authenticator,
        )
        return PhoneDataLayerBridgeCoordinator(
            durableSink = sink,
            payloadAuthenticator = authenticator,
            consentLeaseProvider = leaseProvider,
            acknowledgementKeyResolver = BridgeAcknowledgementKeyResolver { keyId ->
                ACK_KEY.takeIf { keyId == ACK_KEY_ID }
            },
            receiptPublisher = publisher,
            receiptDeliveryOutbox = encryptedReceiptOutbox(root.resolve("receipt-delivery"), storageKey),
        )
    }

    private fun encryptedReceiptOutbox(root: Path, storageKey: SecretKey) =
        EncryptedAppendOnlyReceiptDeliveryOutbox(
            EncryptedAppendOnlyRecordStore(
                root,
                storageKey,
                "bridge-receipt-delivery-v1",
                SecureRandom(),
                maxPayloadBytes = 24 * 1024,
            ),
        )

    private fun event(
        batchId: String,
        encoded: ByteArray,
        receivedAt: Long = 1_900_000_000_000L,
        consentGeneration: Long = CONSENT_GENERATION,
    ) = DataLayerBatchEvent(
        path = "/v1/research/batches/$batchId",
        sourceNodeId = WATCH_NODE,
        receivedAtEpochMillis = receivedAt,
        consentGeneration = consentGeneration,
        wireBytes = encoded,
    )

    private fun wire(
        batchId: String,
        sequence: Long,
        plaintext: ByteArray = "fixture-$batchId".toByteArray(),
        keyId: String = TRANSPORT_KEY_ID,
        key: SecretKeySpec = TRANSPORT_KEY,
    ): ByteArray = BatchEnvelopeCodec.encode(
        AuthenticatedBatchPayloadCipher(SecureRandom()).seal(
            batchId = batchId,
            sessionId = "session-1",
            deviceId = WATCH_DEVICE,
            sequence = sequence,
            createdAtEpochMillis = 1_800_000_000_000L + sequence,
            contentSchemaVersion = 1,
            contentType = "sensor.features",
            plaintext = plaintext,
            keyId = keyId,
            secretKey = key,
        ),
    )

    private fun authenticateReceipt(command: AuthenticatedReceiptCommand): AuthenticatedAcknowledgementResult.Authenticated {
        val result = AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(
            command.authenticatedReceiptBytesCopy(),
            AcknowledgementKeyResolver { keyId -> ACK_KEY.takeIf { keyId == ACK_KEY_ID } },
        )
        assertTrue(result is AuthenticatedAcknowledgementResult.Authenticated)
        return result as AuthenticatedAcknowledgementResult.Authenticated
    }

    private companion object {
        const val CONSENT_GENERATION = 7L
        const val WATCH_NODE = "watch-node-1"
        const val WATCH_DEVICE = "watch-device-1"
        const val TRANSPORT_KEY_ID = "transport-generation-7"
        const val STALE_TRANSPORT_KEY_ID = "transport-generation-6"
        const val ACK_KEY_ID = "ack-generation-7"
        val TRANSPORT_KEY = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
        val STALE_TRANSPORT_KEY = SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "AES")
        val ACK_KEY = SecretKeySpec(ByteArray(32) { (it + 31).toByte() }, "HmacSHA256")

        fun activeLease() = ActiveBridgeConsentLease(
            consentGeneration = CONSENT_GENERATION,
            pairedWatchNodeId = WATCH_NODE,
            pairedWatchDeviceId = WATCH_DEVICE,
            transportKeyId = TRANSPORT_KEY_ID,
            acknowledgementKeyId = ACK_KEY_ID,
        )
    }
}

private class MutableLeaseProvider(
    var lease: ActiveBridgeConsentLease?,
) : BridgeConsentLeaseProvider {
    override fun currentLease(): ActiveBridgeConsentLease? = lease
}

private class RecordingPublisher(
    private val order: MutableList<String> = mutableListOf(),
    var failNext: Boolean = false,
) : DataLayerReceiptPublisher {
    val commands = mutableListOf<AuthenticatedReceiptCommand>()

    override fun publish(command: AuthenticatedReceiptCommand): ReceiptPublishResult {
        commands += command
        order += "publish:${command.path}"
        if (failNext) {
            failNext = false
            return ReceiptPublishResult.Failed("simulated_radio_failure")
        }
        return ReceiptPublishResult.Published("delivery-${commands.size}")
    }
}

private class RestartSafeBackend(
    val order: MutableList<String> = mutableListOf(),
) {
    val records = linkedMapOf<String, StoredBatch>()
    val ordinalOwners = linkedMapOf<BatchOrdinal, String>()
    val quarantines = mutableListOf<BatchQuarantineRecord>()
    var failNextCommit: Boolean = false
    var commitCalls: Int = 0
    var afterSuccessfulCommit: (() -> Unit)? = null
}

private class RestartSafeSink(
    private val backend: RestartSafeBackend,
) : DurableBatchSink {
    override fun commit(candidate: BatchCommitCandidate): DurableCommitResult {
        backend.commitCalls += 1
        backend.order += "commit:${candidate.envelope.batchId}"
        if (backend.failNextCommit) {
            backend.failNextCommit = false
            return DurableCommitResult.StoreFailure("simulated_commit_failure")
        }
        backend.records[candidate.envelope.batchId]?.let { existing ->
            return if (constantTimeEquals(existing.wireSha256Hex, candidate.wireSha256Hex)) {
                DurableCommitResult.AlreadyCommitted(existing.commitToken, existing.wireSha256Hex)
            } else {
                DurableCommitResult.ConflictingBatchId("batch_id_reused")
            }
        }
        val ordinal = BatchOrdinal(
            deviceId = candidate.envelope.deviceId,
            sessionId = candidate.envelope.sessionId,
            sequence = candidate.envelope.sequence,
        )
        if (backend.ordinalOwners.containsKey(ordinal)) {
            return DurableCommitResult.ConflictingBatchId("sequence_already_committed")
        }
        val record = StoredBatch(
            wireSha256Hex = candidate.wireSha256Hex,
            commitToken = "commit-${candidate.envelope.batchId}",
        )
        backend.records[candidate.envelope.batchId] = record
        backend.ordinalOwners[ordinal] = candidate.envelope.batchId
        backend.afterSuccessfulCommit?.invoke()
        return DurableCommitResult.Committed(record.commitToken)
    }

    override fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult {
        backend.quarantines += record
        return QuarantineWriteResult.Recorded("quarantine-${backend.quarantines.size}")
    }
}

private data class StoredBatch(
    val wireSha256Hex: String,
    val commitToken: String,
)

private data class BatchOrdinal(
    val deviceId: String,
    val sessionId: String,
    val sequence: Long,
)

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)
