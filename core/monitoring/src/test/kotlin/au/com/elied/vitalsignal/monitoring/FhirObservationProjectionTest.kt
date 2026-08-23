package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FhirObservationProjectionTest {
    private val fixture = MonitoringTestFixture()

    @Test
    fun boundHeartRateDraftUsesSafeIdsReviewedCodingAndDurableAudit() {
        val audits = mutableListOf<ClinicalExportAuditEntry>()
        val projector = FhirObservationProjector { audits += it; true }
        val session = fhirSession(SensorMetric.HEART_RATE)
        val sample = fixture.sample(session)

        val draft = projector.project(
            session,
            sample,
            fixture.sharePermit(session),
            MonitoringTestFixture.NOW,
        )

        assertTrue(draft.observationId.matches(Regex("[A-Za-z0-9\\-.]{1,64}")))
        assertEquals("http://loinc.org", draft.code.system)
        assertEquals("8867-4", draft.code.code)
        assertEquals("http://unitsofmeasure.org", draft.quantity.system)
        assertEquals("/min", draft.quantity.code)
        assertEquals(sample.sessionId, draft.sessionId)
        assertEquals(sample.sampleId, draft.sampleId)
        assertEquals(sample.streamId, draft.streamId)
        assertEquals(sample.sequence, draft.sequence)
        assertEquals(sample.source, draft.source)
        assertEquals(sample.dataSchemaVersion, draft.dataSchemaVersion)
        assertEquals(sample.consentGeneration, draft.consentGeneration)
        assertEquals(sample.quality.score, draft.qualityEvidence.score, 0.0)
        assertEquals(sample.provenanceIds, draft.provenanceIds)
        assertEquals(sample.observedAtEpochMillis, draft.effectiveEpochMillis)
        assertEquals(1, audits.size)
        assertEquals(draft.exportAuditEventId, audits.single().auditEventId)
        assertEquals("share-grant-1", audits.single().clinicianShareGrantId)
        assertEquals(canonicalFhirDraftSha256(draft), audits.single().draftSha256)
    }

    @Test
    fun heartLungMetricsHaveExplicitLoincAndUcumMappings() {
        val mappings = listOf(
            SensorMetric.HEART_RATE to ("8867-4" to "/min"),
            SensorMetric.RESPIRATORY_RATE to ("9279-1" to "/min"),
            SensorMetric.OXYGEN_SATURATION to ("59408-5" to "%"),
        )

        mappings.forEach { (metric, expected) ->
            val session = fhirSession(metric)
            val draft = FhirObservationProjector { true }.project(
                session,
                fixture.sample(session),
                fixture.sharePermit(session),
                MonitoringTestFixture.NOW,
            )
            assertEquals(expected.first, draft.code.code)
            assertEquals(expected.second, draft.quantity.code)
        }
    }

    @Test
    fun auditFailureSuppressesDraftReturn() {
        val session = fhirSession(SensorMetric.HEART_RATE)
        val projector = FhirObservationProjector { false }

        assertThrows(IllegalStateException::class.java) {
            projector.project(
                session,
                fixture.sample(session),
                fixture.sharePermit(session),
                MonitoringTestFixture.NOW,
            )
        }
    }

    @Test
    fun permitFromDifferentSessionCannotAuthorizeProjection() {
        val session = fhirSession(SensorMetric.HEART_RATE)
        val otherSession = session.copy(sessionId = "session-2", streamId = "stream-2")
        val wrongPermit = fixture.sharePermit(otherSession)

        assertThrows(IllegalArgumentException::class.java) {
            FhirObservationProjector { true }.project(
                session,
                fixture.sample(session),
                wrongPermit,
                MonitoringTestFixture.NOW,
            )
        }
    }

    @Test
    fun expiredPermitCannotBeReplayedForExport() {
        val session = fhirSession(SensorMetric.HEART_RATE)
        val permit = fixture.sharePermit(session)
        val expiry = permit.validUntilEpochMillis
        val currentSample = fixture.sample(
            session,
            observedAtEpochMillis = expiry - 500L,
            receivedAtGatewayEpochMillis = expiry - 100L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            FhirObservationProjector { true }.project(session, currentSample, permit, expiry)
        }
    }

    @Test
    fun observedResearchAccessPermitCannotBypassRegulatedMedicalPromotionBinding() {
        val observed = fhirSession(SensorMetric.HEART_RATE)
        val regulated = observed.copy(purpose = MonitoringPurpose.REGULATED_CLINICAL_SERVICE)
        val observedPermit = fixture.sharePermit(observed)

        assertThrows(IllegalArgumentException::class.java) {
            FhirObservationProjector { true }.project(
                regulated,
                fixture.sample(regulated),
                observedPermit,
                MonitoringTestFixture.NOW,
            )
        }

        val regulatedPermit = fixture.sharePermit(regulated)
        val draft = FhirObservationProjector { true }.project(
            regulated,
            fixture.sample(regulated),
            regulatedPermit,
            MonitoringTestFixture.NOW,
        )
        assertEquals("preliminary", draft.status)
    }

    @Test
    fun simulatorPoorQualityAndStaleSamplesAreRejected() {
        val session = fhirSession(SensorMetric.HEART_RATE)
        val permit = fixture.sharePermit(session)
        val projector = FhirObservationProjector { true }
        val simulator = fixture.sample(session, source = SensorSource.SIMULATOR)
        val poor = fixture.sample(
            session,
            quality = fixture.goodQuality().copy(score = 0.20, validity = 0.20),
        )
        val stale = fixture.sample(session, observedAtEpochMillis = 8_000L)

        assertThrows(IllegalArgumentException::class.java) {
            projector.project(session, simulator, permit, MonitoringTestFixture.NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            projector.project(session, poor, permit, MonitoringTestFixture.NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            projector.project(session, stale, permit, MonitoringTestFixture.NOW)
        }
    }

    @Test
    fun unreviewedMetricCodingIsRejectedRatherThanInvented() {
        val session = fhirSession(SensorMetric.HRV_RMSSD)

        assertThrows(IllegalArgumentException::class.java) {
            FhirObservationProjector { true }.project(
                session,
                fixture.sample(session),
                fixture.sharePermit(session),
                MonitoringTestFixture.NOW,
            )
        }
    }

    @Test
    fun wrongStreamDeviceProtocolOrConsentGenerationCannotReachAudit() {
        val audits = mutableListOf<ClinicalExportAuditEntry>()
        val projector = FhirObservationProjector { audits += it; true }
        val session = fhirSession(SensorMetric.HEART_RATE)
        val permit = fixture.sharePermit(session)
        val base = fixture.sample(session)
        val altered = listOf(
            base.copy(streamId = "stream-2"),
            base.copy(sourceDeviceId = "watch-2"),
            base.copy(acquisitionProtocolVersion = "passive-v2"),
            base.copy(dataSchemaVersion = "live-scalar-v2"),
            base.copy(source = SensorSource.REFERENCE_DEVICE),
            base.copy(consentGeneration = 2L),
        )

        altered.forEach { sample ->
            assertThrows(IllegalArgumentException::class.java) {
                projector.project(session, sample, permit, MonitoringTestFixture.NOW)
            }
        }
        assertTrue(audits.isEmpty())
    }

    @Test
    fun projectionRejectsOutOfRangeHeartLungValues() {
        val cases = listOf(
            SensorMetric.HEART_RATE to listOf(-1.0, 29.9, 240.1),
            SensorMetric.RESPIRATORY_RATE to listOf(-1.0, 4.9, 60.1),
            SensorMetric.OXYGEN_SATURATION to listOf(-1.0, 49.9, 100.1, 250.0),
        )

        cases.forEach { (metric, values) ->
            val session = fhirSession(metric)
            val permit = fixture.sharePermit(session)
            values.forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    FhirObservationProjector { true }.project(
                        session,
                        fixture.sample(session).copy(value = value),
                        permit,
                        MonitoringTestFixture.NOW,
                    )
                }
            }
        }
    }

    @Test
    fun canonicalAuditDigestBindsEveryExportedDraftField() {
        val session = fhirSession(SensorMetric.HEART_RATE)
        val base = FhirObservationProjector { true }.project(
            session,
            fixture.sample(session),
            fixture.sharePermit(session),
            MonitoringTestFixture.NOW,
        )
        val baseDigest = canonicalFhirDraftSha256(base)
        val variants = listOf(
            copyDraft(base, observationId = "different-id"),
            copyDraft(base, status = "final"),
            copyDraft(base, sessionId = "session-2"),
            copyDraft(base, sampleId = "sample-2"),
            copyDraft(base, streamId = "stream-2"),
            copyDraft(base, sequence = base.sequence + 1L),
            copyDraft(base, subjectPseudonym = "subject-2"),
            copyDraft(base, metric = SensorMetric.RESPIRATORY_RATE),
            copyDraft(base, dataClass = ClinicalDataClass.DERIVED_SCALAR_SUMMARY),
            copyDraft(base, source = SensorSource.REFERENCE_DEVICE),
            copyDraft(base, code = base.code.copy(system = "urn:different-code-system")),
            copyDraft(base, code = base.code.copy(code = "different")),
            copyDraft(base, code = base.code.copy(display = "Different display")),
            copyDraft(base, quantity = base.quantity.copy(value = base.quantity.value + 1.0)),
            copyDraft(base, quantity = base.quantity.copy(unit = "beats/min")),
            copyDraft(base, quantity = base.quantity.copy(system = "urn:different-unit-system")),
            copyDraft(base, quantity = base.quantity.copy(code = "different-unit-code")),
            copyDraft(base, effectiveEpochMillis = base.effectiveEpochMillis + 1L),
            copyDraft(base, issuedEpochMillis = base.issuedEpochMillis + 1L),
            copyDraft(base, sourceDeviceId = "watch-2"),
            copyDraft(base, gatewayDeviceId = "phone-2"),
            copyDraft(base, firmwareGeneration = "firmware-2"),
            copyDraft(base, acquisitionProtocolVersion = "protocol-2"),
            copyDraft(base, dataSchemaVersion = "schema-2"),
            copyDraft(base, consentGeneration = base.consentGeneration + 1L),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    score = base.qualityEvidence.score - 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    coverage = base.qualityEvidence.coverage - 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    contact = base.qualityEvidence.contact - 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    motionContamination = base.qualityEvidence.motionContamination + 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    validity = base.qualityEvidence.validity - 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    clipping = base.qualityEvidence.clipping + 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    timestampContinuity = base.qualityEvidence.timestampContinuity - 0.01,
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(base.qualityEvidence, usable = false),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    reasons = base.qualityEvidence.reasons + "new-reason",
                ),
            ),
            copyDraft(
                base,
                qualityEvidence = copyQuality(
                    base.qualityEvidence,
                    evaluatorVersion = "quality-v3",
                ),
            ),
            copyDraft(base, provenanceIds = base.provenanceIds + "raw-2"),
            copyDraft(base, exportAuditEventId = "different-audit-id"),
            copyDraft(
                base,
                securityLabels = base.securityLabels + FhirCodingDraft("urn:test", "x", "X"),
            ),
        )

        assertTrue(variants.all { canonicalFhirDraftSha256(it) != baseDigest })
    }

    @Test
    fun fhirDraftSnapshotsCallerCollections() {
        val provenance = mutableListOf("raw-1")
        val labels = mutableSetOf(FhirCodingDraft("urn:test", "r", "Restricted"))
        val qualityReasons = mutableListOf("qualified")
        val draft = FhirObservationDraft(
            observationId = "observation-1",
            status = "preliminary",
            sessionId = "session-1",
            sampleId = "sample-1",
            streamId = "stream-1",
            sequence = 1L,
            subjectPseudonym = "subject-1",
            metric = SensorMetric.HEART_RATE,
            dataClass = ClinicalDataClass.FHIR_OBSERVATION_DRAFT,
            source = SensorSource.GALAXY_WATCH_ULTRA_2,
            code = FhirCodingDraft("http://loinc.org", "8867-4", "Heart rate"),
            quantity = FhirQuantityDraft(72.0, "/min", "http://unitsofmeasure.org", "/min"),
            effectiveEpochMillis = 1_000L,
            issuedEpochMillis = 1_100L,
            sourceDeviceId = "watch-1",
            gatewayDeviceId = "phone-1",
            firmwareGeneration = "firmware-1",
            acquisitionProtocolVersion = "protocol-1",
            dataSchemaVersion = "schema-1",
            consentGeneration = 1L,
            qualityEvidence = FhirQualityEvidenceDraft(
                score = 0.9,
                coverage = 0.9,
                contact = 0.9,
                motionContamination = 0.1,
                validity = 0.9,
                clipping = 0.0,
                timestampContinuity = 0.9,
                usable = true,
                reasons = qualityReasons,
                evaluatorVersion = "quality-v2",
            ),
            provenanceIds = provenance,
            exportAuditEventId = "audit-1",
            securityLabels = labels,
        )

        provenance += "raw-2"
        labels += FhirCodingDraft("urn:test", "n", "Normal")
        qualityReasons += "mutated"

        assertEquals(listOf("raw-1"), draft.provenanceIds)
        assertEquals(listOf("qualified"), draft.qualityEvidence.reasons)
        assertEquals(1, draft.securityLabels.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (draft.provenanceIds as MutableList<String>).add("raw-3")
        }
    }

    private fun fhirSession(metric: SensorMetric) = fixture.session(
        metric = metric,
        dataClass = ClinicalDataClass.FHIR_OBSERVATION_DRAFT,
    )

    private fun copyDraft(
        draft: FhirObservationDraft,
        observationId: String = draft.observationId,
        status: String = draft.status,
        sessionId: String = draft.sessionId,
        sampleId: String = draft.sampleId,
        streamId: String = draft.streamId,
        sequence: Long = draft.sequence,
        subjectPseudonym: String = draft.subjectPseudonym,
        metric: SensorMetric = draft.metric,
        dataClass: ClinicalDataClass = draft.dataClass,
        source: SensorSource = draft.source,
        code: FhirCodingDraft = draft.code,
        quantity: FhirQuantityDraft = draft.quantity,
        effectiveEpochMillis: Long = draft.effectiveEpochMillis,
        issuedEpochMillis: Long = draft.issuedEpochMillis,
        sourceDeviceId: String = draft.sourceDeviceId,
        gatewayDeviceId: String = draft.gatewayDeviceId,
        firmwareGeneration: String = draft.firmwareGeneration,
        acquisitionProtocolVersion: String = draft.acquisitionProtocolVersion,
        dataSchemaVersion: String = draft.dataSchemaVersion,
        consentGeneration: Long = draft.consentGeneration,
        qualityEvidence: FhirQualityEvidenceDraft = draft.qualityEvidence,
        provenanceIds: List<String> = draft.provenanceIds,
        exportAuditEventId: String = draft.exportAuditEventId,
        securityLabels: Set<FhirCodingDraft> = draft.securityLabels,
    ) = FhirObservationDraft(
        observationId,
        status,
        sessionId,
        sampleId,
        streamId,
        sequence,
        subjectPseudonym,
        metric,
        dataClass,
        source,
        code,
        quantity,
        effectiveEpochMillis,
        issuedEpochMillis,
        sourceDeviceId,
        gatewayDeviceId,
        firmwareGeneration,
        acquisitionProtocolVersion,
        dataSchemaVersion,
        consentGeneration,
        qualityEvidence,
        provenanceIds,
        exportAuditEventId,
        securityLabels,
    )

    private fun copyQuality(
        quality: FhirQualityEvidenceDraft,
        score: Double = quality.score,
        coverage: Double = quality.coverage,
        contact: Double = quality.contact,
        motionContamination: Double = quality.motionContamination,
        validity: Double = quality.validity,
        clipping: Double = quality.clipping,
        timestampContinuity: Double = quality.timestampContinuity,
        usable: Boolean = quality.usable,
        reasons: List<String> = quality.reasons,
        evaluatorVersion: String = quality.evaluatorVersion,
    ) = FhirQualityEvidenceDraft(
        score,
        coverage,
        contact,
        motionContamination,
        validity,
        clipping,
        timestampContinuity,
        usable,
        reasons,
        evaluatorVersion,
    )
}
