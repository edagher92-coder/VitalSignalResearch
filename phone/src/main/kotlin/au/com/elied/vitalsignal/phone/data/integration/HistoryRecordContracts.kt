package au.com.elied.vitalsignal.phone.data.integration

import au.com.elied.vitalsignal.governance.PilotCapability

private val sha256Pattern = Regex("^[0-9a-f]{64}$")

enum class HistorySourceKind {
    SAMSUNG_HEALTH_DATA_SDK,
    HEALTH_CONNECT,
    HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
}

data class SourceRecordKey(
    val source: HistorySourceKind,
    val namespace: String,
    val externalRecordId: String,
) {
    init {
        require(namespace.isNotBlank())
        require(externalRecordId.isNotBlank())
    }
}

enum class SourceDeviceKind {
    WATCH,
    PHONE,
    MEDICAL_DEVICE,
    MANUAL_ENTRY,
    EHR_SYSTEM,
    UNKNOWN,
}

/** Source device is required, even when that "device" is an EHR or manual-entry origin. */
data class SourceDeviceDescriptor(
    val kind: SourceDeviceKind,
    val manufacturer: String,
    val model: String,
    val softwareVersion: String,
    val pseudonymousDeviceId: String,
    val dataOriginPackage: String,
) {
    init {
        require(manufacturer.isNotBlank())
        require(model.isNotBlank())
        require(softwareVersion.isNotBlank())
        require(pseudonymousDeviceId.isNotBlank())
        require(dataOriginPackage.isNotBlank())
    }
}

data class ClinicalTimeRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val zoneOffsetMinutes: Int?,
) {
    init {
        require(startEpochMillis > 0L)
        require(endEpochMillis >= startEpochMillis)
        zoneOffsetMinutes?.let { require(it in -18 * 60..18 * 60) }
    }
}

data class CodedConcept(
    val systemUri: String,
    val code: String,
    val display: String,
) {
    init {
        require(systemUri.isNotBlank())
        require(code.isNotBlank())
        require(display.isNotBlank())
    }
}

data class MeasurementUnit(
    val systemUri: String,
    val code: String,
    val display: String,
) {
    init {
        require(systemUri.isNotBlank())
        require(code.isNotBlank())
        require(display.isNotBlank())
    }

    companion object {
        const val ucumSystemUri = "http://unitsofmeasure.org"

        fun ucum(code: String, display: String = code): MeasurementUnit =
            MeasurementUnit(ucumSystemUri, code, display)
    }
}

sealed interface HistoryValue {
    data class Quantity(
        val value: Double,
        val unit: MeasurementUnit,
    ) : HistoryValue {
        init {
            require(value.isFinite())
        }
    }

    data class Coded(val concept: CodedConcept) : HistoryValue

    data class Text(val value: String) : HistoryValue {
        init {
            require(value.isNotBlank())
        }
    }

    data class BooleanValue(val value: Boolean) : HistoryValue
}

data class SourceRevision(
    /** Monotonic sequence assigned by the source adapter for deterministic ordering. */
    val sequence: Long,
    /** Native version/change identifier retained exactly for later source reconciliation. */
    val opaqueVersion: String,
) : Comparable<SourceRevision> {
    init {
        require(sequence >= 0L)
        require(opaqueVersion.isNotBlank())
    }

    override fun compareTo(other: SourceRevision): Int = sequence.compareTo(other.sequence)

    /** Ordering is sequence-only; native version ties must be rejected separately. */
    fun conflictsAtSameSequence(other: SourceRevision): Boolean =
        sequence == other.sequence && this != other
}

data class HistoryProvenance(
    val sourceKey: SourceRecordKey,
    val revision: SourceRevision,
    val sourceCreatedAtEpochMillis: Long?,
    val sourceUpdatedAtEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val adapterVersion: String,
    val pilotCapability: PilotCapability,
    val consentGeneration: Long,
    val pilotProtocolId: String,
    val validationReceiptId: String,
    val sourceDevice: SourceDeviceDescriptor,
    /** SHA-256 of the source payload before app-owned canonicalisation. */
    val payloadSha256: String,
    /** Hash/token only; never place an OAuth token or credential here. */
    val sourceChangeCursorDigest: String? = null,
) {
    init {
        sourceCreatedAtEpochMillis?.let {
            require(it > 0L && it <= sourceUpdatedAtEpochMillis)
        }
        require(sourceUpdatedAtEpochMillis > 0L)
        require(retrievedAtEpochMillis >= sourceUpdatedAtEpochMillis)
        require(adapterVersion.isNotBlank())
        require(pilotCapability == sourceKey.source.requiredPilotCapability())
        require(consentGeneration > 0L)
        require(pilotProtocolId.isNotBlank())
        require(validationReceiptId.isNotBlank())
        require(sha256Pattern.matches(payloadSha256))
        sourceChangeCursorDigest?.let {
            require(sha256Pattern.matches(it))
        }
    }
}

enum class FhirRelease {
    R4,
    R4B,
}

enum class SupportedFhirResourceType {
    ALLERGY_INTOLERANCE,
    CONDITION,
    DIAGNOSTIC_REPORT,
    ENCOUNTER,
    IMMUNIZATION,
    MEDICATION_REQUEST,
    MEDICATION_STATEMENT,
    OBSERVATION,
    PROCEDURE,
}

