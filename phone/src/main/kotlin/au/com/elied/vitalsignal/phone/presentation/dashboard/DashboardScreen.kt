package au.com.elied.vitalsignal.phone.presentation.dashboard

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.elied.vitalsignal.phone.presentation.brand.ProductBrand
import au.com.elied.vitalsignal.phone.ui.theme.Amber
import au.com.elied.vitalsignal.phone.ui.theme.Blue
import au.com.elied.vitalsignal.phone.ui.theme.Ice
import au.com.elied.vitalsignal.phone.ui.theme.Ink
import au.com.elied.vitalsignal.phone.ui.theme.Mint
import au.com.elied.vitalsignal.phone.ui.theme.MintSoft
import au.com.elied.vitalsignal.phone.ui.theme.Quiet
import au.com.elied.vitalsignal.phone.ui.theme.Rose
import au.com.elied.vitalsignal.phone.ui.theme.Slate
import au.com.elied.vitalsignal.phone.ui.theme.SurfaceDeep
import au.com.elied.vitalsignal.phone.ui.theme.SurfaceLifted
import au.com.elied.vitalsignal.phone.ui.theme.Violet
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onToggleExplanation: () -> Unit,
    onOpenQuickLog: () -> Unit,
    onCloseQuickLog: () -> Unit,
    onSaveQuickLog: (QuickLogDraft) -> Unit,
    onReportHumanConcern: () -> Unit,
    onResolveHumanConcern: () -> Unit,
    onSavedMessageShown: () -> Unit,
    onSelectSimulationScenario: (SimulationScenario) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var resolveConcernConfirmationOpen by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(DashboardPane.TODAY) }
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            onSavedMessageShown()
        }
    }
    LaunchedEffect(state.activeHumanConcern) {
        if (state.activeHumanConcern) {
            pane = paneAfterConcernHold()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            DashboardBottomBar(
                pane = pane,
                onSelectPane = { pane = it },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF06191A), Ink, Color(0xFF031011)),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
                    )
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DashboardHeader(state)
                if (state.isSimulated) SimulationBanner(state.dataModeLabel)
                when (pane) {
                    DashboardPane.TODAY -> TodayPane(
                        state = state,
                        onToggleExplanation = onToggleExplanation,
                        onOpenQuickLog = onOpenQuickLog,
                        onReportHumanConcern = onReportHumanConcern,
                        onResolveHumanConcern = { resolveConcernConfirmationOpen = true },
                    )
                    DashboardPane.EVIDENCE -> EvidencePane(state)
                    DashboardPane.LAB -> LabPane(
                        state = state,
                        onSelectSimulationScenario = onSelectSimulationScenario,
                    )
                }
                SafetyNote()
            }
        }
    }

    if (state.quickLogOpen) {
        QuickLogDialog(
            onDismiss = onCloseQuickLog,
            onSave = onSaveQuickLog,
            onReportHumanConcern = onReportHumanConcern,
        )
    }
    if (resolveConcernConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { resolveConcernConfirmationOpen = false },
            containerColor = SurfaceLifted,
            title = { Text("Resolve this app hold?", color = Ice) },
            text = {
                Text(
                    "This records an explicit human action in the simulator only. It is not medical clearance, does not mean you are well, and does not replace care.",
                    color = Slate,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        resolveConcernConfirmationOpen = false
                        onResolveHumanConcern()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Ice),
                ) { Text("Resolve simulator hold") }
            },
            dismissButton = {
                TextButton(onClick = { resolveConcernConfirmationOpen = false }) {
                    Text("Keep hold active", color = Ice)
                }
            },
        )
    }
}

@Composable
private fun TodayPane(
    state: DashboardUiState,
    onToggleExplanation: () -> Unit,
    onOpenQuickLog: () -> Unit,
    onReportHumanConcern: () -> Unit,
    onResolveHumanConcern: () -> Unit,
) {
    PatternHeroCard(
        state,
        onToggleExplanation,
        onResolveHumanConcern = onResolveHumanConcern,
    )
    TodayActions(
        concernActive = state.activeHumanConcern,
        forecastLocked = state.forecast.status == ForecastStatus.LOCKED,
        onReportConcern = onReportHumanConcern,
        onCheckIn = onOpenQuickLog,
    )
    if (!state.activeHumanConcern) {
        ForecastCard(state.forecast)
    }
}

@Composable
private fun TodayActions(
    concernActive: Boolean,
    forecastLocked: Boolean,
    onReportConcern: () -> Unit,
    onCheckIn: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!concernActive) {
            OutlinedButton(
                onClick = onReportConcern,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose),
            ) {
                Text("I feel concerned")
            }
        }
        if (forecastLocked && !concernActive) {
            Button(
                onClick = onCheckIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
            ) {
                Text("Record pre-forecast check-in")
            }
        }
    }
}

@Composable
private fun EvidencePane(state: DashboardUiState) {
    PaneIntro(
        title = "Evidence",
        summary = "Follow the story down to qualified fixtures. Nothing here is a diagnosis.",
    )
    if (state.activeHumanConcern) {
        WithheldByConcernCard()
        return
    }
    ResearchAssistantCard(state.researchAssistant)
    PersonalTrendCard(state.trend, state.baselineDays, state.baselineTargetDays)
    ActivityWorkloadResponseCard(state.activityResponse)
    SignalQualityCard(state)
    InterpretationTraceCard(state)
    TimelineCard(state.timeline)
}

