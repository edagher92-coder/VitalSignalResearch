package au.com.elied.vitalsignal.phone.data.bridge

import au.com.elied.vitalsignal.transport.BatchAcknowledgement
import au.com.elied.vitalsignal.transport.QuarantineDisposition
import au.com.elied.vitalsignal.transport.ReceiptDisposition
import au.com.elied.vitalsignal.transport.ReceiptReason
import javax.crypto.SecretKey

/**
 * Platform-free view of one Wearable Data Layer DataItem event.
 *
 * The WearableListenerService adapter is responsible only for copying the Google Play
 * services values into this object and invoking the coordinator on a background dispatcher. The
 * byte array is copied so an asynchronous Data Layer callback cannot mutate it during validation.
 * [consentGeneration] is required at ingress and preserved into the authenticated receipt command.
 * The generation-specific transport key and a commit-time lease check remain the authoritative
 * fence; metadata equality alone never authorizes a commit.
 */
class DataLayerBatchEvent(
    val path: String,
    val sourceNodeId: String,
    val receivedAtEpochMillis: Long,
    val consentGeneration: Long,
    wireBytes: ByteArray,
) {
    private val immutableWireBytes = wireBytes.copyOf()

    init {
        require(path.startsWith("/") && path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES) {
            "Invalid Data Layer path"
        }
        require(sourceNodeId.isNotBlank() && sourceNodeId.toByteArray(Charsets.UTF_8).size <= MAX_NODE_ID_BYTES) {
            "Invalid source node ID"
        }
        require(receivedAtEpochMillis >= 0L) { "Receive time must be non-negative" }
        // Zero is an explicit legacy/missing sentinel so the coordinator can durably quarantine
        // old ingress instead of throwing before it reaches the security boundary.
        require(consentGeneration >= MISSING_CONSENT_GENERATION) {
            "Consent generation must be non-negative"
        }
        require(immutableWireBytes.size in 1..MAX_CANONICAL_WIRE_BYTES) {
            "Canonical Data Layer envelope must be 1..$MAX_CANONICAL_WIRE_BYTES bytes"
        }
    }

    val wireSizeBytes: Int get() = immutableWireBytes.size

    fun wireBytesCopy(): ByteArray = immutableWireBytes.copyOf()

    companion object {
        const val MAX_CANONICAL_WIRE_BYTES = 64 * 1024
        const val MAX_PATH_BYTES = 512
        const val MAX_NODE_ID_BYTES = 256
        const val MISSING_CONSENT_GENERATION = 0L
    }
}

/**
 * An immutable, generation-scoped permission to receive from one paired watch.
 *
 * Key identifiers must change when consent is withdrawn and later granted again. Reusing key IDs
 * would defeat the stale-generation fence even if the numeric generation changed.
 */
