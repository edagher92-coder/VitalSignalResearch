package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ObserverHeartbeatLease
import au.com.elied.vitalsignal.governance.PilotCapability

/** Missing, stale and low-quality data remain explicit and may coexist. */
class StreamFreshnessEngine {
    fun evaluate(
        session: MonitoringSession,
        sample: LiveScalarSample?,
        nowEpochMillis: Long,
        sharePermit: MonitoringAccessPermit,
        observerHeartbeatLease: ObserverHeartbeatLease?,
        clockTrusted: Boolean,
        sequenceValid: Boolean,
    ): StreamStatus {
        require(nowEpochMillis > 0L)

        val observerActive = observerHeartbeatLease.matches(session, sharePermit, nowEpochMillis)
        if (session.purpose == MonitoringPurpose.RESEARCH_LOG_ONLY) {
            return status(
                availability = StreamAvailability.AUTHORIZATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "Live sharing authorization is not current",
                reasonCodes = setOf("share-permit-invalid"),
            )
        }
        if (nowEpochMillis !in session.startsAtEpochMillis until session.endsAtEpochMillis) {
            return status(
                availability = StreamAvailability.SESSION_INACTIVE,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "Monitoring session is not active",
                reasonCodes = setOf("session-inactive"),
            )
        }
        if (!sharePermit.matches(session, nowEpochMillis)) {
            return status(
                availability = StreamAvailability.AUTHORIZATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "Live sharing authorization is not current",
                reasonCodes = setOf("share-permit-invalid"),
            )
        }
        if (!clockTrusted) {
            return status(
                availability = StreamAvailability.CLOCK_UNTRUSTED,
                nowEpochMillis = nowEpochMillis,
                observerActive = observerActive,
                headline = "Timestamp cannot be trusted",
                reasonCodes = setOf("clock-untrusted"),
            )
        }
        if (!sequenceValid) {
            return status(
                availability = StreamAvailability.SEQUENCE_INVALID,
                nowEpochMillis = nowEpochMillis,
                observerActive = observerActive,
                headline = "Stream sequence is invalid",
                reasonCodes = setOf("sequence-invalid"),
            )
        }
        if (sample == null) {
            return status(
                availability = StreamAvailability.NO_DATA,
                nowEpochMillis = nowEpochMillis,
                observerActive = observerActive,
                headline = "No data received",
                reasonCodes = setOf("no-sample"),
            )
        }
        if (sample.source !in session.allowedSources || sample.source !in sharePermit.allowedSources) {
            return status(
                availability = StreamAvailability.AUTHORIZATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "Sample source is not authorized for this session",
                reasonCodes = setOf("sample-source-not-authorized"),
            )
        }
        if (!sample.matches(session, sharePermit)) {
            return status(
                availability = StreamAvailability.AUTHORIZATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "No authorized stream data",
                reasonCodes = setOf("sample-binding-mismatch"),
            )
        }
        if (sample.observedAtEpochMillis !in session.startsAtEpochMillis until session.endsAtEpochMillis) {
            return status(
                availability = StreamAvailability.AUTHORIZATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = false,
                headline = "Sample is outside the authorized session",
                reasonCodes = setOf("sample-outside-session"),
            )
        }
        if (sample.observedAtEpochMillis > nowEpochMillis ||
            sample.receivedAtGatewayEpochMillis > nowEpochMillis
        ) {
            return status(
                availability = StreamAvailability.CLOCK_UNTRUSTED,
                nowEpochMillis = nowEpochMillis,
                observerActive = observerActive,
                headline = "Sample or gateway receipt time is in the future",
                reasonCodes = setOf("future-sample-or-receipt"),
            )
        }

        val ageMillis = nowEpochMillis - sample.observedAtEpochMillis
        ClinicalScalarSamplePolicy.rejectionCode(sample)?.let { rejectionCode ->
            return status(
                availability = StreamAvailability.VALIDATION_BLOCKED,
                nowEpochMillis = nowEpochMillis,
                observerActive = observerActive,
                headline = "Sample withheld by the clinical validation gate",
                reasonCodes = setOf(rejectionCode),
                sample = sample,
                ageMillis = ageMillis,
            )
        }

        val qualityBlocked = sample.quality.score < session.minimumDisplayQuality || !sample.quality.usable
        val availability = when {
            ageMillis >= session.staleAfterMillis -> StreamAvailability.STALE
            ageMillis >= session.delayedAfterMillis -> StreamAvailability.DELAYED
            qualityBlocked -> StreamAvailability.QUALITY_BLOCKED
            else -> StreamAvailability.LIVE
        }
        val reasons = buildSet {
            add(
                when (availability) {
                    StreamAvailability.LIVE -> "fresh-qualified-sample"
                    StreamAvailability.DELAYED -> "sample-delayed"
                    StreamAvailability.STALE -> "sample-stale"
                    StreamAvailability.QUALITY_BLOCKED -> "quality-below-display-gate"
                    else -> error("handled before freshness classification")
                },
            )
            if (qualityBlocked) add("quality-below-display-gate")
        }
        val headline = when (availability) {
            StreamAvailability.LIVE -> "Live qualified data"
            StreamAvailability.DELAYED -> if (qualityBlocked) {
                "Data delayed and signal quality is insufficient"
            } else {
                "Data delayed — verify connection"
            }
            StreamAvailability.STALE -> if (qualityBlocked) {
                "Data stale and signal quality is insufficient — current state unknown"
            } else {
                "Data stale — current state unknown"
            }
            StreamAvailability.QUALITY_BLOCKED -> "Signal present but quality is insufficient"
            else -> error("handled before freshness classification")
        }
        return status(
            availability = availability,
            nowEpochMillis = nowEpochMillis,
            observerActive = observerActive,
            headline = headline,
            reasonCodes = reasons,
            sample = sample,
            ageMillis = ageMillis,
            qualityBlocked = qualityBlocked,
        )
    }