@Composable
private fun LabPane(
    state: DashboardUiState,
    onSelectSimulationScenario: (SimulationScenario) -> Unit,
) {
    PaneIntro(
        title = "Lab",
        summary = "Operator fixtures for this simulator session. Not personal data and not a live monitor.",
    )
    if (state.activeHumanConcern) {
        WithheldByConcernCard()
        return
    }
    DataPlaneCard(state.dataPlane)
    if (state.conflictDesk.isNotEmpty()) ConflictDeskCard(state.conflictDesk)
    if (state.featureInspector.isNotEmpty()) FeatureInspectorCard(state.featureInspector)
    if (state.forecastAudit.isNotEmpty()) ForecastAuditTimeline(state.forecastAudit)
    if (state.isSimulated) {
        SimulationLab(
            selected = state.activeSimulationScenario,
            onSelect = onSelectSimulationScenario,
        )
    }
}

@Composable
@Composable
private fun PaneIntro(title: String, summary: String) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ice,
        )
        Text(
            text = summary,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
    }
}

@Composable
private fun WithheldByConcernCard() {
    DashboardCard {
        SectionLabel("Hold active")
        Text(
            text = "Wearable interpretation is withheld",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Text(
            text = "A person-reported concern takes priority. These research surfaces stay hidden so a reassuring fixture cannot override how you feel. Return to Today to keep or resolve the simulator hold.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
    }
}

@Composable
private fun ResearchAssistantCard(assistant: ResearchAssistantUiModel) {
    val template = assistantTemplate(assistant.templateId)
    val accent = when (assistant.status) {
        ResearchAssistantStatus.REVIEWED_SIMULATOR_EXPLANATION -> Blue
        ResearchAssistantStatus.ABSTAINED -> Amber
        ResearchAssistantStatus.BLOCKED -> Rose
        ResearchAssistantStatus.DISABLED -> Quiet
    }
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Governed assistant")
                Text(
                    text = assistant.title,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ice,
                )
            }
            StatusPill(
                label = when (assistant.status) {
                    ResearchAssistantStatus.REVIEWED_SIMULATOR_EXPLANATION ->
                        "REVIEWED"
                    ResearchAssistantStatus.ABSTAINED -> "ABSTAINED"
                    ResearchAssistantStatus.BLOCKED -> "BLOCKED"
                    ResearchAssistantStatus.DISABLED -> "DISABLED"
                },
                color = accent,
            )
        }
        Text(
            text = assistant.providerLabel,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            color = accent.copy(alpha = 0.07f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.16f)),
        ) {
            Text(
                text = template.narrative,
                modifier = Modifier.padding(15.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Ice,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            template.evidenceLabels.forEach { label ->
                Surface(
                    color = SurfaceLifted,
                    shape = RoundedCornerShape(999.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF315356)),
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate,
                    )
                }
            }
        }
        Text(
            text = "STATIC REVIEWED TEMPLATE · NO AI RUN · ${assistant.policyLabel}",
            modifier = Modifier.padding(top = 13.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
        Text(
            text = "Cloud OpenAI/Claude and local Ollama remain disabled in this screen until a consent- and policy-bound gateway is activated.",
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
    }
}

private data class AssistantTemplate(
    val narrative: String,
    val evidenceLabels: List<String>,
)

private fun assistantTemplate(id: ResearchAssistantTemplateId): AssistantTemplate = when (id) {
    ResearchAssistantTemplateId.DEVELOPING_REMEASURE -> AssistantTemplate(
        narrative = "One simulated cardio-autonomic family differs from its matched fixture. A second independent domain and persistence are not present, so the reviewed interpretation remains: record context and remeasure.",
        evidenceLabels = listOf(
            "Cardio-autonomic family",
            "Independent-domain gate",
            "Measurement quality",
        ),
    )
    ResearchAssistantTemplateId.WITHIN_PATTERN -> AssistantTemplate(
        narrative = "Qualified simulated domains are close to their matched fixture ranges. This describes the available fixture only and cannot rule out a health condition or override how a person feels.",
        evidenceLabels = listOf("Matched fixture", "Qualified evidence", "No medical clearance"),
    )
    ResearchAssistantTemplateId.PATTERN_REVIEW -> AssistantTemplate(
        narrative = "More than one independent simulated family meets the research pattern gate. The evidence supports review and remeasurement, not a diagnosis, cause, or treatment decision.",
        evidenceLabels = listOf("Independent families", "Review gate", "Remeasure"),
    )
    ResearchAssistantTemplateId.EVIDENCE_ABSTAINED -> AssistantTemplate(
        narrative = "The available fixture does not support a health interpretation. Missing, immature, or low-quality evidence remains unavailable rather than being filled in as normal.",
        evidenceLabels = emptyList(),
    )
    ResearchAssistantTemplateId.SAFETY_BLOCKED -> AssistantTemplate(
        narrative = "A person-reported concern or reviewed symptom route takes priority. Wearable interpretation is withheld; no assistant response can provide reassurance or medical clearance.",
        evidenceLabels = emptyList(),
    )
}

@Composable
private fun DashboardHeader(state: DashboardUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ProductBrand.DISPLAY_NAME,
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    text = ProductBrand.TAGLINE,
                    style = MaterialTheme.typography.labelMedium,
                    color = Quiet,
                )
            }
            Surface(
                color = Amber.copy(alpha = 0.12f),
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.28f)),
            ) {
                Text(
                    text = if (state.isSimulated) "Simulator" else "Pilot data mode",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber,
                )
            }
        }
        Text(
            text = state.greeting,
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.headlineLarge,
            color = Ice,
        )
        Text(
            text = state.lastSyncLabel,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(Mint.copy(alpha = 0.22f), Blue.copy(alpha = 0.14f)),
                ),
            )
            .border(1.dp, Mint.copy(alpha = 0.28f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            val baselineRibbon = Path().apply {
                moveTo(size.width * 0.06f, size.height * 0.35f)
                cubicTo(
                    size.width * 0.30f,
                    size.height * 0.10f,
                    size.width * 0.65f,
                    size.height * 0.18f,
                    size.width * 0.94f,
                    size.height * 0.56f,
                )
            }
            val observedRibbon = Path().apply {
                moveTo(size.width * 0.06f, size.height * 0.66f)
                cubicTo(
                    size.width * 0.34f,
                    size.height * 0.90f,
                    size.width * 0.63f,
                    size.height * 0.78f,
                    size.width * 0.94f,
                    size.height * 0.42f,
                )
            }
            drawPath(
                baselineRibbon,
                Mint,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                observedRibbon,
                Blue,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(
                color = Ice,
                radius = 2.2.dp.toPx(),
                center = Offset(size.width * 0.73f, size.height * 0.57f),
            )
        }
    }
}

