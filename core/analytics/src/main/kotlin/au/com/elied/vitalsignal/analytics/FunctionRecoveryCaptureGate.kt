package au.com.elied.vitalsignal.analytics

/**
 * The two research inputs proposed for the function/recovery lane. They are
 * observations under an externally reviewed protocol, not exercise advice.
 */
enum class FunctionRecoveryProtocol {
    FIVE_TIMES_SIT_TO_STAND,
    FIXED_ROUTE_WALK,
}

enum class FunctionRecoveryProtocolReviewState {
    DRAFT_REQUIRES_EXTERNAL_REVIEW,
    REVIEWED_FOR_RESEARCH_CAPTURE,
}

/**
 * Identifies which repeatability controls were actually recorded. Values are
 * intentionally opaque identifiers so this platform-neutral core does not
 * direct a person how to perform a movement task.
 */
enum class FunctionRecoveryStandardizationMarker {
    INSTRUCTION_SCRIPT,
    TIMING_METHOD,
    EQUIPMENT_CONFIGURATION,
    ROUTE_OR_CHAIR_CONFIGURATION,
    PRE_CAPTURE_REST_WINDOW,
    POST_CAPTURE_RECOVERY_WINDOW,
}

/**
 * A protocol definition prepared by the study team. A reviewed status and its
 * receipt mean only that a research capture protocol was externally reviewed;
 * neither is participant clearance or an exercise prescription.
 */
class FunctionRecoveryProtocolContract(
    val protocol: FunctionRecoveryProtocol,
    val version: String,
    val reviewState: FunctionRecoveryProtocolReviewState,
    val externalProtocolReviewReceiptId: String = "",
    requiredMarkers: Set<FunctionRecoveryStandardizationMarker>,
) {
    val requiredMarkers: Set<FunctionRecoveryStandardizationMarker> =
        java.util.Set.copyOf(requiredMarkers)

    init {
        require(version.matches(SAFE_REVIEW_ID))
        require(this.requiredMarkers.isNotEmpty())
        if (reviewState == FunctionRecoveryProtocolReviewState.REVIEWED_FOR_RESEARCH_CAPTURE) {
            require(externalProtocolReviewReceiptId.matches(SAFE_REVIEW_ID))
        } else {
            require(externalProtocolReviewReceiptId.isBlank())
        }
    }
}

enum class FunctionRecoveryCaptureCompletion {
    COMPLETED_AS_REVIEWED,
    NOT_COMPLETED,
    DECLINED_BY_PARTICIPANT,
    STOPPED_BY_PARTICIPANT,
    STOPPED_BY_OBSERVER,
    PROTOCOL_DEVIATION,
}

enum class FunctionRecoveryTimingSource {
    APP_MONOTONIC_TIMER,
    VALIDATED_EXTERNAL_TIMER,
    MANUAL_TRANSCRIPTION,
}

enum class FunctionRecoveryObserverAgreement {
    AGREES_COMPLETED_AS_REVIEWED,
    DISAGREES,
    NOT_RECORDED,
}

/**
 * Typed evidence for a reference-task capture. The recorded duration remains
 * explicit even when wall-clock timestamps exist because device wall clocks
 * can move. This is research provenance, not a performance target.
 */
data class FunctionRecoveryTimingEvidence(
    val taskStartedAtEpochMillis: Long,
    val taskEndedAtEpochMillis: Long,
    val recordedDurationMillis: Long,
    val timingSource: FunctionRecoveryTimingSource,
    val observerPrincipalId: String,
    val observerAgreement: FunctionRecoveryObserverAgreement,
    val timingRecordSha256: String,
) {
    init {
        require(taskStartedAtEpochMillis > 0L)
        require(taskEndedAtEpochMillis >= taskStartedAtEpochMillis)
        require(recordedDurationMillis in 1L..MAX_REFERENCE_TASK_DURATION_MILLIS)
        require(observerPrincipalId.matches(SAFE_REVIEW_ID))
        require(timingRecordSha256.matches(SHA256_HEX))
    }

    fun hasConsistentWallClock(
        toleranceMillis: Long = DEFAULT_WALL_CLOCK_TOLERANCE_MILLIS,
    ): Boolean {
        require(toleranceMillis >= 0L)
        val wallClockDuration = taskEndedAtEpochMillis - taskStartedAtEpochMillis
        val difference = if (wallClockDuration >= recordedDurationMillis) {
            wallClockDuration - recordedDurationMillis
        } else {
            recordedDurationMillis - wallClockDuration
        }
        return difference <= toleranceMillis
    }
}

class FunctionRecoveryCaptureInput(
    val contract: FunctionRecoveryProtocolContract,
    /** An external, per-session review record; never created by this engine. */
    val externalSessionReviewReceiptId: String,
    val humanConcern: HumanConcernState,
    val completion: FunctionRecoveryCaptureCompletion,
    val sensorQuality: Double,
    markerIds: Map<FunctionRecoveryStandardizationMarker, String>,
    val timingEvidence: FunctionRecoveryTimingEvidence?,
) {
    val markerIds: Map<FunctionRecoveryStandardizationMarker, String> =
        java.util.Map.copyOf(markerIds)

    init {
        require(sensorQuality in 0.0..1.0)
        require(
            externalSessionReviewReceiptId.isBlank() ||
                externalSessionReviewReceiptId.matches(SAFE_REVIEW_ID),
        )
        require(this.markerIds.values.all { it.matches(SAFE_MARKER_ID) })
        if (completion == FunctionRecoveryCaptureCompletion.COMPLETED_AS_REVIEWED) {
            requireNotNull(timingEvidence) {
                "Completed function/recovery captures require typed timing evidence"
            }
        }
    }
}

