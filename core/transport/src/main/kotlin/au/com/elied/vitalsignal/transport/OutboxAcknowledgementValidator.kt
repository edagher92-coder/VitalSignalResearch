package au.com.elied.vitalsignal.transport

import java.security.MessageDigest

/** Identity of a locally queued wire envelope. Construction validates the full envelope first. */
class QueuedBatchKey private constructor(
    val batchId: String,
    val sessionId: String,
    val sequence: Long,
    val wireSha256Hex: String,
) {
    companion object {
        fun fromCanonicalWireBytes(encoded: ByteArray): QueuedBatchKey {
            val envelope = BatchEnvelopeCodec.decode(encoded)
            return QueuedBatchKey(
                batchId = envelope.batchId,
                sessionId = envelope.sessionId,
                sequence = envelope.sequence,
                wireSha256Hex = sha256Hex(encoded),
            )
        }
    }
}

enum class DeletionDenialReason {
    NACK,
    ACK_WIRE_INVALID,
    ACK_AUTHENTICATION_FAILED,
    ACK_KEY_UNAVAILABLE,
    BATCH_ID_MISMATCH,
    SESSION_ID_MISMATCH,
    SEQUENCE_MISMATCH,
    CHECKSUM_MISMATCH,
    RECEIPT_REPLAY,
    REPLAY_GUARD_FAILURE,
}

sealed interface OutboxAcknowledgementDecision {
    data class DeletionAuthorized(
        val batchId: String,
        val receiptId: String,
        val durableCommitToken: String,
        val acknowledgementKeyId: String,
    ) : OutboxAcknowledgementDecision

    data class DeletionDenied(val reason: DeletionDenialReason) : OutboxAcknowledgementDecision
}

/**
 * The store must atomically and durably claim an ACK before deletion is authorized. Returning
 * [ReplayClaimResult.Claimed] means the claim survives process restart.
 */
interface AcknowledgementReplayStore {
    fun claim(receiptId: String, batchId: String): ReplayClaimResult
}

sealed interface ReplayClaimResult {
    data object Claimed : ReplayClaimResult
    data object AlreadyClaimed : ReplayClaimResult
    data object StoreFailure : ReplayClaimResult
}

/**
 * Watch-side deletion gate. Only one exact, durably claimed ACK can authorize removal of a queued
 * batch; neither a NACK nor a partially matching/replayed receipt mutates the queue.
 */
class OutboxAcknowledgementValidator(
    private val replayStore: AcknowledgementReplayStore,
    private val acknowledgementKeyResolver: AcknowledgementKeyResolver,
) {
    fun evaluateEncoded(
        queued: QueuedBatchKey,
        encodedAcknowledgement: ByteArray,
    ): OutboxAcknowledgementDecision {
        val authenticated = when (
            val result = AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(
                encodedAcknowledgement,
                acknowledgementKeyResolver,
            )
        ) {
            is AuthenticatedAcknowledgementResult.Authenticated -> result
            is AuthenticatedAcknowledgementResult.UnknownKey -> {
                return OutboxAcknowledgementDecision.DeletionDenied(
                    DeletionDenialReason.ACK_KEY_UNAVAILABLE,
                )
            }
            AuthenticatedAcknowledgementResult.AuthenticationFailed -> {
                return OutboxAcknowledgementDecision.DeletionDenied(
                    DeletionDenialReason.ACK_AUTHENTICATION_FAILED,
                )
            }
            AuthenticatedAcknowledgementResult.Malformed -> {
                return OutboxAcknowledgementDecision.DeletionDenied(
                    DeletionDenialReason.ACK_WIRE_INVALID,
                )
            }
        }
        return evaluateAuthenticated(queued, authenticated)
    }

    @Synchronized
    private fun evaluateAuthenticated(
        queued: QueuedBatchKey,
        authenticated: AuthenticatedAcknowledgementResult.Authenticated,
    ): OutboxAcknowledgementDecision {
        val acknowledgement = authenticated.acknowledgement
        if (acknowledgement.disposition != ReceiptDisposition.ACK) {
            return OutboxAcknowledgementDecision.DeletionDenied(DeletionDenialReason.NACK)
        }
        if (acknowledgement.batchId != queued.batchId) {
            return OutboxAcknowledgementDecision.DeletionDenied(DeletionDenialReason.BATCH_ID_MISMATCH)
        }
        if (acknowledgement.sessionId != queued.sessionId) {
            return OutboxAcknowledgementDecision.DeletionDenied(DeletionDenialReason.SESSION_ID_MISMATCH)
        }
        if (acknowledgement.sequence != queued.sequence) {
            return OutboxAcknowledgementDecision.DeletionDenied(DeletionDenialReason.SEQUENCE_MISMATCH)
        }
        if (!constantTimeDigestEquals(acknowledgement.wireSha256Hex, queued.wireSha256Hex)) {
            return OutboxAcknowledgementDecision.DeletionDenied(DeletionDenialReason.CHECKSUM_MISMATCH)
        }

        val replayClaim = try {
            replayStore.claim(acknowledgement.receiptId, queued.batchId)
        } catch (_: RuntimeException) {
            ReplayClaimResult.StoreFailure
        }
        when (replayClaim) {
            ReplayClaimResult.AlreadyClaimed -> return OutboxAcknowledgementDecision.DeletionDenied(
                DeletionDenialReason.RECEIPT_REPLAY,
            )
            ReplayClaimResult.StoreFailure -> return OutboxAcknowledgementDecision.DeletionDenied(
                DeletionDenialReason.REPLAY_GUARD_FAILURE,
            )
            ReplayClaimResult.Claimed -> Unit
        }
        return OutboxAcknowledgementDecision.DeletionAuthorized(
            batchId = queued.batchId,
            receiptId = acknowledgement.receiptId,
            durableCommitToken = requireNotNull(acknowledgement.durableCommitToken),
            acknowledgementKeyId = authenticated.keyId,
        )
    }
}

private fun constantTimeDigestEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)
