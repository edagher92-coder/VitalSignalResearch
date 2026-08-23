package au.com.elied.vitalsignal.model

enum class SelfReportAvailability {
    REPORTED,
    NOT_REPORTED,
    UNABLE_TO_REPORT,
}

data class SelfReportScaleDefinition(
    val id: String,
    val version: String,
    val minimum: Int,
    val maximum: Int,
    val minimumAnchor: String,
    val maximumAnchor: String,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(version.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(maximum > minimum)
        require(minimumAnchor.isNotBlank() && maximumAnchor.isNotBlank())
    }
}

data class SelfReportScaleResponse(
    val availability: SelfReportAvailability,
    val value: Int?,
    val scale: SelfReportScaleDefinition,
) {
    init {
        when (availability) {
            SelfReportAvailability.REPORTED -> require(value in scale.minimum..scale.maximum)
            SelfReportAvailability.NOT_REPORTED,
            SelfReportAvailability.UNABLE_TO_REPORT,
            -> require(value == null) { "An unavailable self-report must not carry an imputed value" }
        }
    }

    companion object {
        fun reported(value: Int, scale: SelfReportScaleDefinition) =
            SelfReportScaleResponse(SelfReportAvailability.REPORTED, value, scale)

        fun missing(
            availability: SelfReportAvailability,
            scale: SelfReportScaleDefinition,
        ): SelfReportScaleResponse {
            require(availability != SelfReportAvailability.REPORTED)
            return SelfReportScaleResponse(availability, null, scale)
        }
    }
}

object ForecastCheckInScales {
    val FUNCTIONAL_CAPACITY = SelfReportScaleDefinition(
        id = "functional-capacity-0-10",
        version = "1.0.0",
        minimum = 0,
        maximum = 10,
        minimumAnchor = "Unable to perform essential usual activities",
        maximumAnchor = "Usual or best personal functional capacity",
    )

    val LIGHTHEADEDNESS = symptomScale(
        id = "lightheadedness-burden-0-10",
        maximumAnchor = "Worst imaginable lightheadedness burden",
    )
    val NAUSEA_VOMITING_DIARRHEA = symptomScale(
        id = "nausea-vomiting-diarrhea-burden-0-10",
        maximumAnchor = "Worst imaginable combined nausea, vomiting, or diarrhea burden",
    )
    val ACUTE_ILLNESS_BURDEN = symptomScale(
        id = "acute-illness-burden-0-10",
        maximumAnchor = "Worst imaginable acute illness burden",
    )

    private fun symptomScale(id: String, maximumAnchor: String) = SelfReportScaleDefinition(
        id = id,
        version = "1.0.0",
        minimum = 0,
        maximum = 10,
        minimumAnchor = "None",
        maximumAnchor = maximumAnchor,
    )
}

/**
 * Context captured after an already-hidden forecast is committed and before it
 * is revealed. It cannot be a feature of that already-committed forecast. A
 * separately preregistered future forecast may use it after its capture time.
 */
data class PreForecastCheckIn(
    val id: String,
    val capturedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val localDateIso: String,
    val localOffsetMinutes: Int,
    val energy: Int,
    val fatigue: Int,
    val perceivedStress: Int,
    val gastrointestinalSymptoms: Int,
    val sleepQuality: Int,
    val functionalCapacity: SelfReportScaleResponse,
    val lightheadedness: SelfReportScaleResponse,
    val nauseaVomitingDiarrhea: SelfReportScaleResponse,
    val acuteIllnessBurden: SelfReportScaleResponse,
    val note: String = "",
    val source: SensorSource = SensorSource.USER_REPORTED,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(capturedAtEpochMillis >= 0)
        require(completedAtEpochMillis >= capturedAtEpochMillis)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(localDateIso))
        require(localOffsetMinutes in -18 * 60..18 * 60)
        require(energy in 0..10)
        require(fatigue in 0..10)
        require(perceivedStress in 0..10)
        require(gastrointestinalSymptoms in 0..10)
        require(sleepQuality in 0..10)
        require(functionalCapacity.scale == ForecastCheckInScales.FUNCTIONAL_CAPACITY)
        require(lightheadedness.scale == ForecastCheckInScales.LIGHTHEADEDNESS)
        require(nauseaVomitingDiarrhea.scale == ForecastCheckInScales.NAUSEA_VOMITING_DIARRHEA)
        require(acuteIllnessBurden.scale == ForecastCheckInScales.ACUTE_ILLNESS_BURDEN)
        require(note.length <= MAX_NOTE_LENGTH)
        require(source == SensorSource.USER_REPORTED)
    }

    companion object {
        const val MAX_NOTE_LENGTH = 2_000
    }
}

enum class OutcomeAvailability {
    OBSERVED,
    MISSING,
    AMBIGUOUS,
}

/**
 * A label recorded only after a committed forecast's target window ends.
 * Missing/ambiguous observations remain null and cannot silently become false.
 */
data class ForecastOutcomeObservation(
    val id: String,
    val forecastId: String,
    val endpointId: String,
    val endpointVersion: String,
    val endpointDefinitionSha256: String,
    val targetStartEpochMillis: Long,
    val targetEndEpochMillis: Long,
    val assessedAtEpochMillis: Long?,
    val recordedAtEpochMillis: Long,
    val availability: OutcomeAvailability,
    val binaryOutcome: Boolean?,
    val sourceCheckInId: String?,
    val missingReason: String = "",
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(forecastId.isNotBlank())
        require(endpointId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(endpointVersion.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(endpointDefinitionSha256.matches(Regex("[a-f0-9]{64}")))
        require(targetEndEpochMillis > targetStartEpochMillis)
        require(recordedAtEpochMillis >= targetEndEpochMillis) {
            "A forecast outcome cannot be recorded before its target window ends"
        }
        when (availability) {
            OutcomeAvailability.OBSERVED -> {
                require(binaryOutcome != null)
                require(sourceCheckInId?.isNotBlank() == true)
                val assessmentAt = requireNotNull(assessedAtEpochMillis)
                require(assessmentAt in targetStartEpochMillis until targetEndEpochMillis) {
                    "A point-assessment outcome must be assessed inside its frozen target window"
                }
                require(recordedAtEpochMillis >= assessmentAt)
                require(missingReason.isBlank())
            }

            OutcomeAvailability.MISSING,
            OutcomeAvailability.AMBIGUOUS,
            -> {
                require(binaryOutcome == null)
                require(assessedAtEpochMillis == null)
                require(missingReason.isNotBlank())
            }
        }
        require(missingReason.length <= MAX_REASON_LENGTH)
    }

    fun asBinaryDoubleOrNull(): Double? = binaryOutcome?.let { if (it) 1.0 else 0.0 }

    companion object {
        const val MAX_REASON_LENGTH = 500
    }
}
