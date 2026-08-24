package au.com.elied.vitalsignal.phone.presentation.dashboard

/**
 * Person-facing information architecture for the simulator phone UI.
 * Operator/lab surfaces stay off Today so the daily story remains glanceable.
 */
enum class DashboardPane {
    TODAY,
    EVIDENCE,
    LAB,
}

enum class DashboardSurface {
    HERO,
    FORECAST,
    ASSISTANT,
    TREND,
    ACTIVITY,
    QUALITY,
    TRACE,
    TIMELINE,
    DATA_PLANE,
    CONFLICTS,
    INSPECTOR,
    FORECAST_AUDIT,
    SIMULATION_LAB,
}

fun paneFor(surface: DashboardSurface): DashboardPane = when (surface) {
    DashboardSurface.HERO,
    DashboardSurface.FORECAST,
    -> DashboardPane.TODAY

    DashboardSurface.ASSISTANT,
    DashboardSurface.TREND,
    DashboardSurface.ACTIVITY,
    DashboardSurface.QUALITY,
    DashboardSurface.TRACE,
    DashboardSurface.TIMELINE,
    -> DashboardPane.EVIDENCE

    DashboardSurface.DATA_PLANE,
    DashboardSurface.CONFLICTS,
    DashboardSurface.INSPECTOR,
    DashboardSurface.FORECAST_AUDIT,
    DashboardSurface.SIMULATION_LAB,
    -> DashboardPane.LAB
}

fun isWithheldDuringConcernHold(surface: DashboardSurface): Boolean =
    surface != DashboardSurface.HERO

fun surfacesFor(pane: DashboardPane): Set<DashboardSurface> =
    DashboardSurface.entries.filter { paneFor(it) == pane }.toSet()
