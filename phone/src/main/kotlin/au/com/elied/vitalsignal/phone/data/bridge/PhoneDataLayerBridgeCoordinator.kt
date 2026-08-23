package au.com.elied.vitalsignal.phone.data.bridge

import au.com.elied.vitalsignal.transport.BatchCommitCandidate
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchPayloadAuthenticator
import au.com.elied.vitalsignal.transport.BatchQuarantineRecord
import au.com.elied.vitalsignal.transport.BatchReceiverCoordinator
import au.com.elied.vitalsignal.transport.DurableBatchSink
import au.com.elied.vitalsignal.transport.DurableCommitResult
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.QuarantineWriteResult
import au.com.elied.vitalsignal.transport.ReceiptReason
import java.security.MessageDigest

/**
 * Phone-side, platform-independent bridge between a Wearable Data Layer listener and the existing
 * authenticated receipt core.
 *
 * This is deliberately not an Android service. The thin WearableListenerService translates a
 * DataItem into [DataLayerBatchEvent] and the MessageClient adapter publishes receipts. All work must run
 * off the main thread because the durable sink and publisher contracts are synchronous.
 *
 * The order is fixed:
 *  1. reject unrelated sources or stale consent-generation hints;
 *  2. authenticate and decode using [BatchReceiverCoordinator];
 *  3. re-check the captured consent lease inside the durable commit call;
 *  4. accept an ACK only from the durable sink;
 *  5. durably stage the exact receipt binding before any publish attempt;
 *  6. re-check the lease, HMAC the staged receipt, then publish to the exact paired node/path.
 */
class PhoneDataLayerBridgeCoordinator(
    private val durableSink: DurableBatchSink,
    private val payloadAuthenticator: BatchPayloadAuthenticator,
    private val consentLeaseProvider: BridgeConsentLeaseProvider,
    private val acknowledgementKeyResolver: BridgeAcknowledgementKeyResolver,
    private val receiptPublisher: DataLayerReceiptPublisher,
    receiptDeliveryOutbox: ReceiptDeliveryOutbox,
) {
    private val receiptDeliveryCoordinator = CrashSafeReceiptDeliveryCoordinator(
        outbox = receiptDeliveryOutbox,
        consentLeaseProvider = consentLeaseProvider,
        acknowledgementKeyResolver = acknowledgementKeyResolver,
        receiptPublisher = receiptPublisher,
    )

    @Synchronized
    fun onDataItem(event: DataLayerBatchEvent): PhoneBridgeProcessingResult {
        if (!event.path.isVitalSignalBatchPath()) {
            return PhoneBridgeProcessingResult.Ignored()
        }

        val lease = consentLeaseProvider.currentLease()
            ?: return guardReject(event, "consent_not_active")
        if (event.sourceNodeId != lease.pairedWatchNodeId) {
            return guardReject(event, "source_node_mismatch")
        }
        if (event.consentGeneration == 0L) {
            return guardReject(event, "consent_generation_missing")
        }
        if (
            event.consentGeneration != lease.consentGeneration
        ) {
            return guardReject(event, "consent_generation_mismatch")
        }

        val fencedSink = ConsentFencedDurableSink(
            delegate = durableSink,
            consentLeaseProvider = consentLeaseProvider,
            expectedLease = lease,
            eventPath = event.path,
        )
        val acknowledgement = BatchReceiverCoordinator(
            durableSink = fencedSink,
            payloadAuthenticator = payloadAuthenticator,
        ).receive(event.wireBytesCopy(), event.receivedAtEpochMillis)

        if (acknowledgement.detailCode.isNonDeliverableGuardFailure()) {
            return PhoneBridgeProcessingResult.GuardRejected(
                quarantineDisposition = acknowledgement.quarantineDisposition,
                quarantineToken = acknowledgement.quarantineToken,
                detailCode = acknowledgement.detailCode,
            )
        }

        val batchId = acknowledgement.batchId
        if (batchId == null) {
            return PhoneBridgeProcessingResult.QuarantinedWithoutReceipt(
                reason = acknowledgement.reason,
                quarantineDisposition = acknowledgement.quarantineDisposition,
                quarantineToken = acknowledgement.quarantineToken,
                detailCode = acknowledgement.detailCode,
            )
        }

        // Revocation or rotation after the commit suppresses the receipt. A later stale retry will
        // be rejected by the generation-specific transport-key fence, never silently re-ACKed.
        if (consentLeaseProvider.currentLease() != lease) {
            val quarantine = quarantineSecurityMetadata(event, "consent_changed_before_receipt")
            return PhoneBridgeProcessingResult.GuardRejected(
                quarantineDisposition = quarantine.disposition,
                quarantineToken = quarantine.token,
                detailCode = "consent_changed_before_receipt",
            )
        }

        return receiptDeliveryCoordinator.stageAndAttempt(
            expectedLease = lease,
            acknowledgement = acknowledgement,
            nowEpochMillis = event.receivedAtEpochMillis,
        )
    }

    /** Explicit scheduling seam for a bounded Android background worker; no worker is installed here. */
    fun retryPendingReceipts(
        nowEpochMillis: Long,
        maximumEntries: Int = 8,
    ): ReceiptRecoveryRunResult = receiptDeliveryCoordinator.recoverAndRetry(
        nowEpochMillis = nowEpochMillis,
        maximumEntries = maximumEntries,
    )

    private fun guardReject(event: DataLayerBatchEvent, detailCode: String): PhoneBridgeProcessingResult.GuardRejected {
        val quarantine = quarantineSecurityMetadata(event, detailCode)
        return PhoneBridgeProcessingResult.GuardRejected(
            quarantineDisposition = quarantine.disposition,
            quarantineToken = quarantine.token,
            detailCode = detailCode,
        )
    }

    /** Records only identity/digest metadata; rejected health bytes are never copied into this record. */
    private fun quarantineSecurityMetadata(
        event: DataLayerBatchEvent,
        detailCode: String,
    ): QuarantineOutcome {
        val digest = sha256Hex(event.wireBytesCopy())
        val record = BatchQuarantineRecord(
            quarantineId = "bridge-${digest.take(24)}-${event.receivedAtEpochMillis}",
            reason = ReceiptReason.AUTHENTICATION_FAILED,
            batchId = event.path.safeBatchIdFromPath(),
            sessionId = null,
            sequence = null,
            wireSha256Hex = digest,
            wireSizeBytes = event.wireSizeBytes,
            receivedAtEpochMillis = event.receivedAtEpochMillis,
            detailCode = detailCode,
        )
        val result = try {
            durableSink.quarantine(record)
        } catch (_: RuntimeException) {
            QuarantineWriteResult.Failed("quarantine_sink_exception")
        }
        return when (result) {
            is QuarantineWriteResult.Recorded -> {
                val token = result.durableQuarantineToken.takeIf { it.isValidWireToken() }
                if (token == null) {
                    QuarantineOutcome(QuarantineDisposition.RECORDING_FAILED, null)
                } else {
                    QuarantineOutcome(QuarantineDisposition.RECORDED, token)
                }
            }
            is QuarantineWriteResult.Failed ->
                QuarantineOutcome(QuarantineDisposition.RECORDING_FAILED, null)
        }
    }

    companion object {
        const val BATCH_PATH_PREFIX = "/v1/research/batches"
        const val RECEIPT_PATH_PREFIX = "/v1/research/receipts"
    }
}

