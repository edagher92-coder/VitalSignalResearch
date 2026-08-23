package au.com.elied.vitalsignal.phone.data.bridge.android

import au.com.elied.vitalsignal.phone.data.bridge.DataLayerBatchEvent
import au.com.elied.vitalsignal.phone.data.bridge.PhoneDataLayerBridgeCoordinator
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Thin Android adapter for watch batch DataItems.
 *
 * WearableListenerService callbacks are delivered off the main thread. Values are copied before
 * the callback returns; no DataItem/DataMap object escapes into durable or cryptographic code.
 * Malformed metadata and an uninstalled runtime are rejected without acknowledgement. The
 * installed runtime itself observes `ReceiptDeliveryPending` and invokes its mandatory recovery-
 * scheduling seam, so this callback does not silently own or drop volatile retry state.
 */
class VitalSignalPhoneDataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val receivedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = event.dataItem
            val uri = item.uri
            val path = uri.path ?: return@forEach
            if (!path.isExactBatchPath()) return@forEach
            val sourceNodeId = uri.host ?: return@forEach

            val dataMap = try {
                DataMapItem.fromDataItem(item).dataMap
            } catch (_: RuntimeException) {
                return@forEach
            }
            val extracted = try {
                if (!dataMap.containsKey(PhoneDataLayerDataMapContract.CANONICAL_WIRE_KEY)) {
                    return@forEach
                }
                val canonicalWire = dataMap.getByteArray(
                    PhoneDataLayerDataMapContract.CANONICAL_WIRE_KEY,
                ) ?: return@forEach
                if (canonicalWire.size !in 1..DataLayerBatchEvent.MAX_CANONICAL_WIRE_BYTES) {
                    return@forEach
                }
                val consentGeneration = if (
                    dataMap.containsKey(PhoneDataLayerDataMapContract.CONSENT_GENERATION_KEY)
                ) {
                    dataMap.getLong(PhoneDataLayerDataMapContract.CONSENT_GENERATION_KEY)
                } else {
                    MISSING_CONSENT_GENERATION
                }
                canonicalWire to consentGeneration
            } catch (_: RuntimeException) {
                return@forEach
            }

            val copiedEvent = try {
                DataLayerBatchEvent(
                    path = path,
                    sourceNodeId = sourceNodeId,
                    receivedAtEpochMillis = receivedAtEpochMillis,
                    consentGeneration = extracted.second,
                    wireBytes = extracted.first,
                )
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            PhoneDataLayerAndroidRuntime.dispatch(copiedEvent)
        }
    }

    private companion object {
        const val MISSING_CONSENT_GENERATION = 0L
    }
}

private fun String.isExactBatchPath(): Boolean {
    val prefix = PhoneDataLayerBridgeCoordinator.BATCH_PATH_PREFIX + "/"
    if (!startsWith(prefix)) return false
    return removePrefix(prefix).matches(Regex("[A-Za-z0-9._:-]{1,96}"))
}
