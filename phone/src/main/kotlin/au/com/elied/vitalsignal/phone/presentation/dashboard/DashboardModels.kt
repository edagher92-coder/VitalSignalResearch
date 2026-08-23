package au.com.elied.vitalsignal.phone.presentation.dashboard

enum class PatternStatus {
    LEARNING,
    STEADY,
    DEVELOPING,
    CHECK,
    UNAVAILABLE,
}

enum class SimulationScenario(val displayName: String) {
    DEVELOPING("Developing"),
    STEADY("Steady"),
    LEARNING("Learning"),
    LOW_QUALITY("Low quality"),
}

enum class ForecastStatus {
    LOCKED,
    LEARNING,
    ABSTAINED,
    AVAILABLE,
}

enum class ResearchAssistantStatus {
    DISABLED,
    REVIEWED_SIMULATOR_EXPLANATION,
    ABSTAINED,
    BLOCKED,
}

enum class ActivityResponseStatus {
    QUALIFIED_DESCRIPTIVE,
    LEARNING,
    ABSTAINED,
    HUMAN_CONCERN_HOLD,
}

/**
 * Presentation-only projection of the versioned activity/exercise response engine.
 * Simulator values are deterministic fixtures, never personal measurements.
 */
data class ActivityResponseUiModel(
    val status: ActivityResponseStatus,
    val protocolLabel: String,
    val steps: Long?,
    val distanceKilometres: Double?,
    val activeMinutes: Int?,
    val averageHeartRateBpm: Int?,
    val persistentPeakHeartRateBpm: Int?,
    val recoveryDropAt60SecondsBpm: Int?,
    val matchedWorkloadCardiacCost: Double?,
    val personalBandDurationLabel: String?,
    val coverageLabel: String,
    val gapLabel: String,
    val comparisonLabel: String,
    val reason: String,
    val modelVersion: String = "activity-exercise-response-v1",
) {
    init {
        require(protocolLabel.isNotBlank())
        require(coverageLabel.isNotBlank())
        require(gapLabel.isNotBlank())
        require(comparisonLabel.isNotBlank())
        require(reason.isNotBlank())
        require(modelVersion.isNotBlank())

        val metrics = listOf(
            steps,
            distanceKilometres,
            activeMinutes,
            averageHeartRateBpm,
            persistentPeakHeartRateBpm,
            recoveryDropAt60SecondsBpm,
            matchedWorkloadCardiacCost,
            personalBandDurationLabel,
        )
        val metricsMustBeWithheld = status in setOf(
            ActivityResponseStatus.ABSTAINED,
            ActivityResponseStatus.HUMAN_CONCERN_HOLD,
        )
        require(if (metricsMustBeWithheld) metrics.all { it == null } else metrics.all { it != null }) {
            "Activity response values must be complete when descriptive and absent when withheld"
        }
        steps?.let { require(it >= 0L) }
        distanceKilometres?.let { require(it.isFinite() && it >= 0.0) }
        activeMinutes?.let { require(it > 0) }
        averageHeartRateBpm?.let { require(it in 20..260) }
        persistentPeakHeartRateBpm?.let { require(it in 20..260) }
        recoveryDropAt60SecondsBpm?.let { require(it in -240..240) }
        matchedWorkloadCardiacCost?.let { require(it.isFinite()) }
        personalBandDurationLabel?.let { require(it.isNotBlank()) }
    }
}

data class DashboardUiState(
    val greeting: String,
    val dataModeLabel: String,
    val isSimulated: Boolean,
    val activeSimulationScenario: SimulationScenario,
    val status: PatternStatus,
    val headline: String,
    val summary: String,
    val nextStep: String,
    val confidence: Int,
    val qualifiedSignalCount: Int,
    val recheckLabel: String,
    val baselineDays: Int,
    val baselineTargetDays: Int,
    val signalQuality: Int,
    val coverageHours: Double,
    val connectedDevice: String,
    val lastSyncLabel: String,
    val forecast: ForecastUiModel,
    val researchAssistant: ResearchAssistantUiModel,
    val activityResponse: ActivityResponseUiModel,
    val dataPlane: DataPlaneUiModel,
    val evidence: List<EvidenceUiModel>,
    val trend: List<TrendPointUiModel>,
    val qualitySignals: List<QualitySignalUiModel>,
    val timeline: List<TimelineItemUiModel>,
    val fiveSecondSummary: FiveSecondSummaryUiModel = FiveSecondSummaryUiModel(),
    val conflictDesk: List<ConflictDeskItemUiModel> = emptyList(),
    val featureInspector: List<FeatureInspectorRowUiModel> = emptyList(),
    val forecastAudit: List<ForecastAuditEventUiModel> = emptyList(),
    val explanationExpanded: Boolean = false,
    val quickLogOpen: Boolean = false,
    val activeHumanConcern: Boolean = false,
    val savedMessage: String? = null,
)