    private fun MonitoringAccessPermit.matches(session: MonitoringSession, nowEpochMillis: Long): Boolean =
        capability == PilotCapability.CLINICIAN_LIVE_SHARE &&
            purpose == session.purpose &&
            sessionId == session.sessionId &&
            subjectPseudonym == session.subjectPseudonym &&
            consentGeneration == session.consentGeneration &&
            metric == session.metric &&
            dataClass == session.dataClass &&
            destinationId == session.destinationId &&
            allowedSources == session.allowedSources &&
            sourceDeviceId == session.sourceDeviceId &&
            gatewayDeviceId == session.gatewayDeviceId &&
            firmwareGeneration == session.firmwareGeneration &&
            acquisitionProtocolVersion == session.acquisitionProtocolVersion &&
            dataSchemaVersion == session.dataSchemaVersion &&
            sessionStartsAtEpochMillis == session.startsAtEpochMillis &&
            sessionEndsAtEpochMillis == session.endsAtEpochMillis &&
            (purpose != MonitoringPurpose.REGULATED_CLINICAL_SERVICE ||
                (clinicalFeatureId == session.clinicalFeatureId &&
                    clinicalFeatureVersion == session.clinicalFeatureVersion &&
                    environmentFingerprintSha256 == session.environmentFingerprintSha256 &&
                    medicalEvidenceReceiptIds.isNotEmpty())) &&
            isCurrentAt(nowEpochMillis)

    private fun LiveScalarSample.matches(
        session: MonitoringSession,
        permit: MonitoringAccessPermit,
    ): Boolean =
        sessionId == session.sessionId &&
            streamId == session.streamId &&
            subjectPseudonym == session.subjectPseudonym &&
            metric == session.metric &&
            dataClass == session.dataClass &&
            source in session.allowedSources &&
            source in permit.allowedSources &&
            sourceDeviceId == session.sourceDeviceId &&
            gatewayDeviceId == session.gatewayDeviceId &&
            firmwareGeneration == session.firmwareGeneration &&
            acquisitionProtocolVersion == session.acquisitionProtocolVersion &&
            dataSchemaVersion == session.dataSchemaVersion &&
            dataSchemaVersion == permit.dataSchemaVersion &&
            consentGeneration == session.consentGeneration &&
            consentGeneration == permit.consentGeneration

    private fun ObserverHeartbeatLease?.matches(
        session: MonitoringSession,
        permit: MonitoringAccessPermit,
        nowEpochMillis: Long,
    ): Boolean = this != null &&
        sessionId == session.sessionId &&
        observerPrincipalId == permit.observerPrincipalId &&
        destinationId == session.destinationId &&
        isCurrentAt(nowEpochMillis)

    private fun status(
        availability: StreamAvailability,
        nowEpochMillis: Long,
        observerActive: Boolean,
        headline: String,
        reasonCodes: Set<String>,
        sample: LiveScalarSample? = null,
        ageMillis: Long? = null,
        qualityBlocked: Boolean = false,
    ): StreamStatus {
        val observerReasons = if (observerActive) emptySet() else setOf("observer-heartbeat-not-current")
        return StreamStatus(
            availability = availability,
            ageMillis = ageMillis,
            measurementAtEpochMillis = sample?.observedAtEpochMillis,
            gatewayReceivedAtEpochMillis = sample?.receivedAtGatewayEpochMillis,
            viewedAtEpochMillis = nowEpochMillis,
            observerCoverage = if (observerActive) ObserverCoverage.ACTIVE else ObserverCoverage.UNAVAILABLE,
            activelyObserved = observerActive && availability == StreamAvailability.LIVE && !qualityBlocked,
            qualityBlocked = qualityBlocked,
            headline = if (observerActive) headline else "$headline · not continuously observed",
            reasonCodes = reasonCodes + observerReasons,
        )
    }
}