/** Commit-time lease, identity, path, and key fence. */
private class ConsentFencedDurableSink(
    private val delegate: DurableBatchSink,
    private val consentLeaseProvider: BridgeConsentLeaseProvider,
    private val expectedLease: ActiveBridgeConsentLease,
    private val eventPath: String,
) : DurableBatchSink {
    override fun commit(candidate: BatchCommitCandidate): DurableCommitResult {
        if (consentLeaseProvider.currentLease() != expectedLease) {
            return DurableCommitResult.StoreFailure("consent_generation_changed")
        }
        if (candidate.transportKeyId != expectedLease.transportKeyId) {
            return DurableCommitResult.StoreFailure("consent_transport_key_mismatch")
        }
        if (candidate.envelope.deviceId != expectedLease.pairedWatchDeviceId) {
            return DurableCommitResult.StoreFailure("bridge_device_mismatch")
        }
        if (eventPath != batchPath(candidate.envelope)) {
            return DurableCommitResult.StoreFailure("bridge_path_identity_mismatch")
        }
        return delegate.commit(candidate)
    }

    override fun quarantine(record: BatchQuarantineRecord): QuarantineWriteResult =
        delegate.quarantine(
            record.copy(
                quarantineId = "${record.quarantineId.take(72)}-${record.receivedAtEpochMillis}",
            ),
        )
}

private data class QuarantineOutcome(
    val disposition: QuarantineDisposition,
    val token: String?,
)

private fun batchPath(envelope: BatchEnvelope): String =
    "${PhoneDataLayerBridgeCoordinator.BATCH_PATH_PREFIX}/${envelope.batchId}"

private fun String.isVitalSignalBatchPath(): Boolean =
    this == PhoneDataLayerBridgeCoordinator.BATCH_PATH_PREFIX ||
        startsWith(PhoneDataLayerBridgeCoordinator.BATCH_PATH_PREFIX + "/")

private fun String.safeBatchIdFromPath(): String? {
    val prefix = PhoneDataLayerBridgeCoordinator.BATCH_PATH_PREFIX + "/"
    if (!startsWith(prefix)) return null
    return removePrefix(prefix)
        .takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,96}")) }
}

private fun String.isNonDeliverableGuardFailure(): Boolean =
    startsWith("consent_") || startsWith("bridge_")

private fun String.isValidWireToken(): Boolean =
    isNotBlank() && toByteArray(Charsets.UTF_8).size <= 256

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
