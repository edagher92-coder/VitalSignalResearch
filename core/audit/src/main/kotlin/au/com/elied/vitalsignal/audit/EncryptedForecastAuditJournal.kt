package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.model.HealthForecast
import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import au.com.elied.vitalsignal.storage.AppendQuarantineReason
import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import au.com.elied.vitalsignal.storage.LocalEncryptedRecord
import au.com.elied.vitalsignal.storage.StorageAppendResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Encrypted, restart-safe adapter for the prospective forecast journal.
 *
 * The supplied store is expected to be dedicated to forecast-audit records.
 * Any authenticated record with another content type, any storage quarantine,
 * or any payload that cannot be decoded exactly makes recovery unavailable.
 */
class EncryptedForecastAuditJournal(
    private val store: EncryptedAppendOnlyRecordStore,
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) : AppendOnlyForecastAuditJournal {
    private val lock = Any()

    init {
        require(maximumRecords in 1..HARD_MAXIMUM_RECORDS)
    }

    override fun recover(): ForecastJournalRecoveryResult = synchronized(lock) {
        when (val recovered = recoverDecoded()) {
            is DecodedRecovery.Ready -> ForecastJournalRecoveryResult.Recovered(recovered.records)
            is DecodedRecovery.Unreadable -> ForecastJournalRecoveryResult.Unreadable(recovered.reason)
        }
    }

    override fun append(
        event: ProspectiveForecastAuditEvent,
        expectedRevision: Long,
    ): ForecastJournalAppendResult = synchronized(lock) {
        val recovered = recoverDecoded()
        if (recovered is DecodedRecovery.Unreadable) {
            return@synchronized ForecastJournalAppendResult.Unavailable(recovered.reason)
        }
        recovered as DecodedRecovery.Ready

        recovered.records.firstOrNull { it.event.eventId == event.eventId }?.let { existing ->
            return@synchronized if (existing.event == event) {
                ForecastJournalAppendResult.ExactDuplicate(existing)
            } else {
                ForecastJournalAppendResult.ConflictingReplay(
                    "Forecast audit event ID was reused with different content",
                )
            }
        }

        val actualRevision = recovered.records.lastOrNull()?.revision ?: 0L
        if (expectedRevision != actualRevision) {
            return@synchronized ForecastJournalAppendResult.RevisionConflict(actualRevision)
        }
        if (recovered.records.size >= maximumRecords) {
            return@synchronized ForecastJournalAppendResult.NotAppended(
                "Forecast audit journal capacity reached",
            )
        }

        val encoded = try {
            ForecastAuditBinaryCodec.encode(event)
        } catch (_: RuntimeException) {
            return@synchronized ForecastJournalAppendResult.NotAppended(
                "Forecast audit event could not be encoded",
            )
        }
        val localRecord = try {
            LocalEncryptedRecord(
                recordId = event.eventId,
                sequence = actualRevision + 1L,
                createdEpochMillis = event.occurredAtEpochMillis,
                contentType = CONTENT_TYPE,
                payload = encoded,
            )
        } catch (_: RuntimeException) {
            return@synchronized ForecastJournalAppendResult.NotAppended(
                "Forecast audit event metadata is invalid",
            )
        }

        val result = try {
            store.append(localRecord)
        } catch (_: RuntimeException) {
            // The durable outcome is unknown after an arbitrary storage exception.
            return@synchronized ForecastJournalAppendResult.Unavailable(
                "Encrypted forecast journal append outcome is unknown",
            )
        }

        when (result) {
            is StorageAppendResult.Accepted -> {
                val accepted = result.acceptedRecord.record
                if (
                    accepted.sequence != localRecord.sequence ||
                    accepted.recordId != localRecord.recordId ||
                    accepted.createdEpochMillis != localRecord.createdEpochMillis ||
                    accepted.contentType != CONTENT_TYPE
                ) {
                    ForecastJournalAppendResult.Unavailable(
                        "Encrypted forecast journal returned an inconsistent receipt",
                    )
                } else {
                    ForecastJournalAppendResult.Appended(
                        ForecastJournalRecord(accepted.sequence, event),
                    )
                }
            }

            is StorageAppendResult.Duplicate -> duplicateAfterRefresh(event)

            is StorageAppendResult.Quarantined -> when (result.reason) {
                AppendQuarantineReason.REPLAY_CONFLICT ->
                    ForecastJournalAppendResult.ConflictingReplay(
                        "Forecast audit event ID was reused with different content",
                    )

                AppendQuarantineReason.OUT_OF_SEQUENCE -> when (val refreshed = recoverDecoded()) {
                    is DecodedRecovery.Ready -> ForecastJournalAppendResult.RevisionConflict(
                        refreshed.records.lastOrNull()?.revision ?: 0L,
                    )
                    is DecodedRecovery.Unreadable -> ForecastJournalAppendResult.Unavailable(
                        refreshed.reason,
                    )
                }

                AppendQuarantineReason.PAYLOAD_TOO_LARGE ->
                    ForecastJournalAppendResult.NotAppended(
                        "Forecast audit payload exceeded the encrypted store bound",
                    )

                AppendQuarantineReason.RECOVERY_BLOCKED ->
                    ForecastJournalAppendResult.Unavailable(
                        "Encrypted forecast journal recovery is blocked",
                    )
            }
        }
    }

    private fun duplicateAfterRefresh(
        event: ProspectiveForecastAuditEvent,
    ): ForecastJournalAppendResult = when (val refreshed = recoverDecoded()) {
        is DecodedRecovery.Unreadable -> ForecastJournalAppendResult.Unavailable(refreshed.reason)
        is DecodedRecovery.Ready -> {
            val existing = refreshed.records.firstOrNull { it.event.eventId == event.eventId }
                ?: return ForecastJournalAppendResult.Unavailable(
                    "Encrypted store reported a duplicate that recovery could not locate",
                )
            if (existing.event == event) {
                ForecastJournalAppendResult.ExactDuplicate(existing)
            } else {
                ForecastJournalAppendResult.ConflictingReplay(
                    "Forecast audit event ID was reused with different content",
                )
            }
        }
    }

    private fun recoverDecoded(): DecodedRecovery {
        val report = try {
            store.recover()
        } catch (_: RuntimeException) {
            return DecodedRecovery.Unreadable("Encrypted forecast journal could not be read")
        }
        if (report.quarantined.isNotEmpty()) {
            return DecodedRecovery.Unreadable(
                "Encrypted forecast journal contains quarantined storage records",
            )
        }
        if (report.accepted.size > maximumRecords) {
            return DecodedRecovery.Unreadable("Encrypted forecast journal exceeds its record bound")
        }

        val decoded = ArrayList<ForecastJournalRecord>(report.accepted.size)
        val eventIds = HashSet<String>(report.accepted.size)
        report.accepted.forEachIndexed { index, accepted ->
            val local = accepted.record
            val expectedRevision = index + 1L
            if (local.sequence != expectedRevision) {
                return DecodedRecovery.Unreadable(
                    "Encrypted forecast journal revisions are not consecutive",
                )
            }
            if (local.contentType != CONTENT_TYPE) {
                return DecodedRecovery.Unreadable(
                    "Encrypted forecast journal contains an unsupported record type",
                )
            }
            val event = try {
                ForecastAuditBinaryCodec.decode(local.payloadCopy())
            } catch (_: RuntimeException) {
                return DecodedRecovery.Unreadable(
                    "Encrypted forecast journal contains an unreadable event",
                )
            }
            if (
                event.eventId != local.recordId ||
                event.occurredAtEpochMillis != local.createdEpochMillis ||
                !eventIds.add(event.eventId)
            ) {
                return DecodedRecovery.Unreadable(
                    "Encrypted forecast journal record metadata is inconsistent",
                )
            }
            decoded += ForecastJournalRecord(local.sequence, event)
        }
        return DecodedRecovery.Ready(decoded)
    }

    private sealed interface DecodedRecovery {
        data class Ready(val records: List<ForecastJournalRecord>) : DecodedRecovery
        data class Unreadable(val reason: String) : DecodedRecovery
    }

    companion object {
        internal const val CONTENT_TYPE = "application/vnd.vitalsignal.forecast-audit.v2"
        const val DEFAULT_MAXIMUM_RECORDS: Int = 100_000
        const val HARD_MAXIMUM_RECORDS: Int = 1_000_000
    }
}