@Composable
private fun SimulationBanner(label: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        color = Amber.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.34f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(Amber, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = Amber,
                )
                Text(
                    text = "Independent research prototype · no Samsung or Apple affiliation",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Amber.copy(alpha = 0.74f),
                )
            }
        }
    }
}

@Composable
private fun PatternHeroCard(
    state: DashboardUiState,
    onToggleExplanation: () -> Unit,
    onResolveHumanConcern: () -> Unit,
) {
    val statusColor = when (state.status) {
        PatternStatus.STEADY -> Mint
        PatternStatus.LEARNING -> Blue
        PatternStatus.DEVELOPING -> Amber
        PatternStatus.CHECK -> if (state.activeHumanConcern) Rose else Amber
        PatternStatus.UNAVAILABLE -> Amber
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.14f),
                            SurfaceLifted,
                            Color(0xFF0B2022),
                        ),
                        start = Offset.Zero,
                        end = Offset(900f, 1000f),
                    ),
                )
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = when (state.status) {
                        PatternStatus.STEADY -> "WITHIN PATTERN"
                        PatternStatus.LEARNING -> "LEARNING"
                        PatternStatus.DEVELOPING -> "DEVELOPING"
                        PatternStatus.CHECK -> "CHECK"
                        PatternStatus.UNAVAILABLE -> "DATA UNAVAILABLE"
                    },
                    color = statusColor,
                )
                Spacer(Modifier.weight(1f))
                ConfidenceBeacon(
                    value = state.confidence,
                    label = when {
                        state.confidence > 0 -> "evidence score"
                        state.status == PatternStatus.LEARNING -> "learning"
                        state.status == PatternStatus.STEADY -> "no deviation"
                        else -> "withheld"
                    },
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.headline,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.headlineMedium,
                color = Ice,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = Slate,
            )
            FiveSecondSummaryRow(state.fiveSecondSummary)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                color = Color(0xFF0C2023),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "WHAT TO DO NEXT",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mint,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        text = state.nextStep,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ice,
                    )
                }
            }
            Text(
                text = "How you feel matters more than the score. This app cannot diagnose or rule out a medical condition.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
            if (state.confidence > 0 && !state.activeHumanConcern) {
                Text(
                    text = "The internal evidence score summarizes quality-qualified simulator evidence and policy support. It is not a probability, wellness/readiness score, or “all clear.”",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Quiet,
                )
            }
            if (state.activeHumanConcern) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    color = Rose.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Rose.copy(alpha = 0.30f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "SIMULATOR-SESSION CONCERN ACTIVE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Rose,
                        )
                        Text(
                            "A model, scenario change, or reassuring sensor value cannot clear this during the current simulator session. No clinician or emergency service was notified.",
                            modifier = Modifier.padding(top = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ice,
                        )
                        OutlinedButton(
                            onClick = onResolveHumanConcern,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ice),
                        ) {
                            Text("Resolve simulator hold")
                        }
                        Text(
                            "Resolving this app hold is not medical clearance and does not replace care.",
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onToggleExplanation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ice),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF35565A)),
                contentPadding = PaddingValues(vertical = 13.dp),
            ) {
                Text(
                    if (state.explanationExpanded) {
                        "Hide reasoning"
                    } else if (state.evidence.isEmpty()) {
                        "Why interpretation was withheld"
                    } else {
                        "See why this changed"
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.explanationExpanded) "↑" else "↓", color = Mint)
            }

            AnimatedVisibility(visible = state.explanationExpanded) {
                EvidencePanel(state.evidence)
            }
        }
    }
}

