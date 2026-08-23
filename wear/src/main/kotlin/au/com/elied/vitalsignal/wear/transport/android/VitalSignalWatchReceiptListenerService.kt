package au.com.elied.vitalsignal.wear.transport.android

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Thin receipt-message adapter. Authentication and deletion remain outside this service. */
class VitalSignalWatchReceiptListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith(AuthenticatedOutboxWatchReceiptHandler.RECEIPT_PATH_PREFIX + "/")) return
        val copiedEvent = try {
            WatchReceiptMessageEvent(
                path = path,
                sourceNodeId = messageEvent.sourceNodeId,
                receiptBytes = messageEvent.data.copyOf(),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        WatchReceiptAndroidRuntime.dispatch(copiedEvent)
    }
}
