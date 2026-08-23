package au.com.elied.vitalsignal.wear.baseline.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import au.com.elied.vitalsignal.model.ActivityState
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesDevice
import au.com.elied.vitalsignal.wear.baseline.WearHealthServicesPoint
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.guava.await

/** Actual AndroidX 1.1.0-rc02 registration adapter; no Samsung SDK type enters this path. */
class AndroidxPassivePlatformRegistration(
    private val passiveClient: PassiveMonitoringClient,
) : PassivePlatformRegistration {
    override suspend fun supportedChannels(): Set<WatchDataChannel> {
        val supported = passiveClient.getCapabilitiesAsync().await().supportedDataTypesPassiveMonitoring
        return buildSet {
            if (DataType.HEART_RATE_BPM in supported) add(WatchDataChannel.PASSIVE_HEART_RATE)
            if (DataType.STEPS in supported) add(WatchDataChannel.PASSIVE_STEPS)
        }
    }

    override suspend fun registerService(channels: Set<WatchDataChannel>) {
        require(channels.isNotEmpty())
        require(channels.all { it in ConsentFencedPassiveRuntime.SUPPORTED_CHANNELS })
        val builder = PassiveListenerConfig.builder()
        when (channels) {
            setOf(WatchDataChannel.PASSIVE_HEART_RATE) ->
                builder.setDataTypes(setOf(DataType.HEART_RATE_BPM))

            setOf(WatchDataChannel.PASSIVE_STEPS) ->
                builder.setDataTypes(setOf(DataType.STEPS))

            else -> builder.setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS))
        }
        passiveClient.setPassiveListenerServiceAsync(
            VitalSignalPassiveListenerService::class.java,
            builder.build(),
        ).await()
    }

    override suspend fun clearService() {
        passiveClient.clearPassiveListenerServiceAsync().await()
    }
}

/** Exact permission policy for the two public passive channels. */
class AndroidPassivePermissionGate(
    private val context: Context,
) : PassivePermissionGate {
    override fun missingPermissions(channels: Set<WatchDataChannel>): Set<String> = buildSet {
        if (WatchDataChannel.PASSIVE_STEPS in channels && !granted(ACTIVITY_RECOGNITION)) {
            add(ACTIVITY_RECOGNITION)
        }
        if (WatchDataChannel.PASSIVE_HEART_RATE in channels) {
            if (Build.VERSION.SDK_INT >= 36) {
                if (!granted(READ_HEART_RATE)) add(READ_HEART_RATE)
                if (!granted(READ_HEALTH_DATA_IN_BACKGROUND)) add(READ_HEALTH_DATA_IN_BACKGROUND)
            } else {
                if (!granted(BODY_SENSORS)) add(BODY_SENSORS)
                if (Build.VERSION.SDK_INT >= 33 && !granted(BODY_SENSORS_BACKGROUND)) {
                    add(BODY_SENSORS_BACKGROUND)
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
        const val BODY_SENSORS = "android.permission.BODY_SENSORS"
        const val BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND"
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    }
}

/**
 * Composition entry point used only after WearPilotActivationGate has issued a lease.
 * It does not mint consent, infer device identity, or connect to the simulator.
 */
object AndroidxPassiveAdapterFactory {
    @Volatile
    private var controller: GovernedPassiveCollectionController? = null

    fun create(context: Context): GovernedPassiveCollectionController {
        controller?.let { return it }
        val appContext = context.applicationContext
        return synchronized(this) {
            controller ?: run {
                val client = HealthServices.getClient(appContext).passiveMonitoringClient
                GovernedPassiveCollectionController(
                    platform = AndroidxPassivePlatformRegistration(client),
                    permissionGate = AndroidPassivePermissionGate(appContext),
                    runtime = AndroidxPassiveServiceRuntime.runtime,
                ).also { controller = it }
            }
        }
    }
}

/** The only exported passive component; its manifest permission restricts binding to WHS. */
class VitalSignalPassiveListenerService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        AndroidxPassiveServiceRuntime.onNewDataPointsReceived(dataPoints)
    }
}

internal object AndroidxPassiveServiceRuntime {
    val runtime = ConsentFencedPassiveRuntime()

    @Volatile
    private var lastResult: PassiveDispatchResult = PassiveDispatchResult.RuntimeNotInstalled

    fun lastDispatchResult(): PassiveDispatchResult = lastResult

    fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val context = runtime.deliveryContext()
        if (context == null) {
            lastResult = PassiveDispatchResult.RuntimeNotInstalled
            return
        }
        val receivedAtEpochMillis = System.currentTimeMillis()
        val bootInstant = Instant.ofEpochMilli(
            receivedAtEpochMillis - SystemClock.elapsedRealtime(),
        )
        val points = try {
            AndroidxPassivePointMapper.map(
                dataPoints = dataPoints,
                context = context,
                receivedAtEpochMillis = receivedAtEpochMillis,
                bootInstant = bootInstant,
            )
        } catch (_: Throwable) {
            runtime.clear(context.consentGeneration)
            lastResult = PassiveDispatchResult.StorageFailed(
                code = "platform_batch_mapping_failed_runtime_closed",
                acceptedBeforeFailure = 0,
                rejectedBeforeFailure = 0,
            )
            return
        }
        lastResult = runtime.dispatch(context.consentGeneration, points)
    }
}