@Composable
private fun ConfidenceBeacon(value: Int, label: String, color: Color) {
    val motionEnabled = rememberVitalMotionEnabled()
    val animatedValue by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = if (motionEnabled) {
            spring(dampingRatio = 1f, stiffness = 240f)
        } else {
            snap()
        },
        label = "evidence-score",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .semantics {
                    contentDescription = if (value > 0) "$value of 100 $label" else label
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                drawArc(
                    color = Ice.copy(alpha = 0.08f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (value > 0) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.32f), color)),
                        startAngle = -90f,
                        sweepAngle = animatedValue * 3.6f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = if (value > 0) value.toString() else "—",
                style = MaterialTheme.typography.titleLarge,
                color = Ice,
            )
        }
        Text(
            text = label,
            modifier = Modifier
                .width(72.dp)
                .padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun EvidencePanel(evidence: List<EvidenceUiModel>) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        HorizontalDivider(color = Color(0xFF29464A))
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Evidence trail",
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Text(
            text = "Every interpretation links back to its source window.",
            style = MaterialTheme.typography.bodyMedium,
            color = Quiet,
        )
        Spacer(Modifier.height(8.dp))
        if (evidence.isEmpty()) {
            Text(
                text = "No physiological evidence was used. The simulator stopped at a learning or measurement-quality gate.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
        } else {
            evidence.forEachIndexed { index, item ->
                EvidenceRow(item)
                if (index != evidence.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = Color(0xFF213B3E),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            color = Color(0xFF0C2023),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = "This pattern is compared with your own time-of-day and activity-matched baseline. Correlated signals are grouped so they are not counted twice.",
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
        }
    }
}

@Composable
private fun EvidenceRow(item: EvidenceUiModel) {
    val color = when (item.direction) {
        EvidenceDirection.SUPPORTS_STEADY -> Mint
        EvidenceDirection.CONTRIBUTES_TO_CHANGE -> Amber
        EvidenceDirection.CONTEXT_ONLY -> Blue
    }
    Row(
        modifier = Modifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(9.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ice,
                )
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.comparison,
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
            Text(
                text = "${item.quality}% quality · ${item.provenance}",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
            )
        }
    }
}

@Composable
private fun ForecastCard(
    forecast: ForecastUiModel,
) {
    var explanationExpanded by remember(forecast.status) { mutableStateOf(false) }
    DashboardCard {
        SectionLabel(forecast.horizonLabel)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = forecast.headline,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ice,
                )
                Text(
                    text = forecast.summary,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate,
                )
            }
            if (forecast.status == ForecastStatus.AVAILABLE && forecast.probability != null) {
                Spacer(Modifier.width(14.dp))
                ProbabilityRing(forecast.probability, forecast.headline)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = forecast.intervalLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
            )
            StatusPill(
                label = forecast.calibrationLabel,
                color = when (forecast.status) {
                    ForecastStatus.AVAILABLE -> Blue
                    ForecastStatus.LOCKED -> Amber
                    ForecastStatus.LEARNING -> Blue
                    ForecastStatus.ABSTAINED -> Rose
                },
            )
        }
        if (forecast.status == ForecastStatus.LOCKED) {
            Text(
                text = "Use the check-in button above to record pre-reveal context. The estimate stays hidden until that check-in is complete.",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
        }
        if (
            forecast.status == ForecastStatus.AVAILABLE &&
            forecast.probability != null &&
            forecast.personalBaseRate != null
        ) {
            Text(
                text = "Simulated estimate ${forecast.probability}% · fixture base rate ${forecast.personalBaseRate}%",
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
        }
        if (
            forecast.status == ForecastStatus.AVAILABLE &&
            forecast.probability != null &&
            forecast.explanation != null
        ) {
            val explanation = forecast.explanation
            OutlinedButton(
                onClick = { explanationExpanded = !explanationExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(top = 14.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ice),
                border = androidx.compose.foundation.BorderStroke(1.dp, Blue.copy(alpha = 0.32f)),
            ) {
                Text(if (explanationExpanded) "Hide how this was calculated" else "Explain this estimate")
            }
            AnimatedVisibility(visible = explanationExpanded) {
                ForecastExplanation(explanation)
            }
        }
    }
}

@Composable
private fun ForecastExplanation(explanation: ForecastExplanationUiModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .animateContentSize(animationSpec = spring(dampingRatio = 0.82f, stiffness = 240f)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ExplanationBlock("WHAT THIS PERCENTAGE MEANS", explanation.meaning, Blue)
        ExplanationBlock("ESTIMATE VS FIXTURE BASE RATE", explanation.comparison, Violet)
        ExplanationList("WHY IT LANDED HERE", explanation.why, Amber)
        ExplanationList("HOW IT WAS CALCULATED", explanation.method, Mint)
        ExplanationList("WHAT COULD CHANGE A FUTURE ESTIMATE", explanation.couldChange, Blue)
        ExplanationList("HOW THIS MUST IMPROVE", explanation.improvementPlan, Violet)
        Text(
            text = "Similarity is not causality. These generated values cannot tell you why you feel a certain way or what will happen.",
            style = MaterialTheme.typography.bodyMedium,
            color = Rose,
        )
    }
}

@Composable
private fun ExplanationBlock(label: String, body: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = 0.075f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            Text(
                text = body,
                modifier = Modifier.padding(top = 7.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Ice,
            )
        }
    }
}

@Composable
private fun ExplanationList(label: String, items: List<String>, accent: Color) {
    Surface(
        color = Color.White.copy(alpha = 0.035f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Ice.copy(alpha = 0.09f)),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            items.forEachIndexed { index, item ->
                Row(Modifier.padding(top = 9.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = accent)
                    Spacer(Modifier.width(10.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium, color = Slate)
                }
            }
        }
    }
}

@Composable
private fun ProbabilityRing(probability: Int, outcomeLabel: String) {
    val motionEnabled = rememberVitalMotionEnabled()
    val animatedProbability by animateFloatAsState(
        targetValue = probability.toFloat(),
        animationSpec = if (motionEnabled) {
            spring(dampingRatio = 1f, stiffness = 220f)
        } else {
            snap()
        },
        label = "forecast-probability",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .semantics {
                contentDescription = "$probability percent simulator probability for $outcomeLabel"
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color(0xFF264649),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = Amber,
                startAngle = -90f,
                sweepAngle = animatedProbability * 3.6f,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$probability%",
                style = MaterialTheme.typography.titleLarge,
                color = Ice,
            )
            Text(
                text = "fixture",
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
            )
        }
    }
}

