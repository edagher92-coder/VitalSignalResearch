package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.governance.ClinicalAlertAction
import au.com.elied.vitalsignal.governance.ClinicalAlertActionPermit
import au.com.elied.vitalsignal.governance.ClinicalAlertActionPermitDecision
import au.com.elied.vitalsignal.governance.ClinicalAlertActionPermitIssuer
import au.com.elied.vitalsignal.governance.ClinicalAlertActorRole
import au.com.elied.vitalsignal.governance.ClinicianShareGrant
import au.com.elied.vitalsignal.governance.ClinicianSharePermit
import au.com.elied.vitalsignal.governance.ConsentGrant
import au.com.elied.vitalsignal.governance.ConsentScope
import au.com.elied.vitalsignal.governance.EvidenceResult
import au.com.elied.vitalsignal.governance.GovernanceKeyResolver
import au.com.elied.vitalsignal.governance.GovernanceReceiptPurpose
import au.com.elied.vitalsignal.governance.HmacGovernanceAuthority
import au.com.elied.vitalsignal.governance.HmacGovernanceVerifier
import au.com.elied.vitalsignal.governance.MedicalPromotionPermit
import au.com.elied.vitalsignal.governance.MedicalPromotionPermitDecision
import au.com.elied.vitalsignal.governance.ObserverHeartbeatDecision
import au.com.elied.vitalsignal.governance.ObserverHeartbeatGate
import au.com.elied.vitalsignal.governance.ObserverHeartbeatLease
import au.com.elied.vitalsignal.governance.PilotAccessGate
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.governance.PromotionEvidenceType
import au.com.elied.vitalsignal.governance.ResearchPromotionGate
import au.com.elied.vitalsignal.governance.ValidationReceipt
import au.com.elied.vitalsignal.governance.ClinicianSharePermitIssuer
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import au.com.elied.vitalsignal.model.SignalQuality

internal class MonitoringTestFixture {
    val verifier = HmacGovernanceVerifier(
        GovernanceKeyResolver { purpose, keyId ->
            if (keyId == authorityId(purpose)) key(purpose) else null
        },
    )
    val pilotGate = PilotAccessGate(verifier, verifier)
    val permitIssuer = ClinicianSharePermitIssuer(pilotGate, verifier)
    val clinicalGate = ClinicalMonitoringGate(permitIssuer)

    fun session(
        purpose: MonitoringPurpose = MonitoringPurpose.OBSERVED_RESEARCH_SESSION,
        metric: SensorMetric = SensorMetric.HEART_RATE,
        dataClass: ClinicalDataClass = ClinicalDataClass.DERIVED_SCALAR_SUMMARY,
        expectedSamplePeriodMillis: Long = 1_000L,
        allowedSources: Set<SensorSource> = setOf(SensorSource.GALAXY_WATCH_ULTRA_2),
    ) = MonitoringSession(
        sessionId = "session-1",
        subjectPseudonym = "subject-1",
        purpose = purpose,
        authorizedObserverPrincipalIds = if (purpose == MonitoringPurpose.RESEARCH_LOG_ONLY) {
            emptySet()
        } else {
            setOf("observer-1")
        },
        metric = metric,
        dataClass = dataClass,
        allowedSources = allowedSources,
        destinationId = "clinician-portal-1",
        streamId = "stream-1",
        sourceDeviceId = "watch-1",
        gatewayDeviceId = "phone-1",
        acquisitionProtocolVersion = "passive-v1",
        consentGeneration = 1L,
        startsAtEpochMillis = 1_000L,
        endsAtEpochMillis = 100_000L,
        expectedSamplePeriodMillis = expectedSamplePeriodMillis,
        minimumDisplayQuality = 0.70,
        protocolVersion = "monitor-v2",
        appVersion = "0.6.0-research",
        deviceModel = "fixture-ultra2",
        firmwareGeneration = "fixture-fw-1",
        dataSchemaVersion = "live-scalar-v1",
        clinicalFeatureId = "clinical-live-monitor",
        clinicalFeatureVersion = "clinical-live-monitor-v1",
        environmentFingerprintSha256 = "e".repeat(64),
    )

    fun request(
        session: MonitoringSession,
        nowEpochMillis: Long = NOW,
        observerPrincipalId: String = "observer-1",
    ) = MonitoringAccessRequest(
        capability = PilotCapability.CLINICIAN_LIVE_SHARE,
        subjectPseudonym = session.subjectPseudonym,
        consentGeneration = session.consentGeneration,
        sessionId = session.sessionId,
        observerPrincipalId = observerPrincipalId,
        metric = session.metric,
        dataClass = session.dataClass,
        destinationId = session.destinationId,
        evaluatedAtEpochMillis = nowEpochMillis,
        collectionPaused = false,
        recoveryRequired = false,
    )

