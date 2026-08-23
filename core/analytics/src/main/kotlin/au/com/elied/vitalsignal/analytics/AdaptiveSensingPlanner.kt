package au.com.elied.vitalsignal.analytics

enum class PassiveEvidenceFamily {
    CARDIAC_AUTONOMIC,
    RESPIRATORY_OXYGEN,
    THERMAL,
    MOVEMENT_LOAD,
    SLEEP_RECOVERY,
}

data class PassiveChangeEvidence(
    val evidenceId: String,
    val family: PassiveEvidenceFamily,
    val absoluteRobustZ: Double,
    val quality: Double,
    val persistentMinutes: Int,
    val observedAtEpochMillis: Long,
    val provenanceIds: List<String>,
) {
    init {
        require(evidenceId.isNotBlank())
        require(absoluteRobustZ.isFinite() && absoluteRobustZ >= 0.0)
        require(quality in 0.0..1.0)
        require(persistentMinutes >= 0)
        require(observedAtEpochMillis > 0L)
        require(provenanceIds.isNotEmpty())
    }
}

enum class ResearchCaptureModality {
    ECG_WITH_EMBEDDED_PPG,
    RAW_PPG_SPOT,
    SPO2_SPOT,
    TEMPERATURE_SPOT,
    EDA_REST_CAPTURE,
}

data class ValidatedCaptureCapability(
    val modality: ResearchCaptureModality,
    val runtimeSupported: Boolean,
    val validationReceiptId: String?,
    val minimumBatteryPercent: Int,
    val foregroundUserInitiated: Boolean,
) {
    init {
        require(minimumBatteryPercent in 1..100)
    }

    val pilotQualified: Boolean
        get() = runtimeSupported &&
            !validationReceiptId.isNullOrBlank() &&
            foregroundUserInitiated
}

data class AdaptiveSensingContext(
    val evaluatedAtEpochMillis: Long,
    val batteryPercent: Int,
    val charging: Boolean = false,
    val thermalSafe: Boolean = true,
    /** `null` is unknown and therefore cannot authorize an on-body capture. */
    val onWrist: Boolean? = true,
    val storageAvailable: Boolean,
    val transportBacklogWithinLimit: Boolean,
    val collectionPaused: Boolean,
    val recoveryRequired: Boolean,
    val lastEscalationAtEpochMillis: Long?,
    val evidence: List<PassiveChangeEvidence>,
    val capabilities: List<ValidatedCaptureCapability>,
) {
    init {
        require(evaluatedAtEpochMillis > 0L)
        require(batteryPercent in 0..100)
        require(lastEscalationAtEpochMillis == null || lastEscalationAtEpochMillis <= evaluatedAtEpochMillis)
    }
}

enum class AdaptiveSensingState {
    NO_ESCALATION,
    LOW_POWER_RECHECK,
    REQUEST_FOREGROUND_CAPTURE,
    ABSTAINED,
}

data class AdaptiveSensingDecision(
    val state: AdaptiveSensingState,
    val requestedModality: ResearchCaptureModality?,
    val contributingEvidenceIds: List<String>,
    val validationReceiptId: String?,
    val requiresUserInitiation: Boolean,
    val reason: String,
    val claimBoundary: String = "Research remeasurement only; this is not a diagnosis or emergency assessment.",
) {
    init {
        require((state == AdaptiveSensingState.REQUEST_FOREGROUND_CAPTURE) == (requestedModality != null))
        require((requestedModality != null) == !validationReceiptId.isNullOrBlank())
        require(!requiresUserInitiation || state == AdaptiveSensingState.REQUEST_FOREGROUND_CAPTURE)
    }
}

/**
 * Battery-aware escalation planner. It can request a short, visible capture; it
 * cannot start an on-demand sensor, diagnose a cause, or issue an urgent alert.
 */
