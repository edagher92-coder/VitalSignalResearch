package au.com.elied.vitalsignal.wear.transport

import android.content.Context
import android.net.Uri
import au.com.elied.vitalsignal.transport.BatchEnvelope
import au.com.elied.vitalsignal.transport.BatchEnvelopeCodec
import au.com.elied.vitalsignal.transport.OutboxAcknowledgementDecision
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

sealed interface BatchQueueResult {
    data class Queued(
        val batchId: String,
        val dataItemUri: String,
        val canonicalWireSha256: String,
        val consentGeneration: Long,
    ) : BatchQueueResult

    data class Failed(
        val batchId: String,
        val cause: Throwable,
    ) : BatchQueueResult
}

interface DataLayerBatchTransport {
    /** Queues a durable Data Item; this is not the phone's receipt acknowledgement. */
    fun enqueue(
        envelope: BatchEnvelope,
        consentGeneration: Long,
        onResult: (BatchQueueResult) -> Unit,
    )

    /** Removes only the VitalSignal Data Item named by an exact, durably claimed ACK decision. */
    fun removeAuthorized(
        queued: BatchQueueResult.Queued,
        authorization: OutboxAcknowledgementDecision.DeletionAuthorized,
        onComplete: (Result<Unit>) -> Unit,
    )
}

/** Public Data Layer implementation; the phone receiver and receipt path are separate adapters. */
class GooglePlayDataLayerBatchTransport(
    context: Context,
) : DataLayerBatchTransport {
    private val dataClient = Wearable.getDataClient(context.applicationContext)

    override fun enqueue(
        envelope: BatchEnvelope,
        consentGeneration: Long,
        onResult: (BatchQueueResult) -> Unit,
    ) {
        require(consentGeneration > 0L) { "Consent generation must be positive" }
        val path = "$BATCH_PATH/${envelope.batchId}"
        val encoded = BatchEnvelopeCodec.encode(envelope)
        val payloadRejection = WearDataItemPayloadPolicy.rejectionCode(encoded.size)
        if (payloadRejection != null) {
            onResult(
                BatchQueueResult.Failed(
                    batchId = envelope.batchId,
                    cause = IllegalArgumentException(
                        "$payloadRejection:${encoded.size}",
                    ),
                ),
            )
            return
        }
        // The phone bridge needs the generation as authenticated-ingress metadata while its
        // receiver still consumes the untouched canonical BatchEnvelope bytes. The phone
        // DataItem adapter extracts these two exact DataMap keys into DataLayerBatchEvent.
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putLong(DataLayerBatchDataMapContract.CONSENT_GENERATION_KEY, consentGeneration)
            dataMap.putByteArray(DataLayerBatchDataMapContract.CANONICAL_WIRE_KEY, encoded)
        }.asPutDataRequest()
            .setUrgent()

        dataClient.putDataItem(request)
            .addOnSuccessListener { item ->
                onResult(
                    BatchQueueResult.Queued(
                        batchId = envelope.batchId,
                        dataItemUri = item.uri.toString(),
                        canonicalWireSha256 = sha256Hex(encoded),
                        consentGeneration = consentGeneration,
                    ),
                )
            }
            .addOnFailureListener { error ->
                onResult(BatchQueueResult.Failed(envelope.batchId, error))
            }
    }

    override fun removeAuthorized(
        queued: BatchQueueResult.Queued,
        authorization: OutboxAcknowledgementDecision.DeletionAuthorized,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (queued.batchId != authorization.batchId) {
            onComplete(Result.failure(IllegalArgumentException("ACK batch does not match queued batch")))
            return
        }
        val dataItemUri = Uri.parse(queued.dataItemUri)
        if (dataItemUri.path != "$BATCH_PATH/${authorization.batchId}") {
            onComplete(Result.failure(IllegalArgumentException("Queued URI is outside the VitalSignal batch path")))
            return
        }
        dataClient.deleteDataItems(dataItemUri)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    private companion object {
        const val BATCH_PATH = "/v1/research/batches"
    }
}

/** Shared key names for the phone-side DataMapItem adapter. */
object DataLayerBatchDataMapContract {
    const val CONSENT_GENERATION_KEY = "consent_generation"
    const val CANONICAL_WIRE_KEY = "canonical_batch_envelope"
}

private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