    fun consent(session: MonitoringSession): ConsentGrant =
        authority(GovernanceReceiptPurpose.CONSENT).issueConsent(
        subjectPseudonym = session.subjectPseudonym,
        generation = session.consentGeneration,
        scopes = setOf(ConsentScope.CLINICIAN_LIVE_DATA_SHARE),
        issuedAtEpochMillis = 500L,
        expiresAtEpochMillis = session.endsAtEpochMillis,
        consentTextSha256 = "a".repeat(64),
    )

    fun validation(session: MonitoringSession): ValidationReceipt =
        authority(GovernanceReceiptPurpose.VALIDATION).issueValidation(
        receiptId = "validation-clinician-1",
        capability = PilotCapability.CLINICIAN_LIVE_SHARE,
        appVersion = session.appVersion,
        deviceModel = session.deviceModel,
        firmwareGeneration = session.firmwareGeneration,
        dataSchemaVersion = session.dataSchemaVersion,
        issuedAtEpochMillis = 600L,
        expiresAtEpochMillis = session.endsAtEpochMillis,
        evidenceIds = listOf("observer-security-test", "stream-failure-test"),
        evidenceBundleSha256 = "b".repeat(64),
    )

    fun grant(
        session: MonitoringSession,
        observerPrincipalId: String = "observer-1",
    ): ClinicianShareGrant = authority(GovernanceReceiptPurpose.CLINICIAN_SHARE)
        .issueClinicianShareGrant(
        grantId = "share-grant-1",
        subjectPseudonym = session.subjectPseudonym,
        consentGeneration = session.consentGeneration,
        sessionId = session.sessionId,
        observerPrincipalId = observerPrincipalId,
        metric = session.metric,
        dataClass = session.dataClass,
        destinationId = session.destinationId,
        issuedAtEpochMillis = 500L,
        startsAtEpochMillis = session.startsAtEpochMillis,
        expiresAtEpochMillis = session.endsAtEpochMillis,
        termsSha256 = "c".repeat(64),
    )

    fun evaluateAccess(
        session: MonitoringSession,
        request: MonitoringAccessRequest = request(session),
        consent: ConsentGrant = consent(session),
        validationReceipts: List<ValidationReceipt> = listOf(validation(session)),
        grant: ClinicianShareGrant = grant(session),
        medicalPermit: MedicalPromotionPermit? = if (
            session.purpose == MonitoringPurpose.REGULATED_CLINICAL_SERVICE
        ) medicalPermit(session) else null,
    ): MonitoringAccessDecision = clinicalGate.evaluate(
        session = session,
        request = request,
        consent = consent,
        validationReceipts = validationReceipts,
        clinicianShareGrant = grant,
        medicalPromotionPermit = medicalPermit,
    )

    fun sharePermit(
        session: MonitoringSession,
        nowEpochMillis: Long = NOW,
    ): MonitoringAccessPermit {
        val decision = evaluateAccess(
            session = session,
            request = request(session, nowEpochMillis),
        ) as MonitoringAccessDecision.Allowed
        return decision.permit
    }

    fun rawClinicianSharePermit(
        session: MonitoringSession,
        nowEpochMillis: Long = NOW,
    ): ClinicianSharePermit = sharePermit(session, nowEpochMillis).clinicianSharePermit

    fun medicalPermit(
        session: MonitoringSession,
        nowEpochMillis: Long = NOW,
    ): MedicalPromotionPermit {
        val promotionGate = ResearchPromotionGate(verifier)
        val receipts = PromotionEvidenceType.values().map { type ->
            authority(GovernanceReceiptPurpose.PROMOTION_EVIDENCE).issuePromotionEvidence(
                receiptId = "medical-${type.name.lowercase()}",
                featureId = session.clinicalFeatureId,
                featureVersion = session.clinicalFeatureVersion,
                evidenceType = type,
                result = EvidenceResult.PASS,
                environmentFingerprintSha256 = session.environmentFingerprintSha256,
                protocolOrDatasetSha256 = "d".repeat(64),
                completedAtEpochMillis = minOf(700L, nowEpochMillis),
                expiresAtEpochMillis = session.endsAtEpochMillis,
            )
        }
        val decision = promotionGate.issueMedicalPermit(
            featureId = session.clinicalFeatureId,
            featureVersion = session.clinicalFeatureVersion,
            environmentFingerprintSha256 = session.environmentFingerprintSha256,
            evaluatedAtEpochMillis = nowEpochMillis,
            receipts = receipts,
        ) as MedicalPromotionPermitDecision.Allowed
        return decision.permit
    }

