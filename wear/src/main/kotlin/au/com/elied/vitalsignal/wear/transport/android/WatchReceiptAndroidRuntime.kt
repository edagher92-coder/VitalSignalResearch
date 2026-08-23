package au.com.elied.vitalsignal.wear.transport.android

import au.com.elied.vitalsignal.transport.AcknowledgementKeyResolver
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementCodec
import au.com.elied.vitalsignal.transport.AuthenticatedAcknowledgementResult
import au.com.elied.vitalsignal.wear.transport.WatchAcknowledgementResult
import au.com.elied.vitalsignal.wear.transport.WatchOutboxCoordinator
import java.util.concurrent.atomic.AtomicReference

/** Immutable copy of a receipt MessageEvent. Google Play services objects never escape callbacks. */
class WatchReceiptMessageEvent(
    val path: String,
    val sourceNodeId: String,
    receiptBytes: ByteArray,
) {
    private val immutableReceiptBytes = receiptBytes.copyOf()

    init {
        require(path.startsWith("/") && path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES)
        require(sourceNodeId.isNotBlank() && sourceNodeId.toByteArray(Charsets.UTF_8).size <= MAX_NODE_BYTES)
        require(immutableReceiptBytes.size <= MAX_RECEIPT_BYTES)
    }

    val receiptSizeBytes: Int get() = immutableReceiptBytes.size

    fun receiptBytesCopy(): ByteArray = immutableReceiptBytes.copyOf()

    private companion object {
        const val MAX_PATH_BYTES = 512
        const val MAX_NODE_BYTES = 256
        const val MAX_RECEIPT_BYTES = 128 * 1024
    }
}

/** No handler is installed by default, so a callback can never imply consent or delete data. */
object WatchReceiptAndroidRuntime {
    private val activeRegistration = AtomicReference<WatchRuntimeRegistration?>(null)

    fun install(handler: WatchReceiptMessageHandler): WatchReceiptRuntimeLease? {
        val registration = WatchRuntimeRegistration(handler)
        if (!activeRegistration.compareAndSet(null, registration)) return null
        return WatchReceiptRuntimeLease(registration, activeRegistration)
    }

    fun dispatch(event: WatchReceiptMessageEvent): WatchReceiptDispatchResult {
        val handler = activeRegistration.get()?.handler
            ?: return WatchReceiptDispatchResult.Rejected("watch_receipt_runtime_unavailable")
        return try {
            WatchReceiptDispatchResult.Processed(handler.handle(event))
        } catch (_: RuntimeException) {
            WatchReceiptDispatchResult.Rejected("watch_receipt_runtime_exception")
        }
    }
}

internal class WatchRuntimeRegistration(val handler: WatchReceiptMessageHandler)

fun interface WatchReceiptMessageHandler {
    fun handle(event: WatchReceiptMessageEvent): WatchReceiptHandlingResult
}

class WatchReceiptRuntimeLease internal constructor(
    private val registration: WatchRuntimeRegistration,
    private val activeRegistration: AtomicReference<WatchRuntimeRegistration?>,
) : AutoCloseable {
    override fun close() {
        activeRegistration.compareAndSet(registration, null)
    }
}

sealed interface WatchReceiptDispatchResult {
    data class Processed(val result: WatchReceiptHandlingResult) : WatchReceiptDispatchResult

    data class Rejected(val detailCode: String) : WatchReceiptDispatchResult
}

sealed interface WatchReceiptHandlingResult {
    data class Processed(val acknowledgementResult: WatchAcknowledgementResult) :
        WatchReceiptHandlingResult

    data class Rejected(val detailCode: String) : WatchReceiptHandlingResult {
        init {
            require(detailCode.matches(Regex("[a-z0-9_.-]{1,96}")))
        }
    }
}

data class ActiveWatchReceiptLease(
    val consentGeneration: Long,
    val pairedPhoneNodeId: String,
) {
    init {
        require(consentGeneration > 0L)
        require(
            pairedPhoneNodeId.isNotBlank() &&
                pairedPhoneNodeId.toByteArray(Charsets.UTF_8).size <= 256,
        )
    }
}

