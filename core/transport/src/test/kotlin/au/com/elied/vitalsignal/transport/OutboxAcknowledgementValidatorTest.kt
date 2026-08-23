package au.com.elied.vitalsignal.transport

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxAcknowledgementValidatorTest {
    @Test
    fun exactDurableAckAuthorizesDeletionOnlyOnce() {
        val wire = wire()
        val queued = QueuedBatchKey.fromCanonicalWireBytes(wire)
        val acknowledgement = ack(wire)
        val replayStore = RecordingReplayStore()
        val validator = validator(replayStore)

        val encoded = authenticated(acknowledgement)
        val first = validator.evaluateEncoded(queued, encoded)
        val replay = validator.evaluateEncoded(queued, encoded)

        assertTrue(first is OutboxAcknowledgementDecision.DeletionAuthorized)
        assertEquals(
            DeletionDenialReason.RECEIPT_REPLAY,
            (replay as OutboxAcknowledgementDecision.DeletionDenied).reason,
        )
    }

    @Test
    fun nackAndEveryIdentityMismatchDenyDeletion() {
        val wire = wire()
        val queued = QueuedBatchKey.fromCanonicalWireBytes(wire)
        val acknowledgement = ack(wire)

        assertDenied(
            queued,
            acknowledgement.copy(
                disposition = ReceiptDisposition.NACK,
                reason = ReceiptReason.STORE_FAILURE,
                durableCommitToken = null,
                quarantineDisposition = QuarantineDisposition.RECORDED,
                quarantineToken = "q-1",
                detailCode = "durable_commit_failed",
            ),
            DeletionDenialReason.NACK,
        )
        assertDenied(queued, acknowledgement.copy(batchId = "batch-other"), DeletionDenialReason.BATCH_ID_MISMATCH)
        assertDenied(queued, acknowledgement.copy(sessionId = "session-other"), DeletionDenialReason.SESSION_ID_MISMATCH)
        assertDenied(queued, acknowledgement.copy(sequence = 8), DeletionDenialReason.SEQUENCE_MISMATCH)
        assertDenied(
            queued,
            acknowledgement.copy(wireSha256Hex = "f".repeat(64)),
            DeletionDenialReason.CHECKSUM_MISMATCH,
        )
    }

    @Test
    fun replayStoreFailureCannotAuthorizeDeletion() {
        val wire = wire()
        val queued = QueuedBatchKey.fromCanonicalWireBytes(wire)
        val validator = validator(
            object : AcknowledgementReplayStore {
                override fun claim(receiptId: String, batchId: String): ReplayClaimResult =
                    ReplayClaimResult.StoreFailure
            },
        )

        val decision = validator.evaluateEncoded(queued, authenticated(ack(wire)))

        assertEquals(
            DeletionDenialReason.REPLAY_GUARD_FAILURE,
            (decision as OutboxAcknowledgementDecision.DeletionDenied).reason,
        )
    }

    @Test
    fun tamperedAcknowledgementWireCannotAuthorizeDeletion() {
        val wire = wire()
        val queued = QueuedBatchKey.fromCanonicalWireBytes(wire)
        val encodedAcknowledgement = authenticated(ack(wire)).also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }

        val decision = validator(RecordingReplayStore())
            .evaluateEncoded(queued, encodedAcknowledgement)

        assertEquals(
            DeletionDenialReason.ACK_AUTHENTICATION_FAILED,
            (decision as OutboxAcknowledgementDecision.DeletionDenied).reason,
        )
    }

    private fun assertDenied(
        queued: QueuedBatchKey,
        acknowledgement: BatchAcknowledgement,
        reason: DeletionDenialReason,
    ) {
        val decision = validator(RecordingReplayStore()).evaluateEncoded(
            queued,
            authenticated(acknowledgement),
        )
        assertEquals(reason, (decision as OutboxAcknowledgementDecision.DeletionDenied).reason)
    }

    private fun validator(replayStore: AcknowledgementReplayStore) =
        OutboxAcknowledgementValidator(
            replayStore = replayStore,
            acknowledgementKeyResolver = AcknowledgementKeyResolver { keyId ->
                if (keyId == ACK_KEY_ID) ACK_KEY else null
            },
        )

    private fun authenticated(acknowledgement: BatchAcknowledgement): ByteArray =
        AuthenticatedAcknowledgementCodec.encode(acknowledgement, ACK_KEY_ID, ACK_KEY)

    private fun wire(): ByteArray = BatchEnvelopeCodec.encode(
        BatchEnvelope(
            batchId = "batch-1",
            sessionId = "session-1",
            deviceId = "watch-1",
            sequence = 7,
            createdAtEpochMillis = 1_800_000_000_000,
            contentSchemaVersion = 1,
            contentType = "sensor.features",
            payload = byteArrayOf(1, 2, 3),
        ),
    )

    private fun ack(wire: ByteArray): BatchAcknowledgement = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = ReceiptReason.DURABLY_COMMITTED,
        receiptId = "receipt-1",
        batchId = "batch-1",
        sessionId = "session-1",
        sequence = 7,
        receivedAtEpochMillis = 1_800_000_000_100,
        wireSha256Hex = sha256Hex(wire),
        durableCommitToken = "commit-1",
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = "durable_commit",
    )

    private companion object {
        const val ACK_KEY_ID = "ack-key-1"
        val ACK_KEY = SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "HmacSHA256")
    }
}

private class RecordingReplayStore : AcknowledgementReplayStore {
    private val claims = mutableSetOf<String>()

    override fun claim(receiptId: String, batchId: String): ReplayClaimResult =
        if (claims.add(receiptId)) ReplayClaimResult.Claimed else ReplayClaimResult.AlreadyClaimed
}
