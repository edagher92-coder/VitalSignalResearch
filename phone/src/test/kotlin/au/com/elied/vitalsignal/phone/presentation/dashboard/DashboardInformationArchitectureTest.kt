package au.com.elied.vitalsignal.phone.presentation.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardInformationArchitectureTest {
    @Test
    fun todayPaneContainsOnlyTheDailyStoryAndForecast() {
        val today = surfacesFor(DashboardPane.TODAY)
        assertEquals(
            setOf(DashboardSurface.HERO, DashboardSurface.FORECAST),
            today,
        )
        assertFalse(today.contains(DashboardSurface.CONFLICTS))
        assertFalse(today.contains(DashboardSurface.INSPECTOR))
        assertFalse(today.contains(DashboardSurface.SIMULATION_LAB))
        assertFalse(today.contains(DashboardSurface.DATA_PLANE))
    }

    @Test
    fun evidencePaneKeepsTraceabilityWithoutOperatorDesks() {
        val evidence = surfacesFor(DashboardPane.EVIDENCE)
        assertTrue(evidence.contains(DashboardSurface.ASSISTANT))
        assertTrue(evidence.contains(DashboardSurface.TRACE))
        assertTrue(evidence.contains(DashboardSurface.ACTIVITY))
        assertFalse(evidence.contains(DashboardSurface.CONFLICTS))
        assertFalse(evidence.contains(DashboardSurface.FORECAST_AUDIT))
    }

    @Test
    fun labPaneKeepsOperatorSurfacesOffTheDailyStory() {
        val lab = surfacesFor(DashboardPane.LAB)
        assertTrue(lab.contains(DashboardSurface.CONFLICTS))
        assertTrue(lab.contains(DashboardSurface.INSPECTOR))
        assertTrue(lab.contains(DashboardSurface.FORECAST_AUDIT))
        assertTrue(lab.contains(DashboardSurface.SIMULATION_LAB))
        assertFalse(lab.contains(DashboardSurface.FORECAST))
        assertFalse(lab.contains(DashboardSurface.HERO))
    }

    @Test
    fun concernHoldWithholdsEverySurfaceExceptTheHeroStory() {
        DashboardSurface.entries.forEach { surface ->
            if (surface == DashboardSurface.HERO) {
                assertFalse(isWithheldDuringConcernHold(surface))
            } else {
                assertTrue(isWithheldDuringConcernHold(surface))
            }
        }
    }

    @Test
    fun everySurfaceHasExactlyOnePane() {
        DashboardSurface.entries.forEach { surface ->
            val pane = paneFor(surface)
            assertEquals(setOf(surface), surfacesFor(pane).intersect(setOf(surface)))
        }
        val assigned = DashboardPane.entries.flatMap { surfacesFor(it) }.toSet()
        assertEquals(DashboardSurface.entries.toSet(), assigned)
    }
}