@Composable
private fun PersonalTrendCard(
    points: List<TrendPointUiModel>,
    baselineDays: Int,
    targetDays: Int,
) {
    val latest = points.lastOrNull()
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Personal pattern")
                Text(
                text = "Personal deviation · 7 days",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ice,
                )
            }
            Text(
                text = "$baselineDays / $targetDays days",
                style = MaterialTheme.typography.labelMedium,
                color = Blue,
            )
        }
        if (latest != null) {
            Row(
                modifier = Modifier.padding(top = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrendMetric(
                    label = "TODAY",
                    value = "${if (latest.value >= 0f) "+" else ""}${"%.1f".format(latest.value)} robust units",
                    color = if (latest.value in latest.expectedLower..latest.expectedUpper) Mint else Amber,
                    modifier = Modifier.weight(1f),
                )
                TrendMetric(
                    label = "PERSONAL BAND",
                    value = "${"%.1f".format(latest.expectedLower)} to +${"%.1f".format(latest.expectedUpper)} robust units",
                    color = Blue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (points.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceDeep,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    "No qualified current point. Missing, immature, or low-quality data is not displayed as zero or normal.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate,
                )
            }
        } else {
            TrendChart(points)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendItem(Mint.copy(alpha = 0.25f), "Expected range")
                Spacer(Modifier.width(16.dp))
                LegendItem(Amber, "Qualified pattern")
            }
        }
        Spacer(Modifier.height(16.dp))
        ProgressTrack(
            progress = (baselineDays.toFloat() / targetDays).coerceIn(0f, 1f),
            color = Blue,
        )
        Text(
            text = if (baselineDays >= targetDays) {
                "The fixture passes the baseline-duration gate. Product validation remains separate."
            } else {
                "Learning is active. Interpretations remain withheld until the baseline matures."
            },
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Quiet,
        )
    }
}

@Composable
private fun ActivityWorkloadResponseCard(activity: ActivityResponseUiModel) {
    val statusColor = when (activity.status) {
        ActivityResponseStatus.QUALIFIED_DESCRIPTIVE -> Mint
        ActivityResponseStatus.LEARNING -> Blue
        ActivityResponseStatus.ABSTAINED -> Amber
        ActivityResponseStatus.HUMAN_CONCERN_HOLD -> Rose
    }
    val statusLabel = when (activity.status) {
        ActivityResponseStatus.QUALIFIED_DESCRIPTIVE -> "QUALIFIED FIXTURE · UNVALIDATED"
        ActivityResponseStatus.LEARNING -> "LEARNING · UNVALIDATED"
        ActivityResponseStatus.ABSTAINED -> "ABSTAINED"
        ActivityResponseStatus.HUMAN_CONCERN_HOLD -> "HUMAN PRIORITY"
    }
    val analyticsVisible = activity.status in setOf(
        ActivityResponseStatus.QUALIFIED_DESCRIPTIVE,
        ActivityResponseStatus.LEARNING,
    )

    DashboardCard {
        SectionLabel("Activity + workload response")
        Text(
            text = if (activity.status == ActivityResponseStatus.HUMAN_CONCERN_HOLD) {
                "Your concern takes priority"
            } else {
                "Matched walk response"
            },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Box(modifier = Modifier.padding(top = 10.dp)) {
            StatusPill(statusLabel, statusColor)
        }
        Text(
            text = activity.protocolLabel,
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
            letterSpacing = 0.45.sp,
        )

        if (analyticsVisible) {
            val steps = requireNotNull(activity.steps)
            val distance = requireNotNull(activity.distanceKilometres)
            val activeMinutes = requireNotNull(activity.activeMinutes)
            val averageHeartRate = requireNotNull(activity.averageHeartRateBpm)
            val persistentPeak = requireNotNull(activity.persistentPeakHeartRateBpm)
            val recoveryDrop = requireNotNull(activity.recoveryDropAt60SecondsBpm)
            val cardiacCost = requireNotNull(activity.matchedWorkloadCardiacCost)

            Row(
                modifier = Modifier.padding(top = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                StatBlock("Session steps", String.format(Locale.US, "%,d", steps), Modifier.weight(1f))
                StatBlock("Distance", String.format(Locale.US, "%.2f km", distance), Modifier.weight(1f))
                StatBlock("Active time", "$activeMinutes min", Modifier.weight(1f))
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = statusColor.copy(alpha = 0.065f),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    statusColor.copy(alpha = 0.16f),
                ),
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text(
                        "QUALIFIED HEART RESPONSE",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        letterSpacing = 0.7.sp,
                    )
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        ActivityMetric(
                            label = "AVG HR",
                            value = "$averageHeartRate bpm",
                            note = "time-weighted",
                            modifier = Modifier.weight(1f),
                        )
                        ActivityMetric(
                            label = "PEAK HR",
                            value = "$persistentPeak bpm",
                            note = "persistent 95th percentile",
                            modifier = Modifier.weight(1f),
                        )
                        ActivityMetric(
                            label = "HR RECOVERY",
                            value = "$recoveryDrop bpm",
                            note = "drop at 60 seconds",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = SurfaceLifted.copy(alpha = 0.78f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    ActivityDetailRow(
                        "Matched-workload cardiac cost",
                        String.format(Locale.US, "%.1f fixture units", cardiacCost),
                    )
                    Text(
                        "Time-weighted heart rate above the session's protocol resting reference per versioned workload unit; neither higher nor lower is interpreted alone.",
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Quiet,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 11.dp),
                        color = Color(0xFF29464A),
                    )
                    ActivityDetailRow(
                        "Personal HR-band time",
                        requireNotNull(activity.personalBandDurationLabel),
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 15.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = statusColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(17.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    statusColor.copy(alpha = 0.22f),
                ),
            ) {
                Text(
                    text = activity.reason,
                    modifier = Modifier.padding(15.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ice,
                )
            }
        }

        if (activity.status != ActivityResponseStatus.HUMAN_CONCERN_HOLD) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = SurfaceDeep,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    ActivityDetailRow("Coverage", activity.coverageLabel)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF29464A),
                    )
                    ActivityDetailRow("Gap state", activity.gapLabel)
                }
            }
        }
        Text(
            text = activity.comparisonLabel,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
        )
        if (analyticsVisible) {
            Text(
                text = activity.reason,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Slate,
            )
        }
        Text(
            text = "${activity.modelVersion} · deterministic simulator fixture · no personal or live watch data",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
    }
}

