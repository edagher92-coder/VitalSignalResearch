package au.com.elied.vitalsignal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FatigueContextModelsTest {
    @Test
    fun `fatigue outcome retains functional and symptom context without naming a cause`() {
        val outcome = DailyOutcome(
            localDateIso = "2026-08-23",
            energy = 3,
            fatigue = 8,
            perceivedStress = 4,
            gastrointestinalSymptoms = 2,
            sleepQuality = 5,
            functionalCapacity = 4,
            lightheadedness = 3,
            nauseaVomitingDiarrhea = 1,
            acuteIllnessBurden = 2,
        )

        assertEquals(8, outcome.fatigue)
        assertEquals(4, outcome.functionalCapacity)
        assertEquals(3, outcome.lightheadedness)
    }

    @Test
    fun `new symptom context remains bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            DailyOutcome(
                localDateIso = "2026-08-23",
                energy = 5,
                fatigue = 5,
                perceivedStress = 5,
                gastrointestinalSymptoms = 5,
                sleepQuality = 5,
                lightheadedness = 11,
            )
        }
    }

    @Test
    fun `pre forecast fatigue context is recorded before rather than inferred by the model`() {
        val checkIn = PreForecastCheckIn(
            id = "check-in-1",
            capturedAtEpochMillis = 1_000L,
            completedAtEpochMillis = 1_120L,
            localDateIso = "2026-08-23",
            localOffsetMinutes = 600,
            energy = 3,
            fatigue = 8,
            perceivedStress = 4,
            gastrointestinalSymptoms = 2,
            sleepQuality = 5,
            functionalCapacity = SelfReportScaleResponse.reported(
                4,
                ForecastCheckInScales.FUNCTIONAL_CAPACITY,
            ),
            lightheadedness = SelfReportScaleResponse.reported(
                3,
                ForecastCheckInScales.LIGHTHEADEDNESS,
            ),
            nauseaVomitingDiarrhea = SelfReportScaleResponse.reported(
                1,
                ForecastCheckInScales.NAUSEA_VOMITING_DIARRHEA,
            ),
            acuteIllnessBurden = SelfReportScaleResponse.reported(
                2,
                ForecastCheckInScales.ACUTE_ILLNESS_BURDEN,
            ),
        )

        assertEquals(SensorSource.USER_REPORTED, checkIn.source)
        assertEquals(8, checkIn.fatigue)
        assertEquals(4, checkIn.functionalCapacity.value)
        assertEquals(1_120L, checkIn.completedAtEpochMillis)
    }

    @Test
    fun `partial check in keeps missing optional responses null rather than imputing zero`() {
        val checkIn = PreForecastCheckIn(
            id = "check-in-partial",
            capturedAtEpochMillis = 2_000L,
            completedAtEpochMillis = 2_100L,
            localDateIso = "2026-08-23",
            localOffsetMinutes = 600,
            energy = 3,
            fatigue = 8,
            perceivedStress = 4,
            gastrointestinalSymptoms = 2,
            sleepQuality = 5,
            functionalCapacity = SelfReportScaleResponse.reported(
                4,
                ForecastCheckInScales.FUNCTIONAL_CAPACITY,
            ),
            lightheadedness = SelfReportScaleResponse.missing(
                SelfReportAvailability.NOT_REPORTED,
                ForecastCheckInScales.LIGHTHEADEDNESS,
            ),
            nauseaVomitingDiarrhea = SelfReportScaleResponse.missing(
                SelfReportAvailability.UNABLE_TO_REPORT,
                ForecastCheckInScales.NAUSEA_VOMITING_DIARRHEA,
            ),
            acuteIllnessBurden = SelfReportScaleResponse.missing(
                SelfReportAvailability.NOT_REPORTED,
                ForecastCheckInScales.ACUTE_ILLNESS_BURDEN,
            ),
        )

        assertEquals(SelfReportAvailability.REPORTED, checkIn.functionalCapacity.availability)
        assertEquals(SelfReportAvailability.NOT_REPORTED, checkIn.lightheadedness.availability)
        assertNull(checkIn.lightheadedness.value)
        assertNull(checkIn.nauseaVomitingDiarrhea.value)
        assertNull(checkIn.acuteIllnessBurden.value)
    }

    @Test
    fun `missing optional response cannot carry a default-looking value`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfReportScaleResponse(
                availability = SelfReportAvailability.NOT_REPORTED,
                value = 0,
                scale = ForecastCheckInScales.LIGHTHEADEDNESS,
            )
        }
    }

    @Test
    fun `glucocorticoid and orthostatic context are explicit non-sensor events`() {
        val events = listOf(
            ContextEvent(
                id = "context-taper-1",
                epochMillis = 1_000L,
                type = ContextEventType.GLUCOCORTICOID_TAPER_PHASE,
                label = "Medication phase recorded",
            ),
            ContextEvent(
                id = "context-standing-1",
                epochMillis = 2_000L,
                type = ContextEventType.ORTHOSTATIC_SYMPTOM,
                label = "Standing symptom recorded",
            ),
            ContextEvent(
                id = "context-gastro-1",
                epochMillis = 3_000L,
                type = ContextEventType.NAUSEA_VOMITING_DIARRHEA,
                label = "Acute symptom recorded",
            ),
            ContextEvent(
                id = "context-concern-1",
                epochMillis = 4_000L,
                type = ContextEventType.USER_CONCERN,
                label = "Person reports feeling unusually unwell",
            ),
        )

        assertEquals(
            listOf(
                ContextEventType.GLUCOCORTICOID_TAPER_PHASE,
                ContextEventType.ORTHOSTATIC_SYMPTOM,
                ContextEventType.NAUSEA_VOMITING_DIARRHEA,
                ContextEventType.USER_CONCERN,
            ),
            events.map(ContextEvent::type),
        )
        assertEquals(setOf(SensorSource.USER_REPORTED), events.mapTo(mutableSetOf(), ContextEvent::source))
    }
}
