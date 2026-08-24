package au.com.elied.vitalsignal.phone.data.samsung

import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.ConsentGrantVerifier
import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PilotGateRequest
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.governance.ValidationReceiptVerifier
import au.com.elied.vitalsignal.phone.data.integration.HistoryConsentGrant
import au.com.elied.vitalsignal.phone.data.integration.HistoryDataScope
import au.com.elied.vitalsignal.phone.data.integration.HistoryPermissionState
import au.com.elied.vitalsignal.phone.data.integration.HistoryPilotGate
import au.com.elied.vitalsignal.phone.data.integration.HistoryPilotGateContext
import au.com.elied.vitalsignal.phone.data.integration.HistoryPilotGateDecision
import au.com.elied.vitalsignal.phone.data.integration.HistoryReadPermit
import au.com.elied.vitalsignal.phone.data.integration.HistoryReadRequest
import au.com.elied.vitalsignal.phone.data.integration.HistoryRuntimeCapability
import au.com.elied.vitalsignal.phone.data.integration.HistorySourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SamsungHealthDataSourceTest {
    @Test
    fun exactActiveSamsungPermitAllowsOnlyCoveredTypes() = runBlocking {
        val adapter = RecordingAdapter(listOf(record()))
        val source = SamsungHealthDataSource(adapter)

        val result = source.read(
            permit(scopes = setOf(HistoryDataScope.HEART_RATE)),
            setOf(SamsungHealthDataType.HEART_RATE),
            RANGE,
            NOW,
        )

        assertEquals(1, result.size)
        assertEquals(1, adapter.readCount)
    }

    @Test
    fun wrongSourceExpiredOrInsufficientScopeNeverReachesSdk() {
        val cases = listOf(
            permit(source = HistorySourceKind.HEALTH_CONNECT),
            permit(expiresAt = NOW),
            permit(scopes = setOf(HistoryDataScope.SLEEP)),
        )
        cases.forEach { candidate ->
            val adapter = RecordingAdapter(listOf(record()))
            val source = SamsungHealthDataSource(adapter)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    source.read(
                        candidate,
                        setOf(SamsungHealthDataType.HEART_RATE),
                        RANGE,
                        NOW,
                    )
                }
            }
            assertEquals(0, adapter.readCount)
        }
    }

    @Test
    fun compoundSamsungTypeRequiresEveryConsentScope() {
        val source = SamsungHealthDataSource(RecordingAdapter(emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                source.read(
                    permit(scopes = setOf(HistoryDataScope.SLEEP)),
                    setOf(SamsungHealthDataType.SLEEP_APNEA_REPORT),
                    RANGE,
                    NOW,
                )
            }
        }
    }

    @Test
    fun adapterCannotSmuggleWrongTypeOrOutOfRangeRecord() {
        val wrongType = record().copy(type = SamsungHealthDataType.SLEEP)
        val outOfRange = record().copy(endEpochMillis = RANGE.untilEpochMillis + 1L)
        listOf(wrongType, outOfRange).forEach { smuggled ->
            val source = SamsungHealthDataSource(RecordingAdapter(listOf(smuggled)))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    source.read(
                        permit(scopes = setOf(HistoryDataScope.HEART_RATE)),
                        setOf(SamsungHealthDataType.HEART_RATE),
                        RANGE,
                        NOW,
                    )
                }
            }
        }
    }

    @Test
    fun samsungRecordSnapshotsMetadataAndNestedQualityReasons() {
        val mutableMetadata = mutableMapOf("origin" to "watch")
        val mutableReasons = mutableListOf("contact stable")
        val record = SamsungHealthRecord(
            externalId = "record-snapshot",
            type = SamsungHealthDataType.HEART_RATE,
            metric = SensorMetric.HEART_RATE,
            startEpochMillis = 1_000L,
            endEpochMillis = 2_000L,
            value = 61.0,
            unit = "bpm",
            quality = SignalQuality(0.95, reasons = mutableReasons),
            sourceDevice = "watch-pseudonym",
            metadata = mutableMetadata,
        )

        mutableMetadata["origin"] = "changed"
        mutableReasons[0] = "changed"

        assertEquals(mapOf("origin" to "watch"), record.metadata)
        assertEquals(listOf("contact stable"), record.quality.reasons)
    }

    private class RecordingAdapter(
        private val records: List<SamsungHealthRecord>,
    ) : SamsungHealthSdkAdapter {
        var readCount = 0
        override suspend fun availability(): SamsungDataAvailability = SamsungDataAvailability.Ready
        override suspend fun requestReadAccess(types: Set<SamsungHealthDataType>) =
            SamsungAccessResult.Granted(types)
        override suspend fun read(types: Set<SamsungHealthDataType>, range: SamsungTimeRange) =
            records.also { readCount += 1 }
    }

    private fun permit(
        source: HistorySourceKind = HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK,
        scopes: Set<HistoryDataScope> = setOf(HistoryDataScope.HEART_RATE),
        expiresAt: Long = NOW + 1_000L,
    ): HistoryReadPermit {
        val capability = when (source) {
            HistorySourceKind.SAMSUNG_HEALTH_DATA_SDK ->
                PilotCapability.PHONE_SAMSUNG_HEALTH_HISTORY
            HistorySourceKind.HEALTH_CONNECT -> PilotCapability.PHONE_HEALTH_CONNECT_HISTORY
            HistorySourceKind.HEALTH_CONNECT_MEDICAL_RECORDS_FHIR ->
                PilotCapability.PHONE_FHIR_MEDICAL_RECORDS
        }
        val coreScope = when (capability) {
            PilotCapability.PHONE_SAMSUNG_HEALTH_HISTORY -> ConsentScope.SAMSUNG_HEALTH_HISTORY
            PilotCapability.PHONE_HEALTH_CONNECT_HISTORY -> ConsentScope.HEALTH_CONNECT_HISTORY
            else -> ConsentScope.MEDICAL_RECORDS
        }
        val issuedAt = minOf(NOW - 1_000L, expiresAt - 1L)
        val coreConsent = ConsentGrant(
            subjectPseudonym = "pilot-1",
            generation = 1L,
            scopes = setOf(coreScope),
            issuedAtEpochMillis = 1L,
            expiresAtEpochMillis = NOW + 100_000L,
            consentTextSha256 = "a".repeat(64),
            signerKeyId = "fixture-consent-key",
            signature = byteArrayOf(1),
        )
        val receipt = ValidationReceipt(
            receiptId = "validation-1",
            capability = capability,
            appVersion = "0.6.0-research",
            deviceModel = "Galaxy S25 Ultra fixture",
            firmwareGeneration = "fixture-fw-1",
            dataSchemaVersion = "history-v1",
            issuedAtEpochMillis = 1L,
            expiresAtEpochMillis = NOW + 100_000L,
            evidenceIds = listOf("fixture-history"),
            evidenceBundleSha256 = "b".repeat(64),
            issuerKeyId = "fixture-validation-key",
            signature = byteArrayOf(2),
        )
        val decision = PilotAccessGate(
            ConsentGrantVerifier { it.signature.contentEquals(byteArrayOf(1)) },
            ValidationReceiptVerifier { it.signature.contentEquals(byteArrayOf(2)) },
        ).evaluate(
            PilotGateRequest(
                capability = capability,
                subjectPseudonym = "pilot-1",
                consentGeneration = 1L,
                appVersion = receipt.appVersion,
                deviceModel = receipt.deviceModel,
                firmwareGeneration = receipt.firmwareGeneration,
                dataSchemaVersion = receipt.dataSchemaVersion,
                evaluatedAtEpochMillis = issuedAt,
                collectionPaused = false,
                recoveryRequired = false,
            ),
            coreConsent,
            listOf(receipt),
        )
        return (HistoryPilotGate.evaluate(
            HistoryReadRequest(source, scopes),
            HistoryPilotGateContext(
                pilotFeatureEnabled = true,
                governanceDecision = decision,
                consent = HistoryConsentGrant(
                    consentId = "consent-1",
                    generation = 1L,
                    participantPseudonym = "pilot-1",
                    protocolId = "private-pilot-v1",
                    allowedSources = setOf(source),
                    allowedScopes = scopes,
                    validFromEpochMillis = 1L,
                    validUntilEpochMillis = expiresAt,
                ),
                capability = HistoryRuntimeCapability(
                    source = source,
                    adapterInstalled = true,
                    sourceAvailable = true,
                    readPermission = HistoryPermissionState.GRANTED,
                ),
                nowEpochMillis = issuedAt,
            ),
        ) as HistoryPilotGateDecision.Allowed).permit
    }

    private fun record() = SamsungHealthRecord(
        externalId = "record-1",
        type = SamsungHealthDataType.HEART_RATE,
        metric = SensorMetric.HEART_RATE,
        startEpochMillis = RANGE.fromEpochMillis + 10L,
        endEpochMillis = RANGE.untilEpochMillis - 10L,
        value = 62.0,
        unit = "bpm",
        quality = SignalQuality(0.95),
        sourceDevice = "watch-pseudonym",
    )

    private companion object {
        const val NOW = 10_000L
        val RANGE = SamsungTimeRange(1_000L, 9_000L)
    }
}