data class ResearchAssistantUiModel(
    val status: ResearchAssistantStatus,
    val title: String,
    val providerLabel: String,
    val narrative: String,
    val evidenceLabels: List<String>,
    val policyLabel: String,
)

data class DataPlaneUiModel(
    val activeMode: String,
    val pilotGateLabel: String,
    val receiptState: String,
    val forecastAuditState: String,
    val integrityDetail: String,
)

data class ForecastUiModel(
    val status: ForecastStatus,
    val horizonLabel: String,
    val headline: String,
    val summary: String,
    val probability: Int?,
    val personalBaseRate: Int?,
    val intervalLabel: String,
    val calibrationLabel: String,
)

data class EvidenceUiModel(
    val id: String,
    val label: String,
    val value: String,
    val comparison: String,
    val direction: EvidenceDirection,
    val quality: Int,
    val provenance: String,
)

enum class EvidenceDirection { SUPPORTS_STEADY, CONTRIBUTES_TO_CHANGE, CONTEXT_ONLY }

data class TrendPointUiModel(
    val dayLabel: String,
    val value: Float,
    val expectedLower: Float,
    val expectedUpper: Float,
)

data class QualitySignalUiModel(
    val label: String,
    val score: Int,
    val note: String,
)

data class FiveSecondSummaryUiModel(
    val whatChanged: String = "No five-second summary is available",
    val evidence: String = "Evidence withheld",
    val nextStep: String = "Record how you feel if something concerns you",
) {
    init {
        require(whatChanged.isNotBlank())
        require(evidence.isNotBlank())
        require(nextStep.isNotBlank())
    }
}

data class ConflictDeskItemUiModel(
    val id: String,
    val title: String,
    val detail: String,
    val action: String,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(detail.isNotBlank())
        require(action.isNotBlank())
    }
}

data class FeatureInspectorRowUiModel(
    val featureId: String,
    val version: String,
    val windowLabel: String,
    val quality: Int,
    val snapshotSha256Prefix: String,
    val provenanceLabel: String,
) {
    init {
        require(featureId.isNotBlank())
        require(version.isNotBlank())
        require(windowLabel.isNotBlank())
        require(quality in 0..100)
        require(snapshotSha256Prefix.matches(Regex("[a-f0-9]{12}")))
        require(provenanceLabel.isNotBlank())
    }
}

data class ForecastAuditEventUiModel(
    val id: String,
    val state: String,
    val timeLabel: String,
    val detail: String,
) {
    init {
        require(id.isNotBlank())
        require(state.isNotBlank())
        require(timeLabel.isNotBlank())
        require(detail.isNotBlank())
    }
}

enum class TimelineKind { INSIGHT, MEASUREMENT, CONTEXT, SYSTEM }

data class TimelineItemUiModel(
    val id: String,
    val timeLabel: String,
    val title: String,
    val detail: String,
    val kind: TimelineKind,
)

data class QuickLogDraft(
    val energy: Int? = null,
    val fatigue: Int? = null,
    val stress: Int? = null,
    val gastrointestinalSymptoms: Int? = null,
    val sleepQuality: Int? = null,
    val userConcernReported: Boolean = false,
    val note: String = "",
) {
    init {
        listOf(energy, fatigue, stress, gastrointestinalSymptoms, sleepQuality)
            .filterNotNull()
            .forEach { require(it in 0..10) }
        require(note.length <= 140)
    }

    val hasCompleteForecastContext: Boolean
        get() = energy != null && fatigue != null && stress != null &&
            gastrointestinalSymptoms != null && sleepQuality != null
}
