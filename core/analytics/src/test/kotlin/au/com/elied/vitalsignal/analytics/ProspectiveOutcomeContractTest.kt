package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.ForecastOutcomeObservation
import au.com.elied.vitalsignal.model.ForecastCheckInScales
import au.com.elied.vitalsignal.model.OutcomeAvailability
import au.com.elied.vitalsignal.model.PreForecastCheckIn
import au.com.elied.vitalsignal.model.SelfReportAvailability
import au.com.elied.vitalsignal.model.SelfReportScaleResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProspectiveOutcomeContractTest {
    @Test
    fun preForecastCheckInIsASeparateContextRecord() {
        val checkIn = PreForecastCheckIn(
            id = "check-in-1",
            capturedAtEpochMillis = 1_000L,
            completedAtEpochMillis = 1_100L,
            localDateIso = "2026-08-23",
            localOffsetMinutes = 600,
            energy = 6,
            fatigue = 4,
            perceivedStress = 3,
            gastrointestinalSymptoms = 2,
            sleepQuality = 7,
            functionalCapacity = SelfReportScaleResponse.reported(
                6,
                ForecastCheckInScales.FUNCTIONAL_CAPACITY,
            ),
            lightheadedness = SelfReportScaleResponse.missing(
                SelfReportAvailability.NOT_REPORTED,
                ForecastCheckInScales.LIGHTHEADEDNESS,
            ),
            nauseaVomitingDiarrhea = SelfReportScaleResponse.missing(
                SelfReportAvailability.NOT_REPORTED,
                ForecastCheckInScales.NAUSEA_VOMITING_DIARRHEA,
            ),
            acuteIllnessBurden = SelfReportScaleResponse.missing(
                SelfReportAvailability.NOT_REPORTED,
                ForecastCheckInScales.ACUTE_ILLNESS_BURDEN,
            ),
        )

        assertEquals(1_000L, checkIn.capturedAtEpochMillis)
    }

    @Test
    fun outcomeCannotBeCreatedBeforeTargetWindowEnds() {
        assertThrows(IllegalArgumentException::class.java) {
            observedOutcome(recordedAt = 2_999L)
        }
    }

    @Test
    fun retrospectiveAssessmentOutsidePointWindowIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observedOutcome(recordedAt = 3_100L).copy(assessedAtEpochMillis = 3_000L)
        }
    }

    @Test
    fun missingOutcomeRemainsNullRatherThanNegative() {
        val outcome = ForecastOutcomeObservation(
            id = "outcome-missing",
            forecastId = "forecast-1",
            endpointId = ENDPOINT_ID,
            endpointVersion = ENDPOINT_VERSION,
            endpointDefinitionSha256 = ENDPOINT_DIGEST,
            targetStartEpochMillis = 2_000L,
            targetEndEpochMillis = 3_000L,
            assessedAtEpochMillis = null,
            recordedAtEpochMillis = 3_100L,
            availability = OutcomeAvailability.MISSING,
            binaryOutcome = null,
            sourceCheckInId = null,
            missingReason = "No check-in in the resolution window",
        )

        assertNull(outcome.asBinaryDoubleOrNull())
    }

    private fun observedOutcome(recordedAt: Long) = ForecastOutcomeObservation(
        id = "outcome-1",
        forecastId = "forecast-1",
        endpointId = ENDPOINT_ID,
        endpointVersion = ENDPOINT_VERSION,
        endpointDefinitionSha256 = ENDPOINT_DIGEST,
        targetStartEpochMillis = 2_000L,
        targetEndEpochMillis = 3_000L,
        assessedAtEpochMillis = 2_500L,
        recordedAtEpochMillis = recordedAt,
        availability = OutcomeAvailability.OBSERVED,
        binaryOutcome = true,
        sourceCheckInId = "check-in-after-target",
    )

    private companion object {
        const val ENDPOINT_ID = "energy-function-72h-point"
        const val ENDPOINT_VERSION = "1.0.0"
        val ENDPOINT_DIGEST = "e".repeat(64)
    }
}
