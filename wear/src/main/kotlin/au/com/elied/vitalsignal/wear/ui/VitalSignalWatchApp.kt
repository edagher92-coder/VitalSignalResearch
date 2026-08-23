package au.com.elied.vitalsignal.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.elied.vitalsignal.wear.capture.CapturePhase
import au.com.elied.vitalsignal.wear.capture.CaptureStatus
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.SensorCapability

private val Ink = Color(0xFF031011)
private val Panel = Color(0xFF0A1D1E)
private val Mint = Color(0xFF78EBCB)
private val Aqua = Color(0xFF36CDAA)
private val Ice = Color(0xFFE7FBF5)
private val Quiet = Color(0xFF91AAA7)
private val Amber = Color(0xFFFFCB72)
private val Rose = Color(0xFFFF919E)

@Composable
fun VitalSignalWatchApp(
    status: CaptureStatus,
    capabilities: List<SensorCapability>,
    permissionMessage: String?,
    simulationMode: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val statusMessage = displayStatusMessage(status = status, simulationMode = simulationMode)
    val accent = when (status.phase) {
        CapturePhase.ACTIVE -> Mint
        CapturePhase.STARTING, CapturePhase.STOPPING -> Amber
        CapturePhase.BLOCKED, CapturePhase.ERROR -> Rose
        CapturePhase.IDLE -> Aqua
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF153633), Color(0xFF07191A), Ink),
                    center = Offset(160f, 90f),
                    radius = 520f,
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Mint.copy(alpha = 0.06f),
                radius = size.minDimension * 0.43f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // Leave a curved-edge safe area while retaining vertical
                // scrolling on compact round displays.
                .padding(start = 17.dp, top = 10.dp, end = 17.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PilotBadge(simulationMode)
            Spacer(Modifier.height(6.dp))
            QualityOrb(status = status, accent = accent)
            Spacer(Modifier.height(5.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = "Capture status"
                        stateDescription = captureStateDescription(
                            status = status,
                            simulationMode = simulationMode,
                            statusMessage = statusMessage,
                        )
                        liveRegion = if (
                            status.phase == CapturePhase.BLOCKED || status.phase == CapturePhase.ERROR
                        ) {
                            LiveRegionMode.Assertive
                        } else {
                            LiveRegionMode.Polite
                        }
                        if (status.phase == CapturePhase.BLOCKED || status.phase == CapturePhase.ERROR) {
                            error(statusMessage)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = phaseHeadline(status.phase, simulationMode),
                    style = TextStyle(
                        color = Ice,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = statusMessage,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(
                        color = Quiet,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            if (status.packetCount > 0) {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = if (simulationMode) {
                        "${status.packetCount} SIMULATED PACKETS · MEMORY ONLY"
                    } else {
                        "${status.packetCount} RESEARCH PACKETS RECEIVED"
                    },
                    style = TextStyle(
                        color = Amber,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            if (permissionMessage != null) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = permissionMessage,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Sensor access required. $permissionMessage"
                        stateDescription = "Capture is blocked until access is resolved"
                        liveRegion = LiveRegionMode.Assertive
                        error(permissionMessage)
                    },
                    style = TextStyle(
                        color = Rose,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            CaptureButton(
                running = status.isRunning,
                accent = accent,
                simulationMode = simulationMode,
                onClick = if (status.isRunning) onStop else onStart,
            )
            Spacer(Modifier.height(8.dp))
            SensorStrip(capabilities = capabilities)
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = if (simulationMode) {
                    "SIMULATOR · NO LIVE HEALTH DATA · NO ATTENDED MONITORING · INDEPENDENT"
                } else {
                    "RESEARCH ONLY · NOT MEDICAL ADVICE · NO ATTENDED MONITORING · INDEPENDENT"
                },
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = Quiet.copy(alpha = 0.88f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    letterSpacing = 0.45.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

private fun phaseHeadline(phase: CapturePhase, simulationMode: Boolean): String = when (phase) {
    CapturePhase.ACTIVE -> if (simulationMode) {
        "Simulator capture in progress"
    } else {
        "Research capture in progress"
    }
    CapturePhase.STARTING -> if (simulationMode) "Preparing simulator" else "Preparing research sensors"
    CapturePhase.STOPPING -> if (simulationMode) "Stopping simulator safely" else "Closing research sensors safely"
    CapturePhase.BLOCKED -> if (simulationMode) "Simulator unavailable" else "Capture unavailable"
    CapturePhase.ERROR -> if (simulationMode) "Simulator needs attention" else "Capture needs attention"
    CapturePhase.IDLE -> if (simulationMode) "Simulator ready" else "Research capture ready"
}

/**
 * The capture controller is shared by real and simulator adapters. Protect the
 * real-mode UI from accidentally repeating fixture or memory-only wording.
 */
private fun displayStatusMessage(status: CaptureStatus, simulationMode: Boolean): String {
    if (simulationMode) return status.message
    val simulatorWording = status.message.contains("simulat", ignoreCase = true) ||
        status.message.contains("fixture", ignoreCase = true) ||
        status.message.contains("memory only", ignoreCase = true) ||
        status.message.contains("simulator memory", ignoreCase = true)
    if (!simulatorWording) return status.message
    return when (status.phase) {
        CapturePhase.STARTING -> "Checking configured research sensors"
        CapturePhase.ACTIVE -> "Collecting configured research sensor packets"
        CapturePhase.STOPPING -> "Closing configured research sensors"
        CapturePhase.BLOCKED -> "Configured research sensors are unavailable"
        CapturePhase.ERROR -> "Research sensor capture needs attention"
        CapturePhase.IDLE -> "Research capture is idle"
    }
}

private fun captureStateDescription(
    status: CaptureStatus,
    simulationMode: Boolean,
    statusMessage: String,
): String = "${phaseHeadline(status.phase, simulationMode)}. $statusMessage"

@Composable
private fun PilotBadge(simulationMode: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Mint.copy(alpha = 0.09f))
            .border(1.dp, Mint.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (simulationMode) Amber else Mint),
        )
        Spacer(Modifier.size(6.dp))
        BasicText(
            text = if (simulationMode) {
                "SIMULATION · NO LIVE DATA"
            } else {
                "RESEARCH PILOT · NOT A MEDICAL MONITOR"
            },
            style = TextStyle(
                color = if (simulationMode) Amber else Mint,
                fontSize = 10.sp,
                letterSpacing = 0.55.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun QualityOrb(
    status: CaptureStatus,
    accent: Color,
) {
    val quality = status.latestQuality?.score
    val progress = quality?.toFloat() ?: when (status.phase) {
        CapturePhase.ACTIVE -> 0.08f
        CapturePhase.STARTING, CapturePhase.STOPPING -> 0.32f
        else -> 0.08f
    }

    Box(
        modifier = Modifier
            .size(112.dp)
            .clearAndSetSemantics {
                contentDescription = "Research signal quality"
                stateDescription = qualityStateDescription(status)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.45f), accent)),
                startAngle = 135f,
                sweepAngle = 270f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                ),
                radius = size.minDimension * 0.38f,
            )
            val trace = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.48f)
                lineTo(size.width * 0.36f, size.height * 0.48f)
                lineTo(size.width * 0.44f, size.height * 0.38f)
                lineTo(size.width * 0.53f, size.height * 0.59f)
                lineTo(size.width * 0.61f, size.height * 0.46f)
                lineTo(size.width * 0.76f, size.height * 0.46f)
            }
            drawPath(
                trace,
                accent.copy(alpha = 0.24f),
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = quality?.let { "${(it * 100).toInt()}" } ?: "—",
                style = TextStyle(
                    color = Ice,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                ),
            )
            BasicText(
                text = when (status.phase) {
                    CapturePhase.ACTIVE -> "QUALITY"
                    CapturePhase.STARTING -> "STARTING"
                    CapturePhase.STOPPING -> "STOPPING"
                    CapturePhase.BLOCKED -> "SETUP"
                    CapturePhase.ERROR -> "CHECK"
                    CapturePhase.IDLE -> "READY"
                },
                style = TextStyle(
                    color = accent,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

private fun qualityStateDescription(status: CaptureStatus): String {
    val quality = status.latestQuality ?: return when (status.phase) {
        CapturePhase.ACTIVE -> "Capture active; quality estimate not available yet"
        CapturePhase.STARTING -> "Capture starting; quality estimate not available yet"
        CapturePhase.STOPPING -> "Capture stopping; quality estimate not available"
        CapturePhase.BLOCKED -> "Capture blocked; no quality estimate"
        CapturePhase.ERROR -> "Capture error; no quality estimate"
        CapturePhase.IDLE -> "Capture ready; no quality estimate"
    }
    val percentage = (quality.score * 100.0).toInt().coerceIn(0, 100)
    val gate = when {
        quality.interpretationGrade -> "meets the research interpretation-quality gate"
        quality.usable -> "usable for feature estimation only"
        else -> "below the usable research-quality gate"
    }
    return "Signal quality $percentage percent; $gate"
}

@Composable
private fun SensorStrip(capabilities: List<SensorCapability>) {
    val highlights = capabilities.take(3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel.copy(alpha = 0.90f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        highlights.forEach { capability ->
            val available = capability.state == CapabilityState.AVAILABLE
            val availabilityLabel = when (capability.state) {
                CapabilityState.AVAILABLE -> "AVAILABLE"
                CapabilityState.UNSUPPORTED_DEVICE -> "UNSUPPORTED"
                CapabilityState.PERMISSION_REQUIRED -> "NEEDS ACCESS"
                CapabilityState.ADAPTER_NOT_INSTALLED -> "NO ADAPTER"
                CapabilityState.TEMPORARILY_UNAVAILABLE -> "UNAVAILABLE"
            }
            val label = when {
                capability.channel.name.contains("HEART") -> "HR / IBI"
                capability.channel.name.contains("ACCEL") -> "MOTION"
                capability.channel.name.contains("PPG") -> "RAW PPG"
                capability.channel.name.contains("TEMPERATURE") -> "TEMP"
                else -> capability.channel.name.take(7)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = buildString {
                            append(label)
                            append(": ")
                            append(availabilityLabel.lowercase())
                            capability.detail?.takeIf { it.isNotBlank() }?.let {
                                append(". ")
                                append(it)
                            }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (available) Mint else Amber),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = label,
                    style = TextStyle(
                        color = Ice,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                BasicText(
                    text = availabilityLabel,
                    style = TextStyle(
                        color = if (available) Mint else Amber,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CaptureButton(
    running: Boolean,
    accent: Color,
    simulationMode: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (running) {
                    Modifier.background(Color.White.copy(alpha = 0.10f))
                } else {
                    Modifier.background(Brush.horizontalGradient(listOf(Aqua, Mint)))
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = when {
                    running && simulationMode -> "Stop simulator"
                    running -> "Stop research capture"
                    simulationMode -> "Run simulator"
                    else -> "Start research capture"
                }
                stateDescription = if (running) "Capture is running" else "Capture is stopped"
                role = Role.Button
            }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = when {
                running -> "END CAPTURE"
                simulationMode -> "RUN SIMULATOR"
                else -> "START RESEARCH CAPTURE"
            },
            style = TextStyle(
                color = if (running) accent else Ink,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
