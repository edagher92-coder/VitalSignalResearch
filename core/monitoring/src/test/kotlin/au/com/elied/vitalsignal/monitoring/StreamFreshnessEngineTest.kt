package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFreshnessEngineTest {
    private val fixture = MonitoringTestFixture()
    private val engine = StreamFreshnessEngine()

    @Test
    fun monitoringContractsSnapshotCallerCollections() {
        val observers = mutableSetOf("observer-1")
        val sources = mutableSetOf(SensorSource.GALAXY_WATCH_ULTRA_2)
        val session = fixture.session().copy(
            authorizedObserverPrincipalIds = observers,
            allowedSources = sources,
        )
        observers += "observer-2"
        sources += SensorSource.REFERENCE_DEVICE
        val provenance = mutableListOf("raw-1")
        val qualityReasons = mutableListOf("qualified")
        val sample = fixture.sample(session).copy(
            quality = fixture.goodQuality().copy(reasons = qualityReasons),
            provenanceIds = provenance,
        )
        provenance += "raw-2"
        qualityReasons += "mutated"
        val status = engine.evaluate(
            session = session,
            sample = sample,
            nowEpochMillis = MonitoringTestFixture.NOW,
            sharePermit = fixture.sharePermit(session),
            observerHeartbeatLease = fixture.heartbeatLease(session),
            clockTrusted = true,
            sequenceValid = true,
        )

        assertEquals(setOf("observer-1"), session.authorizedObserverPrincipalIds)
        assertEquals(setOf(SensorSource.GALAXY_WATCH_ULTRA_2), session.allowedSources)
        assertEquals(listOf("raw-1"), sample.provenanceIds)
        assertEquals(listOf("qualified"), sample.quality.reasons)
        assertThrows(UnsupportedOperationException::class.java) {
            (status.reasonCodes as MutableSet<String>).add("injected")
        }
    }

    @Test
    fun freshQualifiedSampleIsActivelyObservedOnlyWithCurrentOpaqueHeartbeat() {
        val session = fixture.session()
        val permit = fixture.sharePermit(session)
        val sample = fixture.sample(session)

        val observed = engine.evaluate(
            session,
            sample,
            MonitoringTestFixture.NOW,
            permit,
            fixture.heartbeatLease(session),
            clockTrusted = true,
            sequenceValid = true,
        )
        val unattended = engine.evaluate(
            session,
            sample,
            MonitoringTestFixture.NOW,
            permit,
            observerHeartbeatLease = null,
            clockTrusted = true,
            sequenceValid = true,
        )

        assertEquals(StreamAvailability.LIVE, observed.availability)
        assertTrue(observed.activelyObserved)
        assertEquals(ObserverCoverage.ACTIVE, observed.observerCoverage)
        assertEquals(StreamAvailability.LIVE, unattended.availability)
        assertFalse(unattended.activelyObserved)
        assertTrue("observer-heartbeat-not-current" in unattended.reasonCodes)
    }

    @Test
    fun exactCadenceBoundariesAreLiveThenDelayedThenStale() {
        val session = fixture.session(expectedSamplePeriodMillis = 1_000L)
        val live = evaluate(session, fixture.sample(session, observedAtEpochMillis = 9_001L))
        val delayed = evaluate(session, fixture.sample(session, observedAtEpochMillis = 9_000L))
        val stale = evaluate(session, fixture.sample(session, observedAtEpochMillis = 8_000L))

        assertEquals(1_000L, session.delayedAfterMillis)
        assertEquals(2_000L, session.staleAfterMillis)
        assertEquals(StreamAvailability.LIVE, live.availability)
        assertEquals(StreamAvailability.DELAYED, delayed.availability)
        assertEquals(StreamAvailability.STALE, stale.availability)
    }

    @Test
    fun staleThresholdIsCappedAtFiveMinutes() {
        val session = fixture.session(expectedSamplePeriodMillis = 200_000L).copy(
            endsAtEpochMillis = 1_000_000L,
        )
        val now = 500_000L
        val permit = fixture.sharePermit(session, now)
        val sample = fixture.sample(
            session = session,
            observedAtEpochMillis = 200_000L,
            receivedAtGatewayEpochMillis = 499_900L,
        )

        val status = engine.evaluate(
            session,
            sample,
            now,
            permit,
            fixture.heartbeatLease(session, now),
            clockTrusted = true,
            sequenceValid = true,
        )

        assertEquals(300_000L, session.staleAfterMillis)
        assertEquals(StreamAvailability.STALE, status.availability)
    }

    @Test
    fun stalePoorQualitySamplePreservesBothFailureReasons() {
        val session = fixture.session()
        val poor = fixture.goodQuality().copy(score = 0.30, validity = 0.40)
        val status = evaluate(
            session,
            fixture.sample(session, observedAtEpochMillis = 8_000L, quality = poor),
        )

        assertEquals(StreamAvailability.STALE, status.availability)
        assertTrue(status.qualityBlocked)
        assertTrue("sample-stale" in status.reasonCodes)
        assertTrue("quality-below-display-gate" in status.reasonCodes)
        assertTrue(status.headline.contains("current state unknown"))
    }

    @Test
    fun futureGatewayReceiptFailsClockTrustEvenWhenMeasurementIsPast() {
        val session = fixture.session()
        val status = evaluate(
            session,
            fixture.sample(
                session,
                observedAtEpochMillis = 9_500L,
                receivedAtGatewayEpochMillis = 10_001L,
            ),
        )

        assertEquals(StreamAvailability.CLOCK_UNTRUSTED, status.availability)
        assertTrue("future-sample-or-receipt" in status.reasonCodes)
    }

    @Test
    fun simulatorSampleCanNeverBeAuthorizedAsLiveObserverData() {
        val session = fixture.session()
        val status = evaluate(
            session,
            fixture.sample(session, source = SensorSource.SIMULATOR),
        )

        assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, status.availability)
        assertFalse(status.activelyObserved)
        assertTrue("sample-source-not-authorized" in status.reasonCodes)
        assertTrue(status.headline.contains("not authorized", ignoreCase = true))
    }

    @Test
    fun numericSafetyPolicyBlocksSimulatorAndUserReportedValuesDefensively() {
        val logSession = fixture.session(
            purpose = MonitoringPurpose.RESEARCH_LOG_ONLY,
            allowedSources = setOf(SensorSource.SIMULATOR, SensorSource.USER_REPORTED),
        )

        assertEquals(
            "simulator-source-blocked",
            ClinicalScalarSamplePolicy.rejectionCode(
                fixture.sample(logSession, source = SensorSource.SIMULATOR),
            ),
        )
        assertEquals(
            "user-reported-source-blocked",
            ClinicalScalarSamplePolicy.rejectionCode(
                fixture.sample(logSession, source = SensorSource.USER_REPORTED),
            ),
        )
    }

    @Test
    fun implausibleNumericSampleCanNeverAppearAsLiveObserverData() {
        val session = fixture.session()
        val status = evaluate(
            session,
            fixture.sample(session).copy(value = 900.0),
        )

        assertEquals(StreamAvailability.VALIDATION_BLOCKED, status.availability)
        assertFalse(status.activelyObserved)
        assertTrue("value-outside-reviewed-display-bounds" in status.reasonCodes)
    }

    @Test
    fun sampleMustMatchEverySessionStreamProtocolDeviceMetricAndGenerationBinding() {
        val session = fixture.session()
        val base = fixture.sample(session)
        val alteredSamples = listOf(
            base.copy(sessionId = "session-2"),
            base.copy(streamId = "stream-2"),
            base.copy(subjectPseudonym = "subject-2"),
            base.copy(metric = SensorMetric.RESPIRATORY_RATE, unit = SensorMetric.RESPIRATORY_RATE.unit),
            base.copy(dataClass = ClinicalDataClass.FHIR_OBSERVATION_DRAFT),
            base.copy(sourceDeviceId = "watch-2"),
            base.copy(gatewayDeviceId = "phone-2"),
            base.copy(firmwareGeneration = "fixture-fw-2"),
            base.copy(acquisitionProtocolVersion = "passive-v2"),
            base.copy(dataSchemaVersion = "live-scalar-v2"),
            base.copy(consentGeneration = 2L),
        )

        alteredSamples.forEach { altered ->
            assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, evaluate(session, altered).availability)
        }
    }

    @Test
    fun scalarContractRejectsRawWaveformClassAndInvalidSchemaVersion() {
        val session = fixture.session()
        val base = fixture.sample(session)

        assertThrows(IllegalArgumentException::class.java) {
            base.copy(dataClass = ClinicalDataClass.RAW_WAVEFORM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(dataSchemaVersion = "not a safe schema version")
        }
    }

    @Test
    fun sourceMustBeExplicitlyAllowedAndPermitBound() {
        val watchSession = fixture.session()
        val referenceSample = fixture.sample(
            watchSession,
            source = SensorSource.REFERENCE_DEVICE,
        )
        val rejected = evaluate(watchSession, referenceSample)

        val referenceSession = fixture.session(
            allowedSources = setOf(SensorSource.REFERENCE_DEVICE),
        )
        val accepted = evaluate(
            referenceSession,
            fixture.sample(referenceSession, source = SensorSource.REFERENCE_DEVICE),
        )

        assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, rejected.availability)
        assertTrue("sample-source-not-authorized" in rejected.reasonCodes)
        assertEquals(StreamAvailability.LIVE, accepted.availability)
    }

    @Test
    fun allowedSourcesAreMandatoryAndSimulatorCannotEnterObservedSessionConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session(allowedSources = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session(allowedSources = setOf(SensorSource.SIMULATOR))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session(allowedSources = setOf(SensorSource.USER_REPORTED))
        }

        val logOnly = fixture.session(
            purpose = MonitoringPurpose.RESEARCH_LOG_ONLY,
            allowedSources = setOf(SensorSource.SIMULATOR, SensorSource.USER_REPORTED),
        )
        assertEquals(
            setOf(SensorSource.SIMULATOR, SensorSource.USER_REPORTED),
            logOnly.allowedSources,
        )
    }

    @Test
    fun permitCannotBeReusedAfterSessionSchemaOrAllowedSourceChanges() {
        val session = fixture.session()
        val permit = fixture.sharePermit(session)
        val schemaChanged = session.copy(dataSchemaVersion = "live-scalar-v2")
        val sourceChanged = session.copy(allowedSources = setOf(SensorSource.REFERENCE_DEVICE))

        listOf(schemaChanged, sourceChanged).forEach { changed ->
            val status = engine.evaluate(
                changed,
                fixture.sample(changed, source = changed.allowedSources.single()),
                MonitoringTestFixture.NOW,
                permit,
                observerHeartbeatLease = null,
                clockTrusted = true,
                sequenceValid = true,
            )
            assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, status.availability)
            assertTrue("share-permit-invalid" in status.reasonCodes)
        }
    }

    @Test
    fun expiredSharePermitAndChangedConsentGenerationFailClosed() {
        val session = fixture.session()
        val permit = fixture.sharePermit(session)
        val afterPermitExpiry = permit.validUntilEpochMillis
        val currentSample = fixture.sample(
            session,
            observedAtEpochMillis = afterPermitExpiry - 500L,
            receivedAtGatewayEpochMillis = afterPermitExpiry - 100L,
        )
        val expired = engine.evaluate(
            session,
            currentSample,
            afterPermitExpiry,
            permit,
            observerHeartbeatLease = null,
            clockTrusted = true,
            sequenceValid = true,
        )
        val rotatedSession = session.copy(consentGeneration = 2L)
        val rotated = engine.evaluate(
            rotatedSession,
            fixture.sample(rotatedSession),
            MonitoringTestFixture.NOW,
            permit,
            observerHeartbeatLease = null,
            clockTrusted = true,
            sequenceValid = true,
        )

        assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, expired.availability)
        assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, rotated.availability)
    }

    @Test
    fun inactiveSessionNoDataClockAndSequenceFailuresStayExplicit() {
        val session = fixture.session()
        val permit = fixture.sharePermit(session)
        val heartbeat = fixture.heartbeatLease(session)
        val beforeSession = engine.evaluate(
            session,
            null,
            session.startsAtEpochMillis - 1L,
            permit,
            heartbeat,
            clockTrusted = true,
            sequenceValid = true,
        )
        val noData = engine.evaluate(
            session,
            null,
            MonitoringTestFixture.NOW,
            permit,
            heartbeat,
            clockTrusted = true,
            sequenceValid = true,
        )
        val clock = engine.evaluate(
            session,
            fixture.sample(session),
            MonitoringTestFixture.NOW,
            permit,
            heartbeat,
            clockTrusted = false,
            sequenceValid = true,
        )
        val sequence = engine.evaluate(
            session,
            fixture.sample(session),
            MonitoringTestFixture.NOW,
            permit,
            heartbeat,
            clockTrusted = true,
            sequenceValid = false,
        )

        assertEquals(StreamAvailability.SESSION_INACTIVE, beforeSession.availability)
        assertEquals(StreamAvailability.NO_DATA, noData.availability)
        assertEquals(StreamAvailability.CLOCK_UNTRUSTED, clock.availability)
        assertEquals(StreamAvailability.SEQUENCE_INVALID, sequence.availability)
    }

    @Test
    fun sampleOutsideAuthorizedSessionIsBlocked() {
        val session = fixture.session()
        val sample = fixture.sample(
            session,
            observedAtEpochMillis = session.startsAtEpochMillis - 1L,
            receivedAtGatewayEpochMillis = MonitoringTestFixture.NOW - 100L,
        )

        val status = evaluate(session, sample)

        assertEquals(StreamAvailability.AUTHORIZATION_BLOCKED, status.availability)
        assertTrue("sample-outside-session" in status.reasonCodes)
    }

    private fun evaluate(session: MonitoringSession, sample: LiveScalarSample): StreamStatus =
        engine.evaluate(
            session = session,
            sample = sample,
            nowEpochMillis = MonitoringTestFixture.NOW,
            sharePermit = fixture.sharePermit(session),
            observerHeartbeatLease = fixture.heartbeatLease(session),
            clockTrusted = true,
            sequenceValid = true,
        )
}