/** Explicit binary codec; Java object serialization is intentionally forbidden. */
internal object ForecastAuditBinaryCodec {
    const val MAX_ENCODED_BYTES: Int = 256 * 1024

    private const val MAGIC = 0x56464134 // VFA4
    private const val SCHEMA_VERSION = 2
    private const val COMMITTED_EVENT = 1
    private const val CHECK_IN_EVENT = 2
    private const val REVEALED_EVENT = 3
    private const val OUTCOME_EVENT = 4
    private const val MINIMUM_EVENT_BYTES = Int.SIZE_BYTES + Short.SIZE_BYTES + 1

    private const val MAX_IDENTIFIER_BYTES = 96
    private const val MAX_OUTCOME_NAME_BYTES = 1_024
    private const val MAX_DEFINITION_BYTES = 4_096
    private const val MAX_VERSION_BYTES = 384
    private const val MAX_FEATURE_ID_BYTES = 640
    private const val MAX_FEATURE_COUNT = 256
    private const val SHA256_BYTES_AS_HEX = 64

    fun encode(event: ProspectiveForecastAuditEvent): ByteArray {
        val encoded = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(SCHEMA_VERSION)
                when (event) {
                    is ForecastCommittedEvent -> {
                        output.writeByte(COMMITTED_EVENT)
                        output.writeBoundedUtf8(event.eventId, MAX_IDENTIFIER_BYTES)
                        output.writeLong(event.committedAtEpochMillis)
                        output.writeBoundedUtf8(
                            event.canonicalFeatureSnapshotSha256,
                            SHA256_BYTES_AS_HEX,
                        )
                        output.writeForecast(event.forecast)
                    }

                    is PreRevealCheckInStoredEvent -> {
                        output.writeByte(CHECK_IN_EVENT)
                        output.writeBoundedUtf8(event.eventId, MAX_IDENTIFIER_BYTES)
                        output.writeBoundedUtf8(event.forecastId, MAX_IDENTIFIER_BYTES)
                        output.writeLong(event.checkIn.recordedAtEpochMillis)
                        output.writeBoundedUtf8(
                            event.checkIn.contextSnapshotSha256,
                            SHA256_BYTES_AS_HEX,
                        )
                    }

                    is ForecastRevealedEvent -> {
                        output.writeByte(REVEALED_EVENT)
                        output.writeBoundedUtf8(event.eventId, MAX_IDENTIFIER_BYTES)
                        output.writeBoundedUtf8(event.forecastId, MAX_IDENTIFIER_BYTES)
                        output.writeLong(event.revealedAtEpochMillis)
                    }

                    is ForecastOutcomeStoredEvent -> {
                        output.writeByte(OUTCOME_EVENT)
                        output.writeBoundedUtf8(event.eventId, MAX_IDENTIFIER_BYTES)
                        output.writeBoundedUtf8(event.forecastId, MAX_IDENTIFIER_BYTES)
                        output.writeBoundedUtf8(
                            event.observation.endpointId,
                            MAX_IDENTIFIER_BYTES,
                        )
                        output.writeBoundedUtf8(
                            event.observation.endpointVersion,
                            MAX_VERSION_BYTES,
                        )
                        output.writeBoundedUtf8(
                            event.observation.endpointDefinitionSha256,
                            SHA256_BYTES_AS_HEX,
                        )
                        output.writeLong(event.observation.targetStartEpochMillis)
                        output.writeLong(event.observation.targetEndEpochMillis)
                        output.writeBoolean(event.observation.sourceAssessmentAtEpochMillis != null)
                        event.observation.sourceAssessmentAtEpochMillis?.let(output::writeLong)
                        output.writeLong(event.observation.observedAtEpochMillis)
                        output.writeBoolean(event.observation.observedOutcome != null)
                        event.observation.observedOutcome?.let(output::writeDouble)
                        output.writeBoundedUtf8(
                            event.observation.outcomeRecordSha256,
                            SHA256_BYTES_AS_HEX,
                        )
                    }
                }
            }
            buffer.toByteArray()
        }
        require(encoded.size in MINIMUM_EVENT_BYTES..MAX_ENCODED_BYTES) {
            "Forecast audit event exceeds its encoded bound"
        }
        return encoded
    }

    fun decode(encoded: ByteArray): ProspectiveForecastAuditEvent {
        require(encoded.size in MINIMUM_EVENT_BYTES..MAX_ENCODED_BYTES) {
            "Forecast audit event size is invalid"
        }
        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                require(input.readInt() == MAGIC) { "Forecast audit magic is invalid" }
                require(input.readUnsignedShort() == SCHEMA_VERSION) {
                    "Forecast audit schema is unsupported"
                }
                val event = when (input.readUnsignedByte()) {
                    COMMITTED_EVENT -> ForecastCommittedEvent(
                        eventId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
                        committedAtEpochMillis = input.readLong(),
                        canonicalFeatureSnapshotSha256 = input.readBoundedUtf8(
                            SHA256_BYTES_AS_HEX,
                        ),
                        forecast = input.readForecast(),
                    )

                    CHECK_IN_EVENT -> PreRevealCheckInStoredEvent(
                        PreRevealContextCheckIn(
                            eventId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
                            forecastId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
                            recordedAtEpochMillis = input.readLong(),
                            contextSnapshotSha256 = input.readBoundedUtf8(SHA256_BYTES_AS_HEX),
                        ),
                    )

                    REVEALED_EVENT -> ForecastRevealedEvent(
                        eventId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
                        forecastId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
                        revealedAtEpochMillis = input.readLong(),
                    )

                    OUTCOME_EVENT -> {
                        val eventId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES)
                        val forecastId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES)
                        val endpointId = input.readBoundedUtf8(MAX_IDENTIFIER_BYTES)
                        val endpointVersion = input.readBoundedUtf8(MAX_VERSION_BYTES)
                        val endpointDigest = input.readBoundedUtf8(SHA256_BYTES_AS_HEX)
                        val targetStart = input.readLong()
                        val targetEnd = input.readLong()
                        val sourceAssessmentAt = if (input.readBoolean()) input.readLong() else null
                        val observedAt = input.readLong()
                        val outcome = if (input.readBoolean()) input.readDouble() else null
                        ForecastOutcomeStoredEvent(
                            ForecastOutcomeObservation(
                                eventId = eventId,
                                forecastId = forecastId,
                                endpointId = endpointId,
                                endpointVersion = endpointVersion,
                                endpointDefinitionSha256 = endpointDigest,
                                targetStartEpochMillis = targetStart,
                                targetEndEpochMillis = targetEnd,
                                sourceAssessmentAtEpochMillis = sourceAssessmentAt,
                                observedAtEpochMillis = observedAt,
                                observedOutcome = outcome,
                                outcomeRecordSha256 = input.readBoundedUtf8(SHA256_BYTES_AS_HEX),
                            ),
                        )
                    }

                    else -> throw IllegalArgumentException("Forecast audit event type is unsupported")
                }
                require(input.available() == 0) { "Trailing forecast audit bytes are not allowed" }
                return event
            }
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Forecast audit event is malformed", error)
        } catch (error: Exception) {
            throw IllegalArgumentException("Forecast audit event is malformed", error)
        }
    }

    private fun DataOutputStream.writeForecast(forecast: HealthForecast) {
        writeBoundedUtf8(forecast.id, MAX_IDENTIFIER_BYTES)
        writeLong(forecast.createdAtEpochMillis)
        writeEndpoint(forecast.endpoint)
        writeDouble(forecast.probability)
        writeDouble(forecast.lowerBound)
        writeDouble(forecast.upperBound)
        writeDouble(forecast.confidence)
        writeBoundedUtf8(forecast.modelVersion, MAX_VERSION_BYTES)
        require(forecast.featureSnapshotIds.size in 1..MAX_FEATURE_COUNT)
        writeInt(forecast.featureSnapshotIds.size)
        forecast.featureSnapshotIds.forEach { writeBoundedUtf8(it, MAX_FEATURE_ID_BYTES) }
        writeFeatureSchema(forecast.featureSchema)
        writeLong(forecast.cutoffEpochMillis)
        writeLong(forecast.targetStartEpochMillis)
        writeLong(forecast.targetEndEpochMillis)
        writeBoundedUtf8(forecast.policyVersion, MAX_VERSION_BYTES)
        writeDouble(forecast.intervalCoverage)
        writeBoundedUtf8(forecast.featureSnapshotHash, SHA256_BYTES_AS_HEX)
        writeLong(forecast.maximumCommitLagMillis)
    }

    private fun DataInputStream.readForecast(): HealthForecast {
        val id = readBoundedUtf8(MAX_IDENTIFIER_BYTES)
        val createdAt = readLong()
        val endpoint = readEndpoint()
        val probability = readDouble()
        val lowerBound = readDouble()
        val upperBound = readDouble()
        val confidence = readDouble()
        val modelVersion = readBoundedUtf8(MAX_VERSION_BYTES)
        val featureCount = readInt()
        require(featureCount in 1..MAX_FEATURE_COUNT) { "Feature count is invalid" }
        val features = ArrayList<String>(featureCount)
        repeat(featureCount) { features += readBoundedUtf8(MAX_FEATURE_ID_BYTES) }
        val featureSchema = readFeatureSchema()
        return HealthForecast(
            id = id,
            createdAtEpochMillis = createdAt,
            endpoint = endpoint,
            probability = probability,
            lowerBound = lowerBound,
            upperBound = upperBound,
            confidence = confidence,
            modelVersion = modelVersion,
            featureSnapshotIds = features,
            featureSchema = featureSchema,
            cutoffEpochMillis = readLong(),
            targetStartEpochMillis = readLong(),
            targetEndEpochMillis = readLong(),
            policyVersion = readBoundedUtf8(MAX_VERSION_BYTES),
            intervalCoverage = readDouble(),
            featureSnapshotHash = readBoundedUtf8(SHA256_BYTES_AS_HEX),
            maximumCommitLagMillis = readLong(),
        )
    }

    private fun DataOutputStream.writeEndpoint(endpoint: ForecastEndpointDefinition) {
        writeBoundedUtf8(endpoint.id, MAX_IDENTIFIER_BYTES)
        writeBoundedUtf8(endpoint.version, MAX_VERSION_BYTES)
        writeBoundedUtf8(endpoint.displayLabel, MAX_OUTCOME_NAME_BYTES)
        writeBoundedUtf8(endpoint.positiveClassDefinition, MAX_DEFINITION_BYTES)
        writeBoundedUtf8(endpoint.anchor, MAX_VERSION_BYTES)
        writeBoundedUtf8(endpoint.windowSemantics.name, MAX_VERSION_BYTES)
        writeLong(endpoint.targetStartOffsetMillis)
        writeLong(endpoint.targetEndOffsetMillis)
        writeBoundedUtf8(endpoint.definitionSha256, SHA256_BYTES_AS_HEX)
    }

    private fun DataInputStream.readEndpoint(): ForecastEndpointDefinition =
        ForecastEndpointDefinition(
            id = readBoundedUtf8(MAX_IDENTIFIER_BYTES),
            version = readBoundedUtf8(MAX_VERSION_BYTES),
            displayLabel = readBoundedUtf8(MAX_OUTCOME_NAME_BYTES),
            positiveClassDefinition = readBoundedUtf8(MAX_DEFINITION_BYTES),
            anchor = readBoundedUtf8(MAX_VERSION_BYTES),
            windowSemantics = ForecastWindowSemantics.valueOf(
                readBoundedUtf8(MAX_VERSION_BYTES),
            ),
            targetStartOffsetMillis = readLong(),
            targetEndOffsetMillis = readLong(),
            definitionSha256 = readBoundedUtf8(SHA256_BYTES_AS_HEX),
        )

    private fun DataOutputStream.writeFeatureSchema(schema: ForecastFeatureSchemaDefinition) {
        writeBoundedUtf8(schema.id, MAX_IDENTIFIER_BYTES)
        writeBoundedUtf8(schema.version, MAX_VERSION_BYTES)
        require(schema.featureVersions.size in 1..MAX_FEATURE_COUNT)
        writeInt(schema.featureVersions.size)
        schema.featureVersions.toSortedMap().forEach { (id, version) ->
            writeBoundedUtf8(id, MAX_FEATURE_ID_BYTES)
            writeBoundedUtf8(version, MAX_VERSION_BYTES)
        }
        writeBoundedUtf8(schema.standardizationProtocol, MAX_DEFINITION_BYTES)
        writeBoundedUtf8(schema.definitionSha256, SHA256_BYTES_AS_HEX)
    }

    private fun DataInputStream.readFeatureSchema(): ForecastFeatureSchemaDefinition {
        val id = readBoundedUtf8(MAX_IDENTIFIER_BYTES)
        val version = readBoundedUtf8(MAX_VERSION_BYTES)
        val count = readInt()
        require(count in 1..MAX_FEATURE_COUNT) { "Feature schema count is invalid" }
        val featureVersions = linkedMapOf<String, String>()
        repeat(count) {
            val featureId = readBoundedUtf8(MAX_FEATURE_ID_BYTES)
            require(featureId !in featureVersions) { "Duplicate feature schema ID" }
            featureVersions[featureId] = readBoundedUtf8(MAX_VERSION_BYTES)
        }
        return ForecastFeatureSchemaDefinition(
            id = id,
            version = version,
            featureVersions = featureVersions,
            standardizationProtocol = readBoundedUtf8(MAX_DEFINITION_BYTES),
            definitionSha256 = readBoundedUtf8(SHA256_BYTES_AS_HEX),
        )
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..maximumBytes) { "Forecast audit string exceeds its bound" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
        val length = readInt()
        require(length in 1..maximumBytes && length <= available()) {
            "Forecast audit string length is invalid"
        }
        val bytes = ByteArray(length).also(::readFully)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}