/**
 * Verifies the externally issued protocol and per-session review evidence.
 * The analytics module never treats a caller-supplied receipt identifier as
 * authority by itself.
 */
fun interface FunctionRecoveryReviewReceiptVerifier {
    /** Verifies authority over the exact immutable capture input and timing. */
    fun verify(input: FunctionRecoveryCaptureInput): Boolean
}

enum class FunctionRecoveryCaptureGateState {
    QUALIFIED_FOR_RESEARCH_COMPARISON,
    HOLD_FOR_HUMAN_REVIEW,
    ABSTAINED,
}

class FunctionRecoveryCaptureGateDecision(
    val state: FunctionRecoveryCaptureGateState,
    val reason: String,
    missingMarkers: Set<FunctionRecoveryStandardizationMarker> = emptySet(),
) {
    val missingMarkers: Set<FunctionRecoveryStandardizationMarker> =
        java.util.Set.copyOf(missingMarkers)

    /**
     * This only permits the resulting observation to enter a research
     * comparison. It neither advises performance nor determines eligibility.
     */
    val mayCreateResearchEpisode: Boolean
        get() = state == FunctionRecoveryCaptureGateState.QUALIFIED_FOR_RESEARCH_COMPARISON
}

/**
 * Qualifies completed, externally reviewed captures before they enter
 * [StandardizedResponseEngine]. It intentionally has no sensor-derived
 * eligibility, symptom screening, diagnostic, VO2, frailty or fall logic.
 */
class FunctionRecoveryCaptureGate(
    private val reviewReceiptVerifier: FunctionRecoveryReviewReceiptVerifier,
    private val minimumSensorQuality: Double = 0.85,
) {
    init {
        require(minimumSensorQuality in 0.0..1.0)
    }

    fun qualify(input: FunctionRecoveryCaptureInput): FunctionRecoveryCaptureGateDecision {
        if (input.humanConcern != HumanConcernState.NO_CONCERN_REPORTED) {
            return FunctionRecoveryCaptureGateDecision(
                state = FunctionRecoveryCaptureGateState.HOLD_FOR_HUMAN_REVIEW,
                reason = "A human concern is present or was not captured; sensor quality cannot override it",
            )
        }
        val externallyAuthorized = try {
            input.contract.reviewState == FunctionRecoveryProtocolReviewState.REVIEWED_FOR_RESEARCH_CAPTURE &&
                input.externalSessionReviewReceiptId.isNotBlank() &&
                reviewReceiptVerifier.verify(input)
        } catch (_: RuntimeException) {
            false
        }
        if (!externallyAuthorized) {
            return abstained("External protocol and per-session research review are required")
        }
        if (input.completion != FunctionRecoveryCaptureCompletion.COMPLETED_AS_REVIEWED) {
            return abstained("Declined, incomplete, stopped, or deviated captures are not comparable research episodes")
        }
        val timing = input.timingEvidence
            ?: return abstained("Typed timing evidence is required")
        if (!timing.hasConsistentWallClock()) {
            return abstained("Task timing evidence is internally inconsistent")
        }
        if (timing.observerAgreement !=
            FunctionRecoveryObserverAgreement.AGREES_COMPLETED_AS_REVIEWED
        ) {
            return abstained("Observer completion agreement was not recorded")
        }
        val missing = input.contract.requiredMarkers - input.markerIds.keys
        if (missing.isNotEmpty()) {
            return FunctionRecoveryCaptureGateDecision(
                state = FunctionRecoveryCaptureGateState.ABSTAINED,
                reason = "Required standardization markers were not recorded",
                missingMarkers = missing,
            )
        }
        if (input.sensorQuality < minimumSensorQuality) {
            return abstained("Sensor quality is below the research comparison gate")
        }
        return FunctionRecoveryCaptureGateDecision(
            state = FunctionRecoveryCaptureGateState.QUALIFIED_FOR_RESEARCH_COMPARISON,
            reason = "Completed reviewed capture has the required repeatability markers and quality",
        )
    }

    private fun abstained(reason: String) = FunctionRecoveryCaptureGateDecision(
        state = FunctionRecoveryCaptureGateState.ABSTAINED,
        reason = reason,
    )
}

private val SAFE_REVIEW_ID = Regex("[A-Za-z0-9._-]{1,96}")
private val SAFE_MARKER_ID = Regex("[A-Za-z0-9._:-]{1,160}")
private val SHA256_HEX = Regex("[a-f0-9]{64}")
private const val MAX_REFERENCE_TASK_DURATION_MILLIS = 3_600_000L
private const val DEFAULT_WALL_CLOCK_TOLERANCE_MILLIS = 10_000L
