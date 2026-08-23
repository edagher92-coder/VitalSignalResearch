package au.com.elied.vitalsignal.phone.data.samsung

import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.phone.data.integration.HistoryDataScope
import au.com.elied.vitalsignal.phone.data.integration.HistoryReadPermit
import au.com.elied.vitalsignal.phone.data.integration.HistorySourceKind

/** Proprietary/public SDK calls are isolated behind this module-internal raw boundary. */
internal interface SamsungHealthSdkAdapter {
    suspend fun availability(): SamsungDataAvailability
    suspend fun requestReadAccess(types: Set<SamsungHealthDataType>): SamsungAccessResult
    suspend fun read(
        types: Set<SamsungHealthDataType>,
        range: SamsungTimeRange,
    ): List<SamsungHealthRecord>
}

/**
 * The only app-facing Samsung Health boundary. Every real read must carry a
 * short-lived gate-issued permit for the exact source and data scopes. The raw
 * adapter cannot be reached by presentation or analytics code.
 */
open class SamsungHealthDataSource internal constructor(
    private val adapter: SamsungHealthSdkAdapter,
) {
    suspend fun availability(): SamsungDataAvailability = adapter.availability()

    /** OS permission prompting is separate from data access and must be user initiated. */
    suspend fun requestReadAccess(types: Set<SamsungHealthDataType>): SamsungAccessResult {
        require(types.isNotEmpty())
        return adapter.requestReadAccess(types)
    }

    suspend fun read(
        permit: HistoryReadPermit,
        types: Set<SamsungHealthDataType>,
        range: SamsungTimeRange,
        nowEpochMillis: Long,
    ): List<SamsungHealthRecord> {
        require(types.isNotEmpty())
        require(permit.source == HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK) {
            "Samsung Health read requires an exact Samsung source permit"
        }
        require(permit.isValidAt(nowEpochMillis)) { "History read permit is expired or not active" }
        val requiredScopes = types.flatMapTo(linkedSetOf()) { it.requiredScopes }
        require(permit.scopes.containsAll(requiredScopes)) {
            "History read permit does not cover every requested Samsung data type"
        }

        val records = adapter.read(types.toSet(), range)
        require(records.all { record ->
            record.type in types &&
                record.startEpochMillis >= range.fromEpochMillis &&
                record.endEpochMillis <= range.untilEpochMillis &&
                record.endEpochMillis >= record.startEpochMillis
        }) { "Samsung adapter returned a record outside the permitted request" }
        return java.util.List.copyOf(records)
    }
}

enum class SamsungHealthDataType(internal val requiredScopes: Set<HistoryDataScope>) {
    HEART_RATE(setOf(HistoryDataScope.HEART_RATE)),
    SLEEP(setOf(HistoryDataScope.SLEEP)),
    STEP_COUNT(setOf(HistoryDataScope.ACTIVITY_AND_EXERCISE)),
    BLOOD_OXYGEN(setOf(HistoryDataScope.BLOOD_OXYGEN)),
    SKIN_TEMPERATURE(setOf(HistoryDataScope.SKIN_TEMPERATURE)),
    ENERGY_SCORE(setOf(HistoryDataScope.VITALS, HistoryDataScope.ACTIVITY_AND_EXERCISE)),
    EXERCISE(setOf(HistoryDataScope.ACTIVITY_AND_EXERCISE)),
    IRREGULAR_HEART_RHYTHM_NOTIFICATION(setOf(HistoryDataScope.HEART_RATE)),
    SLEEP_APNEA_REPORT(setOf(HistoryDataScope.SLEEP, HistoryDataScope.BLOOD_OXYGEN)),
}

data class SamsungTimeRange(
    val fromEpochMillis: Long,
    val untilEpochMillis: Long,
) {
    init {
        require(fromEpochMillis >= 0L)
        require(fromEpochMillis < untilEpochMillis)
    }
}

class SamsungHealthRecord(
    val externalId: String,
    val type: SamsungHealthDataType,
    val metric: SensorMetric?,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val value: Double?,
    val unit: String?,
    quality: SignalQuality,
    val sourceDevice: String?,
    metadata: Map<String, String> = emptyMap(),
) {
    /** Immutable adapter-boundary snapshots, including nested quality-reason evidence. */
    val metadata: Map<String, String> = java.util.Map.copyOf(metadata)
    val quality: SignalQuality = quality.copy()

    init {
        require(externalId.isNotBlank())
        require(startEpochMillis >= 0L)
        require(endEpochMillis >= startEpochMillis)
        require(value == null || value.isFinite())
        require(this.metadata.keys.none(String::isBlank))
        require(this.metadata.values.none(String::isBlank))
    }

    fun copy(
        externalId: String = this.externalId,
        type: SamsungHealthDataType = this.type,
        metric: SensorMetric? = this.metric,
        startEpochMillis: Long = this.startEpochMillis,
        endEpochMillis: Long = this.endEpochMillis,
        value: Double? = this.value,
        unit: String? = this.unit,
        quality: SignalQuality = this.quality,
        sourceDevice: String? = this.sourceDevice,
        metadata: Map<String, String> = this.metadata,
    ) = SamsungHealthRecord(
        externalId,
        type,
        metric,
        startEpochMillis,
        endEpochMillis,
        value,
        unit,
        quality,
        sourceDevice,
        metadata,
    )

    override fun equals(other: Any?): Boolean = other is SamsungHealthRecord &&
        externalId == other.externalId && type == other.type && metric == other.metric &&
        startEpochMillis == other.startEpochMillis && endEpochMillis == other.endEpochMillis &&
        value == other.value && unit == other.unit && quality == other.quality &&
        sourceDevice == other.sourceDevice && metadata == other.metadata

    override fun hashCode(): Int = listOf(
        externalId,
        type,
        metric,
        startEpochMillis,
        endEpochMillis,
        value,
        unit,
        quality,
        sourceDevice,
        metadata,
    ).hashCode()
}

sealed interface SamsungDataAvailability {
    data object Ready : SamsungDataAvailability
    data class Unavailable(val reason: String) : SamsungDataAvailability
    data class UpgradeRequired(val minimumVersion: String) : SamsungDataAvailability
}

sealed interface SamsungAccessResult {
    data class Granted(val types: Set<SamsungHealthDataType>) : SamsungAccessResult
    data class PartiallyGranted(
        val granted: Set<SamsungHealthDataType>,
        val denied: Set<SamsungHealthDataType>,
    ) : SamsungAccessResult
    data class Denied(val types: Set<SamsungHealthDataType>) : SamsungAccessResult
}

/** Used until a licensed Samsung AAR is placed in phone/libs. */
class UnavailableSamsungHealthDataSource : SamsungHealthDataSource(UnavailableSamsungHealthSdkAdapter)

private object UnavailableSamsungHealthSdkAdapter : SamsungHealthSdkAdapter {
    override suspend fun availability(): SamsungDataAvailability =
        SamsungDataAvailability.Unavailable(
            reason = "Samsung Health Data SDK AAR is not installed in this research build.",
        )

    override suspend fun requestReadAccess(types: Set<SamsungHealthDataType>): SamsungAccessResult =
        SamsungAccessResult.Denied(types)

    override suspend fun read(
        types: Set<SamsungHealthDataType>,
        range: SamsungTimeRange,
    ): List<SamsungHealthRecord> = emptyList()
}