data class FhirResourceLocator(
    val release: FhirRelease,
    val resourceType: SupportedFhirResourceType,
    val logicalId: String,
    val metaVersionId: String,
    val serverBaseUriSha256: String,
) {
    init {
        require(logicalId.isNotBlank())
        require(metaVersionId.isNotBlank())
        require(sha256Pattern.matches(serverBaseUriSha256))
    }
}

/**
 * Canonical history record shared by Samsung Health, Health Connect, and the
 * Health Connect Medical Records FHIR bridge. It does not flatten away source,
 * units, device, time zone, revision, or payload provenance.
 */
class CanonicalHistoryRecord(
    val participantPseudonym: String,
    val concept: CodedConcept,
    val clinicalTime: ClinicalTimeRange,
    val value: HistoryValue,
    val provenance: HistoryProvenance,
    val fhirLocator: FhirResourceLocator? = null,
    sourceMetadata: Map<String, String> = emptyMap(),
) {
    /** Snapshot source metadata at the proprietary/public adapter boundary. */
    val sourceMetadata: Map<String, String> = java.util.Map.copyOf(sourceMetadata)

    init {
        require(participantPseudonym.isNotBlank())
        require(this.sourceMetadata.keys.none(String::isBlank))
        require(this.sourceMetadata.values.none(String::isBlank))
        val isFhir = provenance.sourceKey.source ==
            HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR
        require(isFhir == (fhirLocator != null)) {
            "FHIR source records must retain a FHIR locator; non-FHIR records must not invent one"
        }
    }

    val key: SourceRecordKey get() = provenance.sourceKey

    fun copy(
        participantPseudonym: String = this.participantPseudonym,
        concept: CodedConcept = this.concept,
        clinicalTime: ClinicalTimeRange = this.clinicalTime,
        value: HistoryValue = this.value,
        provenance: HistoryProvenance = this.provenance,
        fhirLocator: FhirResourceLocator? = this.fhirLocator,
        sourceMetadata: Map<String, String> = this.sourceMetadata,
    ) = CanonicalHistoryRecord(
        participantPseudonym,
        concept,
        clinicalTime,
        value,
        provenance,
        fhirLocator,
        sourceMetadata,
    )

    override fun equals(other: Any?): Boolean = other is CanonicalHistoryRecord &&
        participantPseudonym == other.participantPseudonym && concept == other.concept &&
        clinicalTime == other.clinicalTime && value == other.value &&
        provenance == other.provenance && fhirLocator == other.fhirLocator &&
        sourceMetadata == other.sourceMetadata

    override fun hashCode(): Int = listOf(
        participantPseudonym,
        concept,
        clinicalTime,
        value,
        provenance,
        fhirLocator,
        sourceMetadata,
    ).hashCode()
}

/**
 * No SDK implementation is permitted to return canonical records without a
 * gate-issued read permit. Real adapters translate proprietary/public SDK types
 * only inside their implementation module.
 */
interface GovernedHistoryIngestionBoundary {
    suspend fun readChanges(
        permit: HistoryReadPermit,
        cursor: HistoryChangeCursor?,
    ): HistoryChangePage
}

data class HistoryChangeCursor(
    val source: HistorySourceKind,
    val opaqueCursor: String,
) {
    init {
        require(opaqueCursor.isNotBlank())
    }
}

class HistoryChangePage(
    val source: HistorySourceKind,
    val participantPseudonym: String,
    val consentGeneration: Long,
    val validationReceiptId: String,
    changes: List<HistorySourceChange>,
    val nextCursor: HistoryChangeCursor,
    val hasMore: Boolean,
) {
    /** Snapshot change pages because adapters commonly reuse mutable page buffers. */
    val changes: List<HistorySourceChange> = java.util.List.copyOf(changes)

    init {
        require(participantPseudonym.isNotBlank())
        require(consentGeneration > 0L)
        require(validationReceiptId.isNotBlank())
        require(nextCursor.source == source)
        require(this.changes.all { it.key.source == source })
        require(this.changes.all {
            it.participantPseudonym == participantPseudonym &&
                it.consentGeneration == consentGeneration &&
                it.validationReceiptId == validationReceiptId
        })
    }

    fun copy(
        source: HistorySourceKind = this.source,
        participantPseudonym: String = this.participantPseudonym,
        consentGeneration: Long = this.consentGeneration,
        validationReceiptId: String = this.validationReceiptId,
        changes: List<HistorySourceChange> = this.changes,
        nextCursor: HistoryChangeCursor = this.nextCursor,
        hasMore: Boolean = this.hasMore,
    ) = HistoryChangePage(
        source,
        participantPseudonym,
        consentGeneration,
        validationReceiptId,
        changes,
        nextCursor,
        hasMore,
    )

    override fun equals(other: Any?): Boolean = other is HistoryChangePage &&
        source == other.source && participantPseudonym == other.participantPseudonym &&
        consentGeneration == other.consentGeneration &&
        validationReceiptId == other.validationReceiptId && changes == other.changes &&
        nextCursor == other.nextCursor && hasMore == other.hasMore

    override fun hashCode(): Int = listOf(
        source,
        participantPseudonym,
        consentGeneration,
        validationReceiptId,
        changes,
        nextCursor,
        hasMore,
    ).hashCode()
}
