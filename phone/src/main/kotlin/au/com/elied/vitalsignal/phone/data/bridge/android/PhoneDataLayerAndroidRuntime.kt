package au.com.elied.vitalsignal.phone.data.bridge.android

import au.com.elied.vitalsignal.phone.data.bridge.DataLayerBatchEvent
import au.com.elied.vitalsignal.phone.data.bridge.PhoneBridgeProcessingResult
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local hand-off from the exported Google Play services callback to the governed bridge.
 *
 * There is deliberately no default coordinator, consent, key, or storage implementation. Until
 * application startup installs the fully constructed private-pilot runtime, ingress fails closed.
 * An installation cannot silently replace a live installation; the owner must close its exact
 * lease first.
 */
object PhoneDataLayerAndroidRuntime {
    private val activeRegistration = AtomicReference<PhoneRuntimeRegistration?>(null)

    fun install(
        handler: PhoneDataLayerBatchHandler,
        receiptRecoveryRequestor: PhoneReceiptRecoveryRequestor,
    ): PhoneDataLayerRuntimeLease? {
        val registration = PhoneRuntimeRegistration(handler, receiptRecoveryRequestor)
        if (!activeRegistration.compareAndSet(null, registration)) return null
        val initialRecoveryRequest = registration.safelyRequestRecovery(
            PhoneReceiptRecoveryReason.PROCESS_RUNTIME_INSTALLED,
        )
        return PhoneDataLayerRuntimeLease(registration, activeRegistration, initialRecoveryRequest)
    }

    fun dispatch(event: DataLayerBatchEvent): PhoneDataLayerDispatchResult {
        val registration = activeRegistration.get()
            ?: return PhoneDataLayerDispatchResult.Rejected("phone_bridge_runtime_unavailable")
        return try {
            val result = registration.handler.handle(event)
            val recoveryRequest = if (result is PhoneBridgeProcessingResult.ReceiptDeliveryPending) {
                registration.safelyRequestRecovery(PhoneReceiptRecoveryReason.RECEIPT_DELIVERY_PENDING)
            } else {
                PhoneReceiptRecoveryRequestResult.NotRequired
            }
            PhoneDataLayerDispatchResult.Processed(result, recoveryRequest)
        } catch (_: RuntimeException) {
            PhoneDataLayerDispatchResult.Rejected("phone_bridge_runtime_exception")
        }
    }
}

internal class PhoneRuntimeRegistration(
    val handler: PhoneDataLayerBatchHandler,
    private val receiptRecoveryRequestor: PhoneReceiptRecoveryRequestor,
) {
    fun safelyRequestRecovery(reason: PhoneReceiptRecoveryReason): PhoneReceiptRecoveryRequestResult = try {
        receiptRecoveryRequestor.request(reason)
    } catch (_: RuntimeException) {
        PhoneReceiptRecoveryRequestResult.Failed("receipt_recovery_request_exception")
    }
}

fun interface PhoneDataLayerBatchHandler {
    fun handle(event: DataLayerBatchEvent): PhoneBridgeProcessingResult
}

/**
 * Composition seam for WorkManager, JobScheduler, or another reviewed Android scheduler.
 * Implementations must invoke `PhoneDataLayerBridgeCoordinator.retryPendingReceipts`; this source
 * module deliberately does not claim that a scheduler has been installed or device-tested.
 */
fun interface PhoneReceiptRecoveryRequestor {
    fun request(reason: PhoneReceiptRecoveryReason): PhoneReceiptRecoveryRequestResult
}

enum class PhoneReceiptRecoveryReason {
    PROCESS_RUNTIME_INSTALLED,
    RECEIPT_DELIVERY_PENDING,
}

sealed interface PhoneReceiptRecoveryRequestResult {
    data class Requested(val requestToken: String) : PhoneReceiptRecoveryRequestResult {
        init {
            require(requestToken.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        }
    }

    data class Failed(val detailCode: String) : PhoneReceiptRecoveryRequestResult {
        init {
            require(detailCode.matches(Regex("[a-z0-9_.-]{1,96}")))
        }
    }

    data object NotRequired : PhoneReceiptRecoveryRequestResult
}

class PhoneDataLayerRuntimeLease internal constructor(
    private val registration: PhoneRuntimeRegistration,
    private val activeRegistration: AtomicReference<PhoneRuntimeRegistration?>,
    val initialRecoveryRequestResult: PhoneReceiptRecoveryRequestResult,
) : AutoCloseable {
    override fun close() {
        activeRegistration.compareAndSet(registration, null)
    }
}

sealed interface PhoneDataLayerDispatchResult {
    data class Processed(
        val result: PhoneBridgeProcessingResult,
        val receiptRecoveryRequestResult: PhoneReceiptRecoveryRequestResult,
    ) : PhoneDataLayerDispatchResult

    data class Rejected(val detailCode: String) : PhoneDataLayerDispatchResult {
        init {
            require(detailCode.matches(Regex("[a-z0-9_.-]{1,96}")))
        }
    }
}

/** These names are the on-wire contract written by GooglePlayDataLayerBatchTransport. */
internal object PhoneDataLayerDataMapContract {
    const val CONSENT_GENERATION_KEY = "consent_generation"
    const val CANONICAL_WIRE_KEY = "canonical_batch_envelope"
}