    fun alertActionPermit(
        alertId: String,
        session: MonitoringSession,
        expectedAlertVersion: Long?,
        actorPrincipalId: String,
        actorRole: ClinicalAlertActorRole,
        action: ClinicalAlertAction,
        nowEpochMillis: Long,
        clinicianSharePermit: ClinicianSharePermit? = null,
        expiresAtEpochMillis: Long = nowEpochMillis + 1_000L,
    ): ClinicalAlertActionPermit {
        val receipt = authority(GovernanceReceiptPurpose.CLINICAL_ALERT_ACTION)
            .issueClinicalAlertAction(
            receiptId = "action-${action.name.lowercase()}-${expectedAlertVersion ?: "create"}",
            alertId = alertId,
            sessionId = session.sessionId,
            subjectPseudonym = session.subjectPseudonym,
            expectedAlertVersion = expectedAlertVersion,
            actorPrincipalId = actorPrincipalId,
            actorRole = actorRole,
            action = action,
            issuedAtEpochMillis = nowEpochMillis,
            startsAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        return (ClinicalAlertActionPermitIssuer(verifier).issue(
            receipt = receipt,
            evaluatedAtEpochMillis = nowEpochMillis,
            clinicianSharePermit = clinicianSharePermit,
        ) as ClinicalAlertActionPermitDecision.Allowed).permit
    }

    fun heartbeatLease(
        session: MonitoringSession,
        nowEpochMillis: Long = NOW,
        observerPrincipalId: String = "observer-1",
    ): ObserverHeartbeatLease {
        val receipt = authority(GovernanceReceiptPurpose.OBSERVER_HEARTBEAT)
            .issueObserverHeartbeat(
            receiptId = "heartbeat-1",
            sessionId = session.sessionId,
            observerPrincipalId = observerPrincipalId,
            destinationId = session.destinationId,
            recordedAtEpochMillis = nowEpochMillis - 100L,
            expiresAtEpochMillis = nowEpochMillis + 5_000L,
        )
        return (ObserverHeartbeatGate(verifier).issue(
            receipt,
            nowEpochMillis,
        ) as ObserverHeartbeatDecision.Allowed).lease
    }

    fun sample(
        session: MonitoringSession,
        observedAtEpochMillis: Long = NOW - 500L,
        receivedAtGatewayEpochMillis: Long = maxOf(observedAtEpochMillis, NOW - 100L),
        quality: SignalQuality = goodQuality(),
        source: SensorSource = SensorSource.GALAXY_WATCH_ULTRA_2,
    ) = LiveScalarSample(
        sampleId = "sample-1",
        sessionId = session.sessionId,
        streamId = session.streamId,
        subjectPseudonym = session.subjectPseudonym,
        sequence = 1L,
        metric = session.metric,
        dataClass = session.dataClass,
        unit = session.metric.unit,
        value = when (session.metric) {
            SensorMetric.OXYGEN_SATURATION -> 98.0
            SensorMetric.RESPIRATORY_RATE -> 16.0
            else -> 72.0
        },
        observedAtEpochMillis = observedAtEpochMillis,
        receivedAtGatewayEpochMillis = receivedAtGatewayEpochMillis,
        quality = quality,
        source = source,
        sourceDeviceId = session.sourceDeviceId,
        gatewayDeviceId = session.gatewayDeviceId,
        firmwareGeneration = session.firmwareGeneration,
        acquisitionProtocolVersion = session.acquisitionProtocolVersion,
        dataSchemaVersion = session.dataSchemaVersion,
        consentGeneration = session.consentGeneration,
        provenanceIds = listOf("raw-1", "quality-1"),
    )

    fun goodQuality() = SignalQuality(
        score = 0.90,
        coverage = 0.95,
        contact = 0.95,
        motionContamination = 0.10,
        validity = 0.95,
        clipping = 0.0,
        timestampContinuity = 0.95,
    )

    private fun authority(purpose: GovernanceReceiptPurpose) = HmacGovernanceAuthority(
        authorityId(purpose),
        purpose,
        key(purpose),
    )

    private fun authorityId(purpose: GovernanceReceiptPurpose) =
        "monitoring-${purpose.name.lowercase()}-authority"

    private fun key(purpose: GovernanceReceiptPurpose) =
        ByteArray(32) { index -> (index + 1 + purpose.ordinal * 11).toByte() }

    companion object {
        const val NOW = 10_000L
    }
}