@Composable
private fun ActivityMetric(
    label: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
            fontSize = 12.sp,
            letterSpacing = 0.55.sp,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Ice,
            maxLines = 1,
        )
        Text(
            text = note,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
    }
}

@Composable
private fun ActivityDetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Quiet,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 3.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Ice,
        )
    }
}

@Composable
private fun TrendMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = SurfaceLifted.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.13f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
                fontSize = 12.sp,
                letterSpacing = 0.55.sp,
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrendChart(points: List<TrendPointUiModel>) {
    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp)
                .semantics {
                    contentDescription = "Seven-day personal physiological deviation chart; the most recent qualified point is ${points.last().value} robust baseline units from expected"
                },
        ) {
            if (points.size < 2) return@Canvas
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val minValue = -1.5f
            val maxValue = 1.5f
            fun y(value: Float): Float = bottom - ((value - minValue) / (maxValue - minValue)) * (bottom - top)
            fun x(index: Int): Float = left + (right - left) * index / (points.size - 1)

            val expectedPath = Path().apply {
                moveTo(x(0), y(points.first().expectedUpper))
                points.indices.drop(1).forEach { lineTo(x(it), y(points[it].expectedUpper)) }
                points.indices.reversed().forEach { lineTo(x(it), y(points[it].expectedLower)) }
                close()
            }
            drawPath(
                path = expectedPath,
                brush = Brush.verticalGradient(
                    listOf(Mint.copy(alpha = 0.16f), Mint.copy(alpha = 0.055f)),
                    startY = y(points.first().expectedUpper),
                    endY = y(points.first().expectedLower),
                ),
            )
            listOf(-1f, 1f).forEach { gridValue ->
                drawLine(
                    color = Ice.copy(alpha = 0.035f),
                    start = Offset(left, y(gridValue)),
                    end = Offset(right, y(gridValue)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawLine(
                color = Color(0xFF294548),
                start = Offset(left, y(0f)),
                end = Offset(right, y(0f)),
                strokeWidth = 1.dp.toPx(),
            )
            val trendPath = Path().apply {
                moveTo(x(0), y(points.first().value))
                points.indices.drop(1).forEach { lineTo(x(it), y(points[it].value)) }
            }
            val trendArea = Path().apply {
                moveTo(x(0), bottom)
                lineTo(x(0), y(points.first().value))
                points.indices.drop(1).forEach { lineTo(x(it), y(points[it].value)) }
                lineTo(x(points.lastIndex), bottom)
                close()
            }
            drawPath(
                path = trendArea,
                brush = Brush.verticalGradient(
                    listOf(Amber.copy(alpha = 0.15f), Color.Transparent),
                    startY = top,
                    endY = bottom,
                ),
            )
            drawPath(
                path = trendPath,
                color = Amber,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            points.forEachIndexed { index, point ->
                drawCircle(
                    color = if (index == points.lastIndex) Ice else Amber,
                    radius = if (index == points.lastIndex) 5.dp.toPx() else 3.dp.toPx(),
                    center = Offset(x(index), y(point.value)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Text(
                    text = point.dayLabel.take(3),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (point == points.last()) Ice else Quiet,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SignalQualityCard(state: DashboardUiState) {
    val qualityPassed = state.signalQuality >= 80
    val qualityColor = if (qualityPassed) Mint else Amber
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Input signal quality")
                Text(
                    text = if (qualityPassed) "Qualified measurement" else "Below quality gate",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ice,
                )
                Text(
                    text = "${state.connectedDevice} · ${state.coverageHours} h coverage",
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Quiet,
                )
            }
            Text(
                text = "${state.signalQuality}%",
                style = MaterialTheme.typography.headlineLarge,
                color = qualityColor,
            )
        }
        Spacer(Modifier.height(18.dp))
        state.qualitySignals.forEach { signal ->
            QualityRow(signal, qualityColor)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = "Low-contact or motion-contaminated windows are rejected; uncertain data widens the forecast range instead of creating an alert.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
    }
}

@Composable
private fun InterpretationTraceCard(state: DashboardUiState) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Story to source")
                Text(
                    text = "Trace this interpretation",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ice,
                )
            }
            StatusPill("EXPLAINABLE", Blue)
        }
        Text(
            text = "Start with the plain-language brief, then follow every layer down to the generated fixture.",
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TraceStep("01", "Today brief", state.status.name.lowercase().replaceFirstChar { it.uppercase() })
            TraceConnector()
            TraceStep("02", "Qualified metrics", state.qualifiedSignalCount.toString())
            TraceConnector()
            TraceStep("03", "Source windows", state.evidence.size.toString())
            TraceConnector()
            TraceStep("04", "Data origin", if (state.isSimulated) "Fixture" else "Pilot")
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            color = Blue.copy(alpha = 0.07f),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Blue.copy(alpha = 0.14f)),
        ) {
            Text(
                text = if (state.isSimulated) {
                    "SIMULATOR TRACE · generated, versioned inputs · no personal health data"
                } else {
                    "RESEARCH PILOT TRACE · not diagnosis or attended monitoring"
                },
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Blue,
                letterSpacing = 0.45.sp,
            )
        }
    }
}

@Composable
private fun TraceStep(index: String, title: String, detail: String) {
    Surface(
        modifier = Modifier.width(126.dp),
        color = SurfaceLifted.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF315356)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelMedium,
                color = Mint,
            )
            Text(
                text = title,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Ice,
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TraceConnector() {
    Text(
        text = "→",
        modifier = Modifier.padding(horizontal = 7.dp),
        style = MaterialTheme.typography.titleLarge,
        color = Quiet.copy(alpha = 0.65f),
    )
}

@Composable
private fun DataPlaneCard(dataPlane: DataPlaneUiModel) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Pilot data plane")
                Text(
                    text = dataPlane.activeMode,
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = Ice,
                )
            }
            StatusPill(dataPlane.pilotGateLabel, Amber)
        }
        Spacer(Modifier.height(16.dp))
        DataPlaneRow("Watch receipt", dataPlane.receiptState)
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 11.dp),
            color = Color(0xFF29464A),
        )
        DataPlaneRow("Forecast audit", dataPlane.forecastAuditState)
        Text(
            text = dataPlane.integrityDetail,
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
    }
}

