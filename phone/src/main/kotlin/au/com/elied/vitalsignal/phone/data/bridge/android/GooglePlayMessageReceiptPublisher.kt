package au.com.elied.vitalsignal.phone.data.bridge.android

import android.content.Context
import android.os.Looper
import au.com.elied.vitalsignal.phone.data.bridge.AuthenticatedReceiptCommand
import au.com.elied.vitalsignal.phone.data.bridge.DataLayerReceiptPublisher
import au.com.elied.vitalsignal.phone.data.bridge.ReceiptPublishResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Exact-node, bounded, synchronous publisher for use from a listener/background worker only. */
class GooglePlayMessageReceiptPublisher(
    context: Context,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : DataLayerReceiptPublisher {
    private val messageClient: MessageClient = Wearable.getMessageClient(context.applicationContext)
    private val engine = BoundedReceiptPublishEngine(
        timeoutMillis = timeoutMillis,
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        sender = { nodeId, path, payload, timeout ->
            Tasks.await(
                messageClient.sendMessage(nodeId, path, payload),
                timeout,
                TimeUnit.MILLISECONDS,
            )
        },
    )

    override fun publish(command: AuthenticatedReceiptCommand): ReceiptPublishResult =
        engine.publish(command)

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun interface BoundedMessageSender {
    fun send(
        targetNodeId: String,
        path: String,
        payload: ByteArray,
        timeoutMillis: Long,
    ): Int
}

/** Platform-independent decision shell retained separately so timeout/error behavior is testable. */
internal class BoundedReceiptPublishEngine(
    private val timeoutMillis: Long,
    private val isMainThread: () -> Boolean,
    private val sender: BoundedMessageSender,
) {
    init {
        require(timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS)
    }

    fun publish(command: AuthenticatedReceiptCommand): ReceiptPublishResult {
        if (isMainThread()) return ReceiptPublishResult.Failed("main_thread_publish_rejected")
        val requestId = try {
            sender.send(
                targetNodeId = command.targetNodeId,
                path = command.path,
                payload = command.authenticatedReceiptBytesCopy(),
                timeoutMillis = timeoutMillis,
            )
        } catch (_: TimeoutException) {
            return ReceiptPublishResult.Failed("receipt_publish_timeout")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ReceiptPublishResult.Failed("receipt_publish_interrupted")
        } catch (_: ExecutionException) {
            return ReceiptPublishResult.Failed("receipt_publish_task_failed")
        } catch (_: RuntimeException) {
            return ReceiptPublishResult.Failed("receipt_publish_exception")
        }
        return ReceiptPublishResult.Published("message-$requestId")
    }

    private companion object {
        const val MIN_TIMEOUT_MILLIS = 250L
        const val MAX_TIMEOUT_MILLIS = 15_000L
    }
}