/** Converts WHS boot-relative clocks to epoch clocks without using callback receipt time. */
internal object AndroidxPassivePointMapper {
    fun map(
        dataPoints: DataPointContainer,
        context: PassiveDeliveryContext,
        receivedAtEpochMillis: Long,
        bootInstant: Instant,
    ): List<WearHealthServicesPoint> = buildList {
        dataPoints.getData(DataType.HEART_RATE_BPM).forEach { point ->
            val measuredAt = point.getTimeInstant(bootInstant).toEpochMilli()
            add(
                point(
                    channel = WatchDataChannel.PASSIVE_HEART_RATE,
                    startEpochMillis = measuredAt,
                    endEpochMillis = measuredAt,
                    receivedAtEpochMillis = receivedAtEpochMillis,
                    value = point.value,
                    device = context.device,
                    recordDiscriminator = point.timeDurationFromBoot.toNanos().toString(),
                ),
            )
        }
        dataPoints.getData(DataType.STEPS).forEach { point ->
            add(
                point(
                    channel = WatchDataChannel.PASSIVE_STEPS,
                    startEpochMillis = point.getStartInstant(bootInstant).toEpochMilli(),
                    endEpochMillis = point.getEndInstant(bootInstant).toEpochMilli(),
                    receivedAtEpochMillis = receivedAtEpochMillis,
                    value = point.value.toDouble(),
                    device = context.device,
                    recordDiscriminator =
                        "${point.startDurationFromBoot.toNanos()}:${point.endDurationFromBoot.toNanos()}",
                ),
            )
        }
    }

    private fun point(
        channel: WatchDataChannel,
        startEpochMillis: Long,
        endEpochMillis: Long,
        receivedAtEpochMillis: Long,
        value: Double,
        device: WearHealthServicesDevice,
        recordDiscriminator: String,
    ): WearHealthServicesPoint = WearHealthServicesPoint(
        recordId = stableRecordId(
            channel = channel,
            device = device,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            value = value,
            discriminator = recordDiscriminator,
        ),
        channel = channel,
        measurementStartEpochMillis = startEpochMillis,
        measurementEndEpochMillis = endEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        value = value,
        originPackage = HEALTH_SERVICES_PACKAGE,
        device = device,
        quality = passiveQuality(channel),
        activityState = ActivityState.UNKNOWN,
    )

    private fun passiveQuality(channel: WatchDataChannel): SignalQuality = when (channel) {
        WatchDataChannel.PASSIVE_HEART_RATE -> SignalQuality(
            score = 0.55,
            coverage = 0.50,
            contact = 0.50,
            timestampContinuity = 0.70,
            reasons = listOf(
                "Public passive API does not expose contact or motion quality for this point",
                "Passive sampling and batch cadence vary by device",
            ),
            evaluatorVersion = "wear-health-services-public-v1",
        )

        WatchDataChannel.PASSIVE_STEPS -> SignalQuality(
            score = 0.70,
            coverage = 0.70,
            contact = 1.0,
            timestampContinuity = 0.80,
            reasons = listOf("Step deltas are watch-derived and may arrive in variable batches"),
            evaluatorVersion = "wear-health-services-public-v1",
        )

        else -> error("Unsupported passive channel")
    }

    private fun stableRecordId(
        channel: WatchDataChannel,
        device: WearHealthServicesDevice,
        startEpochMillis: Long,
        endEpochMillis: Long,
        value: Double,
        discriminator: String,
    ): String {
        val canonical = listOf(
            channel.name,
            device.stableDeviceAlias,
            device.firmwareGeneration,
            startEpochMillis.toString(),
            endEpochMillis.toString(),
            value.toString(),
            discriminator,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "public-${channel.name.lowercase()}-$digest"
    }

    private const val HEALTH_SERVICES_PACKAGE = "com.google.android.wearable.healthservices"
}