data class ActiveBridgeConsentLease(
    val consentGeneration: Long,
    val pairedWatchNodeId: String,
    val pairedWatchDeviceId: String,
    val transportKeyId: String,
    val acknowledgementKeyId: String,
) {
    init {
        require(consentGeneration > 0L) { "Consent generation must be positive" }
        require(pairedWatchNodeId.isSafeIdentifier(256)) { "Invalid paired node ID" }
        require(pairedWatchDeviceId.matches(SAFE_DEVICE_ID)) { "Invalid paired watch device ID" }
        require(transportKeyId.matches(SAFE_KEY_ID)) { "Invalid transport key ID" }
        require(acknowledgementKeyId.matches(SAFE_KEY_ID)) { "Invalid acknowledgement key ID" }
        require(transportKeyId != acknowledgementKeyId) { "Transport and ACK keys must be purpose separated" }
    }

    private companion object {
        val SAFE_DEVICE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val SAFE_KEY_ID = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

/** Null means collection consent is not active; callers must fail closed. */
fun interface BridgeConsentLeaseProvider {
    fun currentLease(): ActiveBridgeConsentLease?
}

/** Resolves only purpose-specific HmacSHA256 acknowledgement keys. */
fun interface BridgeAcknowledgementKeyResolver {
    fun resolve(keyId: String): SecretKey?
}

/**
 * Fully formed command for the MessageClient adapter.
 *
 * The publisher must send these exact bytes to [targetNodeId] at [path]. It must not regenerate,
 * reinterpret, or redirect the receipt.
 */
class AuthenticatedReceiptCommand(
    val targetNodeId: String,
    val path: String,
    val consentGeneration: Long,
    authenticatedReceiptBytes: ByteArray,
) {
    private val immutableReceiptBytes = authenticatedReceiptBytes.copyOf()

    init {
        require(targetNodeId.isSafeIdentifier(256)) { "Invalid target node ID" }
        val batchId = path.removePrefix(PhoneDataLayerBridgeCoordinator.RECEIPT_PATH_PREFIX + "/")
        require(
            path == "${PhoneDataLayerBridgeCoordinator.RECEIPT_PATH_PREFIX}/$batchId" &&
                batchId.matches(Regex("[A-Za-z0-9._:-]{1,96}")),
        ) {
            "Invalid receipt path"
        }
        require(consentGeneration > 0L) { "Consent generation must be positive" }
        require(immutableReceiptBytes.isNotEmpty()) { "Authenticated receipt is empty" }
    }

    val receiptSizeBytes: Int get() = immutableReceiptBytes.size

    fun authenticatedReceiptBytesCopy(): ByteArray = immutableReceiptBytes.copyOf()
}

sealed interface ReceiptPublishResult {
    data class Published(val deliveryToken: String) : ReceiptPublishResult {
        init {
            require(deliveryToken.matches(Regex("[A-Za-z0-9._:-]{1,256}"))) {
                "Delivery token is invalid"
            }
        }
    }

    data class Failed(val detailCode: String = "receipt_publish_failed") : ReceiptPublishResult {
        init {
            require(detailCode.matches(SAFE_DETAIL_CODE)) { "Invalid publish failure code" }
        }
    }
}

/** Implemented by the bounded Google Play MessageClient adapter in the Android source set. */
fun interface DataLayerReceiptPublisher {
    fun publish(command: AuthenticatedReceiptCommand): ReceiptPublishResult
}

/** Result states deliberately distinguish durable receipt state from radio delivery state. */
sealed interface PhoneBridgeProcessingResult {
    data class AckPublished(
        val acknowledgement: BatchAcknowledgement,
        val deliveryToken: String,
    ) : PhoneBridgeProcessingResult {
        init {
            require(acknowledgement.disposition == ReceiptDisposition.ACK)
            require(acknowledgement.reason.isAckReason)
        }
    }

    data class NackPublished(
        val acknowledgement: BatchAcknowledgement,
        val deliveryToken: String,
    ) : PhoneBridgeProcessingResult {
        init {
            require(acknowledgement.disposition == ReceiptDisposition.NACK)
            require(!acknowledgement.reason.isAckReason)
        }
    }

    /** The durable result exists, but the authenticated receipt was not delivered. A retry is safe. */
    data class ReceiptDeliveryPending(
        val acknowledgement: BatchAcknowledgement,
        val command: AuthenticatedReceiptCommand?,
        val detailCode: String,
    ) : PhoneBridgeProcessingResult {
        init {
            require(detailCode.matches(SAFE_DETAIL_CODE)) { "Invalid delivery failure code" }
        }
    }

    /**
     * The durable batch result could not be durably staged for receipt delivery. It is never
     * silently treated as queued; the watch must retain and re-offer its canonical batch.
     */
    data class ReceiptDeliveryUnavailable(
        val acknowledgement: BatchAcknowledgement,
        val detailCode: String,
    ) : PhoneBridgeProcessingResult {
        init {
            require(detailCode.matches(SAFE_DETAIL_CODE)) { "Invalid delivery failure code" }
        }
    }

    /** A bounded retry terminal state. The exact receipt remains encrypted and auditable. */
    data class ReceiptDeliveryAbandoned(
        val acknowledgement: BatchAcknowledgement,
        val detailCode: String,
    ) : PhoneBridgeProcessingResult {
        init {
            require(detailCode == "receipt_retry_exhausted" || detailCode == "receipt_consent_stale") {
                "Invalid receipt abandonment reason"
            }
        }
    }

    /** Bytes were rejected and quarantined, but no identity-bound receipt could safely be emitted. */
    data class QuarantinedWithoutReceipt(
        val reason: ReceiptReason,
        val quarantineDisposition: QuarantineDisposition,
        val quarantineToken: String?,
        val detailCode: String,
    ) : PhoneBridgeProcessingResult

    /** A source, path, device, key, or consent-generation fence rejected the event. */
    data class GuardRejected(
        val quarantineDisposition: QuarantineDisposition,
        val quarantineToken: String?,
        val detailCode: String,
    ) : PhoneBridgeProcessingResult

    /** Events outside the VitalSignal batch namespace are not interpreted or quarantined. */
    data class Ignored(val detailCode: String = "unrelated_data_layer_path") : PhoneBridgeProcessingResult
}

internal val SAFE_DETAIL_CODE = Regex("[a-z0-9_.-]{1,96}")

private fun String.isSafeIdentifier(maximumBytes: Int): Boolean =
    isNotBlank() && toByteArray(Charsets.UTF_8).size <= maximumBytes