class AdaptiveSensingPlanner(
    private val minimumQuality: Double = 0.85,
    private val minimumAbsoluteRobustZ: Double = 2.5,
    private val minimumPersistenceMinutes: Int = 15,
    private val minimumIndependentFamilies: Int = 2,
    private val maximumEvidenceAgeMillis: Long = 30 * 60 * 1_000L,
    private val escalationCooldownMillis: Long = 6 * 60 * 60 * 1_000L,
) {
    init {
        require(minimumQuality in 0.0..1.0)
        require(minimumAbsoluteRobustZ >= 2.0)
        require(minimumPersistenceMinutes >= 1)
        require(minimumIndependentFamilies >= 2)
        require(maximumEvidenceAgeMillis > 0L)
        require(escalationCooldownMillis > 0L)
    }

    fun plan(context: AdaptiveSensingContext): AdaptiveSensingDecision {
        if (context.recoveryRequired) return abstain("Secure recovery is required")
        if (context.collectionPaused) return abstain("Collection is paused")
        if (context.charging) return abstain("Foreground capture is paused while charging")
        if (!context.thermalSafe) return abstain("Foreground capture is paused by the thermal gate")
        if (context.onWrist != true) return abstain("Verified on-wrist contact is required")
        if (!context.storageAvailable || !context.transportBacklogWithinLimit) {
            return abstain("Secure storage or transport capacity is unavailable")
        }

        val qualified = context.evidence.filter { item ->
            item.observedAtEpochMillis <= context.evaluatedAtEpochMillis &&
                context.evaluatedAtEpochMillis - item.observedAtEpochMillis <= maximumEvidenceAgeMillis &&
                item.quality >= minimumQuality &&
                item.absoluteRobustZ >= minimumAbsoluteRobustZ &&
                item.persistentMinutes >= minimumPersistenceMinutes
        }
        val families = qualified.mapTo(linkedSetOf()) { it.family }
        if (families.size < minimumIndependentFamilies) {
            val hasUnqualifiedChange = context.evidence.any {
                it.absoluteRobustZ >= minimumAbsoluteRobustZ
            }
            return AdaptiveSensingDecision(
                state = if (hasUnqualifiedChange) {
                    AdaptiveSensingState.LOW_POWER_RECHECK
                } else {
                    AdaptiveSensingState.NO_ESCALATION
                },
                requestedModality = null,
                contributingEvidenceIds = qualified.map { it.evidenceId }.sorted(),
                validationReceiptId = null,
                requiresUserInitiation = false,
                reason = if (hasUnqualifiedChange) {
                    "Change is not yet corroborated by two qualified independent signal families"
                } else {
                    "No persistent cross-family change met the research threshold"
                },
            )
        }
        if (context.lastEscalationAtEpochMillis != null &&
            context.evaluatedAtEpochMillis - context.lastEscalationAtEpochMillis < escalationCooldownMillis
        ) {
            return AdaptiveSensingDecision(
                state = AdaptiveSensingState.LOW_POWER_RECHECK,
                requestedModality = null,
                contributingEvidenceIds = qualified.map { it.evidenceId }.sorted(),
                validationReceiptId = null,
                requiresUserInitiation = false,
                reason = "The foreground-capture cooldown is active",
            )
        }

        val preferred = preferredModalities(families)
        val capability = preferred.firstNotNullOfOrNull { modality ->
            context.capabilities.firstOrNull { candidate ->
                candidate.modality == modality &&
                    candidate.pilotQualified &&
                    context.batteryPercent >= candidate.minimumBatteryPercent
            }
        }
        if (capability == null) {
            return AdaptiveSensingDecision(
                state = AdaptiveSensingState.LOW_POWER_RECHECK,
                requestedModality = null,
                contributingEvidenceIds = qualified.map { it.evidenceId }.sorted(),
                validationReceiptId = null,
                requiresUserInitiation = false,
                reason = "No validated, supported foreground capture is currently available",
            )
        }
        return AdaptiveSensingDecision(
            state = AdaptiveSensingState.REQUEST_FOREGROUND_CAPTURE,
            requestedModality = capability.modality,
            contributingEvidenceIds = qualified.map { it.evidenceId }.sorted(),
            validationReceiptId = capability.validationReceiptId,
            requiresUserInitiation = true,
            reason = "A persistent cross-family change qualifies for an optional clean research remeasurement",
        )
    }

    private fun preferredModalities(
        families: Set<PassiveEvidenceFamily>,
    ): List<ResearchCaptureModality> = when {
        PassiveEvidenceFamily.CARDIAC_AUTONOMIC in families &&
            PassiveEvidenceFamily.RESPIRATORY_OXYGEN in families -> listOf(
                ResearchCaptureModality.ECG_WITH_EMBEDDED_PPG,
                ResearchCaptureModality.SPO2_SPOT,
                ResearchCaptureModality.RAW_PPG_SPOT,
            )

        PassiveEvidenceFamily.CARDIAC_AUTONOMIC in families -> listOf(
            ResearchCaptureModality.ECG_WITH_EMBEDDED_PPG,
            ResearchCaptureModality.RAW_PPG_SPOT,
            ResearchCaptureModality.EDA_REST_CAPTURE,
        )

        PassiveEvidenceFamily.THERMAL in families -> listOf(
            ResearchCaptureModality.TEMPERATURE_SPOT,
            ResearchCaptureModality.RAW_PPG_SPOT,
        )

        else -> listOf(
            ResearchCaptureModality.RAW_PPG_SPOT,
            ResearchCaptureModality.SPO2_SPOT,
        )
    }

    private fun abstain(reason: String) = AdaptiveSensingDecision(
        state = AdaptiveSensingState.ABSTAINED,
        requestedModality = null,
        contributingEvidenceIds = emptyList(),
        validationReceiptId = null,
        requiresUserInitiation = false,
        reason = reason,
    )
}
