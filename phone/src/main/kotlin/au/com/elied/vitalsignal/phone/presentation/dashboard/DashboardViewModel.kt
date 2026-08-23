package au.com.elied.vitalsignal.phone.presentation.dashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class DashboardViewModel(
    private val repository: DashboardRepository = DemoDashboardRepository(),
) : ViewModel() {
    val state: StateFlow<DashboardUiState> = repository.state

    fun toggleExplanation() {
        repository.setExplanationExpanded(!state.value.explanationExpanded)
    }

    fun openQuickLog() = repository.setQuickLogOpen(true)
    fun closeQuickLog() = repository.setQuickLogOpen(false)
    fun saveQuickLog(draft: QuickLogDraft) = repository.saveQuickLog(draft)
    fun reportHumanConcern() = repository.reportHumanConcern()
    fun resolveHumanConcern() = repository.resolveHumanConcern()
    fun clearSavedMessage() = repository.clearSavedMessage()
    fun selectSimulationScenario(scenario: SimulationScenario) =
        repository.setSimulationScenario(scenario)
}
