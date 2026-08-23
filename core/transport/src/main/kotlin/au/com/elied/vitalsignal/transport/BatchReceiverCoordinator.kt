package au.com.elied.vitalsignal.transport

import java.security.MessageDigest

/**
 * A durable sink must decide identity and sequence atomically with its commit. In particular, it
 * must never return [DurableCommitResult.Committed] before bytes and receipt metadata survive a
 * process restart.
 */
interface DurableBatchSink {
    fun commit(candidate: BatchCommitCandidate): DurableCommitResult

    fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult
}

class BatchCommitCandidate(
    val envelope: BatchEnvelope,
    canonicalWireBytes: ByteArray,
    val wireSha256Hex: String,
    val transportKeyId: String,
    authenticatedPayload: ByteArray,
) {
    private val immutableWireBytes = canonicalWireBytes.copyOf()
    private val immutableAuthenticatedPayload = authenticatedPayload.copyOf()

    fun canonicalWireBytesCopy(): ByteArray = immutableWireBytes.copyOf()
    fun authenticatedPayloadCopy(): ByteArray = immutableAuthenticatedPayload.copyOf()
}

sealed interface DurableCommitResult {
    data class Committed(val durableCommitToken: String) : DurableCommitResult

    /** The sink must return the digest from the already-durable canonical record. */
    data class AlreadyCommitted(
        val durableCommitToken: String,
        val canonicalWireSha256Hex: String,
    ) : DurableCommitResult

    data class ConflictingBatchId(val detailCode: String = "batch_id_reused") : DurableCommitResult

    data class OutOfOrder(val detailCode: String = "sequence_not_advanced") : DurableCommitResult

    data class StoreFailure(val detailCode: String = "durable_commit_failed") : DurableCommitResult
}

data class BatchQuarantineRecord(
    val quarantineId: String,
    val reason: ReceiptReason,
    val batchId: String?,
    val sessionId: String?,
    val sequence: Long?,
    val wireSha256Hex: String,
    val wireSizeBytes: Int,
    val receivedAtEpochMillis: Long,
    val detailCode: String,
)

sealed interface QuarantineWriteResult {
    data class Recorded(val durableQuarantineToken: String) : QuarantineWriteResult
    data class Failed(val detailCode: String = "quarantine_write_failed") : QuarantineWriteResult
}

/**
 * Phone-side receipt state machine. It is intentionally synchronous and platform-free; callers
 * choose their own dispatcher. No path constructs an ACK without a durable sink result.
 */
