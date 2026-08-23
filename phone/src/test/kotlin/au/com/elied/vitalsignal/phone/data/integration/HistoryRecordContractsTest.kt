package au.com.elied.vitalsignal.phone.data.integration

import au.com.elied.vitalsignal.governance.PilotCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRecordContractsTest {
    @Test
    fun quantitativeRecordRetainsUnitDeviceTimeAndGovernanceProvenance() {
        val record = record()
        val quantity = record.value as HistoryValue.Quantity

        assertEquals("mg/L", quantity.unit.code)
        assertEquals(MeasurementUnit.ucumSystemUri, quantity.unit.systemUri)
        assertEquals(SourceDeviceKind.EHR_SYSTEM, record.provenance.sourceDevice.kind)
        assertEquals(600, record.clinicalTime.zoneOffsetMinutes)
        assertEquals(4L, record.provenance.consentGeneration)
        assertEquals("validation-fhir-1", record.provenance.validationReceiptId)
        assertTrue(record.fhirLocator != null)
    }

    @Test
    fun fhirSourceCannotLoseFhirIdentityAndVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(fhirLocator = null)
        }
    }

    @Test
    fun nonFhirSourceCannotInventFhirLocator() {
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(
                provenance = provenance(
                    source = HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK,
                    revision = 1L,
                ),
            )
        }
    }

    @Test
    fun malformedPayloadDigestIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            provenance().copy(payloadSha256 = "not-a-sha256")
        }
    }

    @Test
    fun recordMetadataAndAdapterChangePageAreImmutableSnapshots() {
        val mutableMetadata = mutableMapOf("status" to "final")
        val snapshottedRecord = record().copy(sourceMetadata = mutableMetadata)
        val mutableChanges = mutableListOf<HistorySourceChange>(
            HistorySourceChange.Upsert(snapshottedRecord),
        )
        val page = HistoryChangePage(
            source = HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
            participantPseudonym = "participant-1",
            consentGeneration = 4L,
            validationReceiptId = "validation-fhir-1",
            changes = mutableChanges,
            nextCursor = HistoryChangeCursor(
                HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
                "cursor-2",
            ),
            hasMore = false,
        )

        mutableMetadata["status"] = "amended"
        mutableChanges.clear()

        assertEquals(mapOf("status" to "final"), snapshottedRecord.sourceMetadata)
        assertEquals(1, page.changes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (page.changes as MutableList<HistorySourceChange>).clear()
        }
    }

    private fun record(
        revision: Long = 1L,
        digest: String = "a".repeat(64),
    ) = CanonicalHistoryRecord(
        participantPseudonym = "participant-1",
        concept = CodedConcept(
            systemUri = "http://loinc.org",
            code = "1988-5",
            display = "C reactive protein",
        ),
        clinicalTime = ClinicalTimeRange(
            startEpochMillis = 5_000L,
            endEpochMillis = 5_000L,
            zoneOffsetMinutes = 600,
        ),
        value = HistoryValue.Quantity(3.5, MeasurementUnit.ucum("mg/L")),
        provenance = provenance(revision = revision, digest = digest),
        fhirLocator = FhirResourceLocator(
            release = FhirRelease.R4,
            resourceType = SupportedFhirResourceType.OBSERVATION,
            logicalId = "observation-1",
            metaVersionId = revision.toString(),
            serverBaseUriSha256 = "b".repeat(64),
        ),
    )

    private fun provenance(
        source: HistorySourceKind = HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR,
        revision: Long = 1L,
        digest: String = "a".repeat(64),
    ) = HistoryProvenance(
        sourceKey = SourceRecordKey(source, "medical-records", "record-1"),
        revision = SourceRevision(revision, "native-$revision"),
        sourceCreatedAtEpochMillis = 4_000L,
        sourceUpdatedAtEpochMillis = 5_000L + revision,
        retrievedAtEpochMillis = 8_000L + revision,
        adapterVersion = "history-adapter-v1",
        pilotCapability = when (source) {
            HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK ->
                PilotCapability.PHONE_SAMSUNG_HEALTH_HISTORY
            HistorySourceKind.HEALTH_CONNECT -> PilotCapability.PHONE_HEALTH_CONNECT_HISTORY
            HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR ->
                PilotCapability.PHONE_FHIR_MEDICAL_RECORDS
        },
        consentGeneration = 4L,
        pilotProtocolId = "pilot-protocol-1",
        validationReceiptId = "validation-fhir-1",
        sourceDevice = SourceDeviceDescriptor(
            kind = SourceDeviceKind.EHR_SYSTEM,
            manufacturer = "fixture-ehr",
            model = "fixture-server",
            softwareVersion = "1",
            pseudonymousDeviceId = "ehr-source-1",
            dataOriginPackage = "fixture.health.records",
        ),
        payloadSha256 = digest,
        sourceChangeCursorDigest = "c".repeat(64),
    )
}
