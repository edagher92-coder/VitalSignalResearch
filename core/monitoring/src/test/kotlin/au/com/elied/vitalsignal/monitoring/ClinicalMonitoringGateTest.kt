package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalDataClass
import au.com.elied.vitalsignal.governance.PilotCapability
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalMonitoringGateTest {
    private val fixture = MonitoringTestFixture()

    @Test
    fun exactSignedObserverGrantIssuesBoundResearchPermit() {
        val decision = fixture.evaluateAccess(fixture.session())

        assertTrue(decision is MonitoringAccessDecision.Allowed)
        val allowed = decision as MonitoringAccessDecision.Allowed
        assertTrue(allowed.displayLabel.contains("not a clinical"))
        assertEquals(PilotCapability.CLINICIAN_LIVE_SHARE, allowed.permit.capability)
        assertEquals("observer-1", allowed.permit.observerPrincipalId)
        assertEquals("session-1", allowed.permit.sessionId)
        assertEquals(setOf(SensorSource.GALAXY_WATCH_ULTRA_2), allowed.permit.allowedSources)
        assertEquals("live-scalar-v1", allowed.permit.dataSchemaVersion)
        assertEquals("watch-1", allowed.permit.sourceDeviceId)
        assertEquals("phone-1", allowed.permit.gatewayDeviceId)
        assertThrows(UnsupportedOperationException::class.java) {
            (allowed.permit.allowedSources as MutableSet<SensorSource>).add(
                SensorSource.REFERENCE_DEVICE,
            )
        }
    }

    @Test
    fun researchLogOnlyIsNeverShareable() {
        val session = fixture.session(MonitoringPurpose.RESEARCH_LOG_ONLY)
        val decision = fixture.evaluateAccess(session)

        assertTrue(decision is MonitoringAccessDecision.Denied)
        assertEquals(
            MonitoringAccessReason.LOG_ONLY_NOT_SHAREABLE,
            (decision as MonitoringAccessDecision.Denied).reason,
        )
    }

    @Test
    fun capabilitySubjectGenerationSessionMetricDataClassAndDestinationMustMatch() {
        val session = fixture.session()
        val alteredRequests = listOf(
            fixture.request(session).copy(capability = PilotCapability.PERSONAL_INTERPRETATION),
            fixture.request(session).copy(subjectPseudonym = "subject-2"),
            fixture.request(session).copy(consentGeneration = 2L),
            fixture.request(session).copy(sessionId = "session-2"),
            fixture.request(session).copy(metric = SensorMetric.RESPIRATORY_RATE),
            fixture.request(session).copy(dataClass = ClinicalDataClass.FHIR_OBSERVATION_DRAFT),
            fixture.request(session).copy(destinationId = "destination-2"),
        )

        alteredRequests.forEach { request ->
            val denied = fixture.evaluateAccess(session, request = request)
                as MonitoringAccessDecision.Denied
            assertEquals(MonitoringAccessReason.SESSION_BINDING_MISMATCH, denied.reason)
        }
    }

    @Test
    fun observerMustBeBothSessionAuthorizedAndSignedIntoGrant() {
        val session = fixture.session()
        val request = fixture.request(session, observerPrincipalId = "observer-2")

        val denied = fixture.evaluateAccess(
            session = session,
            request = request,
            grant = fixture.grant(session, observerPrincipalId = "observer-2"),
        ) as MonitoringAccessDecision.Denied

        assertEquals(MonitoringAccessReason.OBSERVER_NOT_AUTHORIZED, denied.reason)
    }

    @Test
    fun signedGrantMutationAndConsentPauseFailClosed() {
        val session = fixture.session()
        val tamperedGrant = fixture.grant(session).copy(destinationId = "destination-2")
        val tampered = fixture.evaluateAccess(session, grant = tamperedGrant)
            as MonitoringAccessDecision.Denied
        val paused = fixture.evaluateAccess(
            session,
            request = fixture.request(session).copy(collectionPaused = true),
        ) as MonitoringAccessDecision.Denied

        assertEquals(MonitoringAccessReason.SHARE_AUTHORIZATION_DENIED, tampered.reason)
        assertEquals(MonitoringAccessReason.SHARE_AUTHORIZATION_DENIED, paused.reason)
    }

    @Test
    fun sessionStartAndEndBoundariesFailClosed() {
        val session = fixture.session()
        val before = fixture.evaluateAccess(
            session,
            request = fixture.request(session, session.startsAtEpochMillis - 1L),
        ) as MonitoringAccessDecision.Denied
        val atEnd = fixture.evaluateAccess(
            session,
            request = fixture.request(session, session.endsAtEpochMillis),
        ) as MonitoringAccessDecision.Denied

        assertEquals(MonitoringAccessReason.SESSION_NOT_ACTIVE, before.reason)
        assertEquals(MonitoringAccessReason.SESSION_NOT_ACTIVE, atEnd.reason)
    }

    @Test
    fun futureDatedValidationReceiptCannotUnlockSharing() {
        val session = fixture.session()
        val future = fixture.validation(session).copy(
            issuedAtEpochMillis = MonitoringTestFixture.NOW + 1L,
        )

        val denied = fixture.evaluateAccess(
            session,
            validationReceipts = listOf(future),
        ) as MonitoringAccessDecision.Denied

        assertEquals(MonitoringAccessReason.SHARE_AUTHORIZATION_DENIED, denied.reason)
    }

    @Test
    fun regulatedModeRequiresExactCurrentOpaqueMedicalPermit() {
        val session = fixture.session(MonitoringPurpose.REGULATED_CLINICAL_SERVICE)
        val missing = fixture.evaluateAccess(session, medicalPermit = null)
            as MonitoringAccessDecision.Denied
        val wrongFeatureSession = session.copy(clinicalFeatureVersion = "different-version")
        val wrongPermit = fixture.medicalPermit(wrongFeatureSession)
        val mismatched = fixture.evaluateAccess(session, medicalPermit = wrongPermit)
            as MonitoringAccessDecision.Denied
        val allowed = fixture.evaluateAccess(session)

        assertEquals(MonitoringAccessReason.MEDICAL_PROMOTION_REQUIRED, missing.reason)
        assertEquals(MonitoringAccessReason.MEDICAL_PROMOTION_INVALID, mismatched.reason)
        assertTrue(allowed is MonitoringAccessDecision.Allowed)
        assertEquals(
            "Validated clinical monitoring service",
            (allowed as MonitoringAccessDecision.Allowed).displayLabel,
        )
        assertEquals(MonitoringPurpose.REGULATED_CLINICAL_SERVICE, allowed.permit.purpose)
        assertEquals(session.clinicalFeatureId, allowed.permit.clinicalFeatureId)
        assertTrue(allowed.permit.medicalEvidenceReceiptIds.isNotEmpty())
    }

    @Test
    fun permitLifetimeIsShorterThanSessionAndCannotBeExtendedByCaller() {
        val session = fixture.session()
        val permit = (fixture.evaluateAccess(session) as MonitoringAccessDecision.Allowed).permit

        assertTrue(permit.validUntilEpochMillis < session.endsAtEpochMillis)
        assertFalse(permit.isCurrentAt(permit.validUntilEpochMillis))
    }
}