/** Null means receipt processing is not currently authorized. */
fun interface WatchReceiptLeaseProvider {
    fun currentLease(): ActiveWatchReceiptLease?
}

internal fun interface WatchAcknowledgementProcessor {
    fun process(encodedAcknowledgement: ByteArray, consentGeneration: Long): WatchAcknowledgementResult
}

/**
 * Binds source node and receipt path, authenticates once at the Android boundary, then delegates
 * to [WatchOutboxCoordinator], which independently re-authenticates and exact-matches the durable
 * outbox record before it can stage or perform deletion.
 */
class AuthenticatedOutboxWatchReceiptHandler private constructor(
    private val acknowledgementProcessor: WatchAcknowledgementProcessor,
    private val acknowledgementKeyResolver: AcknowledgementKeyResolver,
    private val receiptLeaseProvider: WatchReceiptLeaseProvider,
) : WatchReceiptMessageHandler {
    constructor(
        outboxCoordinator: WatchOutboxCoordinator,
        acknowledgementKeyResolver: AcknowledgementKeyResolver,
        receiptLeaseProvider: WatchReceiptLeaseProvider,
    ) : this(
        acknowledgementProcessor = WatchAcknowledgementProcessor(
            outboxCoordinator::handleAcknowledgement,
        ),
        acknowledgementKeyResolver = acknowledgementKeyResolver,
        receiptLeaseProvider = receiptLeaseProvider,
    )

    internal constructor(
        acknowledgementProcessor: WatchAcknowledgementProcessor,
        acknowledgementKeyResolver: AcknowledgementKeyResolver,
        receiptLeaseProvider: WatchReceiptLeaseProvider,
        @Suppress("UNUSED_PARAMETER") testBoundary: Unit,
    ) : this(
        acknowledgementProcessor = acknowledgementProcessor,
        acknowledgementKeyResolver = acknowledgementKeyResolver,
        receiptLeaseProvider = receiptLeaseProvider,
    )

    override fun handle(event: WatchReceiptMessageEvent): WatchReceiptHandlingResult {
        val pathBatchId = event.path.exactReceiptBatchId()
            ?: return WatchReceiptHandlingResult.Rejected("receipt_path_invalid")
        val lease = receiptLeaseProvider.currentLease()
            ?: return WatchReceiptHandlingResult.Rejected("receipt_consent_not_active")
        if (event.sourceNodeId != lease.pairedPhoneNodeId) {
            return WatchReceiptHandlingResult.Rejected("receipt_source_node_mismatch")
        }

        val wire = event.receiptBytesCopy()
        val authenticated = try {
            AuthenticatedAcknowledgementCodec.decodeAndAuthenticate(
                wire,
                acknowledgementKeyResolver,
            )
        } catch (_: RuntimeException) {
            return WatchReceiptHandlingResult.Rejected("receipt_authentication_exception")
        }
        val decoded = when (authenticated) {
            is AuthenticatedAcknowledgementResult.Authenticated -> authenticated
            is AuthenticatedAcknowledgementResult.UnknownKey ->
                return WatchReceiptHandlingResult.Rejected("receipt_key_unavailable")
            AuthenticatedAcknowledgementResult.AuthenticationFailed ->
                return WatchReceiptHandlingResult.Rejected("receipt_authentication_failed")
            AuthenticatedAcknowledgementResult.Malformed ->
                return WatchReceiptHandlingResult.Rejected("receipt_wire_invalid")
        }
        if (decoded.acknowledgement.batchId != pathBatchId) {
            return WatchReceiptHandlingResult.Rejected("receipt_path_batch_mismatch")
        }
        if (receiptLeaseProvider.currentLease() != lease) {
            return WatchReceiptHandlingResult.Rejected("receipt_consent_changed")
        }

        return WatchReceiptHandlingResult.Processed(
            acknowledgementProcessor.process(wire, lease.consentGeneration),
        )
    }

    companion object {
        const val RECEIPT_PATH_PREFIX = "/v1/research/receipts"
    }
}

private fun String.exactReceiptBatchId(): String? {
    val prefix = AuthenticatedOutboxWatchReceiptHandler.RECEIPT_PATH_PREFIX + "/"
    if (!startsWith(prefix)) return null
    return removePrefix(prefix).takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,96}")) }
}