@Composable
private fun DataPlaneRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Quiet,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = Ice,
        )
    }
}

@Composable
private fun QualityRow(signal: QualitySignalUiModel, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(116.dp)) {
            Text(signal.label, style = MaterialTheme.typography.labelLarge, color = Ice)
            Text(
                signal.note,
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        ProgressTrack(
            progress = signal.score / 100f,
            modifier = Modifier.weight(1f),
            color = color,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = signal.score.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun FiveSecondSummaryRow(summary: FiveSecondSummaryUiModel) {
    val stacked = todaySummaryLayout() == TodaySummaryLayout.STACKED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .semantics { contentDescription = "Five-second summary" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (stacked) {
            FiveSecondCell("What changed", summary.whatChanged, Modifier.fillMaxWidth())
            FiveSecondCell("Evidence", summary.evidence, Modifier.fillMaxWidth())
            FiveSecondCell("Next step", summary.nextStep, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FiveSecondCell(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0C2023),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Violet,
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Ice,
            )
        }
    }
}

@Composable
private fun ConflictDeskCard(items: List<ConflictDeskItemUiModel>) {
    DashboardCard {
        SectionLabel("Conflict desk")
        Text(
            text = "Rejected source revisions stay visible",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Text(
            text = "Simulator-only. Equal sequence with a different native version fails closed instead of overwriting history.",
            modifier = Modifier.padding(bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
        items.forEach { item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                color = Color(0xFF0C2023),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.28f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = Ice)
                    Text(
                        item.detail,
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate,
                    )
                    Text(
                        item.action,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureInspectorCard(rows: List<FeatureInspectorRowUiModel>) {
    DashboardCard {
        SectionLabel("Feature inspector")
        Text(
            text = "Cutoff-sealed snapshot contents",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Text(
            text = "Values, windows, quality and provenance are bound into the training-case receipt. This is a simulator fixture, not personal health data.",
            modifier = Modifier.padding(bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
        rows.forEach { row ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                color = Color(0xFF0C2023),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.24f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(row.featureId, style = MaterialTheme.typography.titleMedium, color = Ice)
                    Text(
                        "${row.version} · ${row.windowLabel} · quality ${row.quality}/100",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate,
                    )
                    Text(
                        "canonicalSha256 ${row.snapshotSha256Prefix}…",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Violet,
                    )
                    Text(
                        row.provenanceLabel,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Quiet,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForecastAuditTimeline(events: List<ForecastAuditEventUiModel>) {
    DashboardCard {
        SectionLabel("Forecast audit")
        Text(
            text = "Committed hidden → context → reveal → outcome",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        events.forEachIndexed { index, event ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(10.dp)
                            .background(Violet, CircleShape),
                    )
                    if (index != events.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(58.dp)
                                .background(Color(0xFF284448)),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = if (index != events.lastIndex) 12.dp else 0.dp),
                ) {
                    Text(event.timeLabel.uppercase(), style = MaterialTheme.typography.labelMedium, color = Violet)
                    Text(event.state, modifier = Modifier.padding(top = 3.dp), style = MaterialTheme.typography.titleMedium, color = Ice)
                    Text(event.detail, modifier = Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, color = Slate)
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(timeline: List<TimelineItemUiModel>) {
    DashboardCard {
        SectionLabel("Traceable timeline")
        Text(
            text = "What changed, and what informed it",
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        timeline.forEachIndexed { index, item ->
            TimelineRow(item, showLine = index != timeline.lastIndex)
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItemUiModel, showLine: Boolean) {
    val color = when (item.kind) {
        TimelineKind.INSIGHT -> Amber
        TimelineKind.MEASUREMENT -> Mint
        TimelineKind.CONTEXT -> Blue
        TimelineKind.SYSTEM -> Quiet
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            if (showLine) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(58.dp)
                        .background(Color(0xFF284448)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (showLine) 12.dp else 0.dp),
        ) {
            Text(
                text = item.timeLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ice,
                )
                Text(
                    text = item.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(color.copy(alpha = 0.10f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = item.detail,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Quiet,
            )
        }
    }
}

@Composable
private fun SafetyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF294548)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Research pattern guidance only",
                style = MaterialTheme.typography.labelLarge,
                color = Ice,
            )
            Text(
                text = "This app cannot diagnose or rule out a medical condition. No clinician or emergency service is automatically notified, and this app is not attended. How you feel matters more than the score. ${ProductBrand.NAME} may miss important changes; if you feel seriously unwell, use local emergency services or contact a clinician and do not wait for the app.",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Quiet,
            )
        }
    }
}

@Composable
private fun SimulationLab(
    selected: SimulationScenario,
    onSelect: (SimulationScenario) -> Unit,
) {
    DashboardCard {
        SectionLabel("Simulator lab")
        Text(
            text = "Test every safety state",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Ice,
        )
        Text(
            text = "These deterministic fixtures exercise UI behavior only. They are not measurements or medical examples.",
            modifier = Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimulationScenario.entries.forEach { scenario ->
                val active = scenario == selected
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(scenario) }
                        .semantics {
                            contentDescription = "Load ${scenario.displayName} simulator state"
                            stateDescription = if (active) "Selected" else "Not selected"
                        },
                    color = if (active) Mint.copy(alpha = 0.16f) else SurfaceLifted,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (active) Mint.copy(alpha = 0.55f) else Color(0xFF35565A),
                    ),
                ) {
                    Text(
                        text = scenario.displayName,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) Mint else Slate,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardBottomBar(
    pane: DashboardPane,
    onSelectPane: (DashboardPane) -> Unit,
) {
    Surface(
        color = SurfaceDeep.copy(alpha = 0.98f),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PaneTab(
                title = "Today",
                selected = pane == DashboardPane.TODAY,
                onClick = { onSelectPane(DashboardPane.TODAY) },
                modifier = Modifier.weight(1f),
            )
            PaneTab(
                title = "Evidence",
                selected = pane == DashboardPane.EVIDENCE,
                onClick = { onSelectPane(DashboardPane.EVIDENCE) },
                modifier = Modifier.weight(1f),
            )
            PaneTab(
                title = "Lab",
                selected = pane == DashboardPane.LAB,
                onClick = { onSelectPane(DashboardPane.LAB) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PaneTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = title
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        color = if (selected) Mint.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Mint.copy(alpha = 0.40f) else Color(0xFF2A484B),
        ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = Modifier.padding(vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MintSoft else Slate,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickLogDialog(
    onDismiss: () -> Unit,
    onSave: (QuickLogDraft) -> Unit,
    onReportHumanConcern: () -> Unit,
) {
    var energy by remember { mutableStateOf<Int?>(null) }
    var fatigue by remember { mutableStateOf<Int?>(null) }
    var stress by remember { mutableStateOf<Int?>(null) }
    var symptoms by remember { mutableStateOf<Int?>(null) }
    var sleep by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLifted,
        shape = RoundedCornerShape(26.dp),
        title = {
            Column {
                SectionLabel("Daily context")
                Text(
                    text = "How are you right now?",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ice,
                )
                Text(
                    text = "This is pre-reveal context, not the later forecast outcome. It does not change medical treatment.",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedButton(
                    onClick = onReportHumanConcern,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            stateDescription = "Reports concern immediately and closes this dialog"
                            liveRegion = LiveRegionMode.Assertive
                        },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose),
                ) {
                    Text("I feel unwell or concerned — hold wearable output")
                }
                Text(
                    text = "This acts immediately. Nobody is notified; use your care plan or seek help independently of the watch.",
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate,
                )
                LogSlider("Energy", energy, "No usable energy", "Usual or best energy") { energy = it }
                LogSlider("Fatigue", fatigue, "No fatigue", "Worst imaginable fatigue") { fatigue = it }
                LogSlider("Stress", stress, "No perceived stress", "Worst imaginable stress") { stress = it }
                LogSlider("GI symptoms", symptoms, "No GI symptom burden", "Worst imaginable GI burden") { symptoms = it }
                LogSlider("Sleep quality", sleep, "Worst imaginable sleep", "Usual or best sleep") { sleep = it }
                TextField(
                    value = note,
                    onValueChange = { note = it.take(140) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    placeholder = { Text("Optional note") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceDeep,
                        unfocusedContainerColor = SurfaceDeep,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Ice,
                        unfocusedTextColor = Ice,
                        focusedPlaceholderColor = Quiet,
                        unfocusedPlaceholderColor = Quiet,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        QuickLogDraft(
                            energy = energy,
                            fatigue = fatigue,
                            stress = stress,
                            gastrointestinalSymptoms = symptoms,
                            sleepQuality = sleep,
                            note = note,
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
            ) {
                Text("Save check-in")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Slate)
            }
        },
    )
}

@Composable
private fun LogSlider(
    label: String,
    value: Int?,
    minimumAnchor: String,
    maximumAnchor: String,
    onValueChange: (Int?) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = Ice,
            )
            Text(
                text = value?.let { "$it / 10" } ?: "Not answered",
                style = MaterialTheme.typography.labelLarge,
                color = if (value == null) Quiet else Mint,
            )
        }
        if (value == null) {
            Text(
                text = "Choose an explicit value",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Quiet,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (0..10).forEach { score ->
                    OutlinedButton(
                        onClick = { onValueChange(score) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "$label, $score out of 10"
                        },
                    ) {
                        Text(score.toString())
                    }
                }
            }
        } else {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt()) },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = Mint,
                    activeTrackColor = Mint,
                    inactiveTrackColor = Color(0xFF29484B),
                ),
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = "$value out of 10; zero means $minimumAnchor; ten means $maximumAnchor"
                },
            )
            TextButton(onClick = { onValueChange(null) }) {
                Text("Clear $label response", color = Slate)
            }
        }
        Text(
            text = "0 = $minimumAnchor · 10 = $maximumAnchor",
            style = MaterialTheme.typography.bodySmall,
            color = Quiet,
        )
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244346)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(SurfaceDeep, Color(0xFF0A2022)),
                        start = Offset.Zero,
                        end = Offset(800f, 900f),
                    ),
                )
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Mint,
        letterSpacing = 1.1.sp,
    )
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = SurfaceDeep,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Ice,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Quiet,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier.fillMaxWidth(),
    color: Color,
) {
    Box(
        modifier = modifier
            .height(7.dp)
            .clip(CircleShape)
            .background(Color(0xFF29474A))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progress.coerceIn(0f, 1f),
                    range = 0f..1f,
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(7.dp)
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun rememberVitalMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Quiet)
    }
}
