package au.com.elied.vitalsignal.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.elied.vitalsignal.wear.capture.ResearchCaptureConfig
import au.com.elied.vitalsignal.wear.capture.ResearchCaptureRuntime
import au.com.elied.vitalsignal.wear.capture.ResearchCaptureService
import au.com.elied.vitalsignal.wear.permissions.WearPermissionPolicy
import au.com.elied.vitalsignal.wear.sensor.CapabilityState
import au.com.elied.vitalsignal.wear.sensor.SensorCapability
import au.com.elied.vitalsignal.wear.sensor.SensorCatalog
import au.com.elied.vitalsignal.wear.ui.VitalSignalWatchApp

class MainActivity : ComponentActivity() {
    private val permissionMessage = mutableStateOf<String?>(null)
    private var pendingCapture: ResearchCaptureConfig? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = WearPermissionPolicy.missingForegroundResearchPermissions(this)
        if (missing.isEmpty()) {
            permissionMessage.value = null
            pendingCapture?.let { ResearchCaptureService.start(this, it) }
            pendingCapture = null
        } else {
            permissionMessage.value = "Sensor permission is required for research capture"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val status by ResearchCaptureRuntime.controller.status.collectAsStateWithLifecycle()
            val capabilities = remember {
                mutableStateOf(
                    SensorCatalog.researchDefaults.map { channel ->
                        SensorCapability(
                            channel = channel,
                            state = CapabilityState.ADAPTER_NOT_INSTALLED,
                            detail = "Samsung adapter pending",
                        )
                    },
                )
            }
            LaunchedEffect(status.phase) {
                capabilities.value = runCatching {
                    ResearchCaptureRuntime.adapters.inspectCapabilities()
                        .filter { it.channel in SensorCatalog.researchDefaults }
                }.getOrDefault(capabilities.value)
            }

            VitalSignalWatchApp(
                status = status,
                capabilities = capabilities.value,
                permissionMessage = permissionMessage.value,
                simulationMode = ResearchCaptureRuntime.isSimulationMode,
                onStart = ::requestStart,
                onStop = { ResearchCaptureService.stop(this) },
            )
        }
    }

    private fun requestStart() {
        val config = ResearchCaptureConfig.newPilotSession()
        if (ResearchCaptureRuntime.isSimulationMode) {
            permissionMessage.value = null
            ResearchCaptureService.start(this, config)
            return
        }
        val missing = WearPermissionPolicy.missingForegroundResearchPermissions(this)
        if (missing.isEmpty()) {
            ResearchCaptureService.start(this, config)
        } else {
            pendingCapture = config
            permissionLauncher.launch(missing)
        }
    }
}
