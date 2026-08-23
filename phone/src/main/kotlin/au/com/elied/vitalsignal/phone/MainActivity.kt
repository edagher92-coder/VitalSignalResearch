package au.com.elied.vitalsignal.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.elied.vitalsignal.phone.presentation.dashboard.DashboardScreen
import au.com.elied.vitalsignal.phone.presentation.dashboard.DashboardViewModel
import au.com.elied.vitalsignal.phone.ui.theme.VitalSignalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VitalSignalTheme {
                val viewModel: DashboardViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                DashboardScreen(
                    state = state,
                    onToggleExplanation = viewModel::toggleExplanation,
                    onOpenQuickLog = viewModel::openQuickLog,
                    onCloseQuickLog = viewModel::closeQuickLog,
                    onSaveQuickLog = viewModel::saveQuickLog,
                    onReportHumanConcern = viewModel::reportHumanConcern,
                    onResolveHumanConcern = viewModel::resolveHumanConcern,
                    onSavedMessageShown = viewModel::clearSavedMessage,
                    onSelectSimulationScenario = viewModel::selectSimulationScenario,
                )
            }
        }
    }
}