class BatchReceiverCoordinator(
    private val durableSink: DurableBatchSink,
    private val payloadAuthenticator: BatchPayloadAuthenticator,
) {
    @Synchronized
    fun receive(encoded: ByteArray, receivedAtEpochMillis: Long): BatchAcknowledgement {
        require(receivedAtEpochMillis >= 0) { "Receive time must be non-negative" }
        val digest = sha256Hex(encoded)
        val receiptId = receiptId(digest, receivedAtEpochMillis)
        val envelope = try {
            BatchEnvelopeCodec.decode(encoded)
        } catch (failure: WireDecodeException) {
            return nackAndQuarantine(
                reason = failure.failureCode.toReceiptReason(),
                receiptId = receiptId,
                envelope = null,
                digest = digest,
                wireSize = encoded.size,
                receivedAt = receivedAtEpochMillis,
                detailCode = "decode_${failure.failureCode.name.lowercase()}",
            )
        } catch (_: RuntimeException) {
            return nackAndQuarantine(
                reason = ReceiptReason.MALFORMED,
                receiptId = receiptId,
                envelope = null,
                digest = digest,
                wireSize = encoded.size,
                receivedAt = receivedAtEpochMillis,
                detailCode = "decode_unexpected_failure",
            )
        }

        val authenticated = try {
            payloadAuthenticator.authenticate(envelope)
        } catch (_: RuntimeException) {
            BatchAuthenticationResult.Rejected(
                ReceiptReason.AUTHENTICATION_FAILED,
                "authentication_exception",
            )
        }
        if (authenticated is BatchAuthenticationResult.Rejected) {
            return nackAndQuarantine(
                reason = authenticated.reason,
                receiptId = receiptId,
                envelope = envelope,
                digest = digest,
                wireSize = encoded.size,
                receivedAt = receivedAtEpochMillis,
                detailCode = authenticated.detailCode,
            )
        }
        authenticated as BatchAuthenticationResult.Authenticated

        val result = try {
            durableSink.commit(
                BatchCommitCandidate(
                    envelope = envelope,
                    canonicalWireBytes = encoded,
                    wireSha256Hex = digest,
                    transportKeyId = authenticated.keyId,
                    authenticatedPayload = authenticated.plaintextCopy(),
                ),
            )
        } catch (_: RuntimeException) {
            DurableCommitResult.StoreFailure("durable_sink_exception")
        }

        return when (result) {
            is DurableCommitResult.Committed -> {
                if (!result.durableCommitToken.isWireToken()) {
                    nackAndQuarantine(
                        ReceiptReason.STORE_FAILURE,
                        receiptId,
                        envelope,
                        digest,
                        encoded.size,
                        receivedAtEpochMillis,
                        "empty_durable_commit_token",
                    )
                } else {
                    ack(
                        envelope = envelope,
                        digest = digest,
                        receiptId = receiptId,
                        receivedAt = receivedAtEpochMillis,
                        durableToken = result.durableCommitToken,
                        duplicate = false,
                    )
                }
            }

            is DurableCommitResult.AlreadyCommitted -> {
                val sameBytes = result.canonicalWireSha256Hex.matches(SHA_256_HEX) &&
                    constantTimeHexEquals(result.canonicalWireSha256Hex, digest)
                if (sameBytes && result.durableCommitToken.isWireToken()) {
                    ack(
                        envelope = envelope,
                        digest = digest,
                        receiptId = receiptId,
                        receivedAt = receivedAtEpochMillis,
                        durableToken = result.durableCommitToken,
                        duplicate = true,
                    )
                } else {
                    nackAndQuarantine(
                        ReceiptReason.ID_CONFLICT,
                        receiptId,
                        envelope,
                        digest,
                        encoded.size,
                        receivedAtEpochMillis,
                        "durable_duplicate_digest_conflict",
                    )
                }
            }

            is DurableCommitResult.ConflictingBatchId -> nackAndQuarantine(
                ReceiptReason.ID_CONFLICT,
                receiptId,
                envelope,
                digest,
                encoded.size,
                receivedAtEpochMillis,
                result.detailCode.safeDetailCode("batch_id_conflict"),
            )

            is DurableCommitResult.OutOfOrder -> nackAndQuarantine(
                ReceiptReason.OUT_OF_ORDER,
                receiptId,
                envelope,
                digest,
                encoded.size,
                receivedAtEpochMillis,
                result.detailCode.safeDetailCode("out_of_order"),
            )

            is DurableCommitResult.StoreFailure -> nackAndQuarantine(
                ReceiptReason.STORE_FAILURE,
                receiptId,
                envelope,
                digest,
                encoded.size,
                receivedAtEpochMillis,
                result.detailCode.safeDetailCode("durable_commit_failed"),
            )
        }
    }

    private fun ack(
        envelope: BatchEnvelope,
        digest: String,
        receiptId: String,
        receivedAt: Long,
        durableToken: String,
        duplicate: Boolean,
    ) = BatchAcknowledgement(
        disposition = ReceiptDisposition.ACK,
        reason = if (duplicate) ReceiptReason.DURABLE_DUPLICATE else ReceiptReason.DURABLY_COMMITTED,
        receiptId = receiptId,
        batchId = envelope.batchId,
        sessionId = envelope.sessionId,
        sequence = envelope.sequence,
        receivedAtEpochMillis = receivedAt,
        wireSha256Hex = digest,
        durableCommitToken = durableToken,
        quarantineDisposition = QuarantineDisposition.NOT_APPLICABLE,
        quarantineToken = null,
        detailCode = if (duplicate) "durable_duplicate" else "durable_commit",
    )

    private fun nackAndQuarantine(
        reason: ReceiptReason,
        receiptId: String,
        envelope: BatchEnvelope?,
        digest: String,
        wireSize: Int,
        receivedAt: Long,
        detailCode: String,
    ): BatchAcknowledgement {
        val record = BatchQuarantineRecord(
            quarantineId = "quarantine-${digest.take(32)}",
            reason = reason,
            batchId = envelope?.batchId,
            sessionId = envelope?.sessionId,
            sequence = envelope?.sequence,
            wireSha256Hex = digest,
            wireSizeBytes = wireSize,
            receivedAtEpochMillis = receivedAt,
            detailCode = detailCode.safeDetailCode("quarantined"),
        )
        val quarantineResult = try {
            durableSink.quarantine(record)
        } catch (_: RuntimeException) {
            QuarantineWriteResult.Failed("quarantine_sink_exception")
        }
        val token = (quarantineResult as? QuarantineWriteResult.Recorded)
            ?.durableQuarantineToken
            ?.takeIf { it.isWireToken() }
        return BatchAcknowledgement(
            disposition = ReceiptDisposition.NACK,
            reason = reason,
            receiptId = receiptId,
            batchId = envelope?.batchId,
            sessionId = envelope?.sessionId,
            sequence = envelope?.sequence,
            receivedAtEpochMillis = receivedAt,
            wireSha256Hex = digest,
            durableCommitToken = null,
            quarantineDisposition = if (token == null) {
                QuarantineDisposition.RECORDING_FAILED
            } else {
                QuarantineDisposition.RECORDED
            },
            quarantineToken = token,
            detailCode = record.detailCode,
        )
    }

    private companion object {
        val SHA_256_HEX = Regex("[a-f0-9]{64}")
    }
}

private fun DecodeFailureCode.toReceiptReason(): ReceiptReason = when (this) {
    DecodeFailureCode.OVERSIZE -> ReceiptReason.OVERSIZE
    DecodeFailureCode.UNSUPPORTED_VERSION -> ReceiptReason.UNSUPPORTED_VERSION
    DecodeFailureCode.CHECKSUM_MISMATCH -> ReceiptReason.CHECKSUM_MISMATCH
    DecodeFailureCode.TRAILING_BYTES -> ReceiptReason.TRAILING_BYTES
    DecodeFailureCode.TRUNCATED,
    DecodeFailureCode.BAD_MAGIC,
    DecodeFailureCode.MALFORMED,
    -> ReceiptReason.MALFORMED
}

private fun constantTimeHexEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII),
    right.toByteArray(Charsets.US_ASCII),
)

private fun receiptId(digest: String, receivedAt: Long): String =
    "receipt-${digest.take(24)}-$receivedAt"

private fun String.safeDetailCode(fallback: String): String =
    takeIf { matches(Regex("[a-z0-9_.-]{1,96}")) } ?: fallback

private fun String.isWireToken(): Boolean =
    isNotBlank() && toByteArray(Charsets.UTF_8).size <= BatchWireLimits.MAX_TOKEN_BYTES
