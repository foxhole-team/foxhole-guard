package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDashboardVisibilityContractTest {
    @Test
    fun `repeated dashboard navigation entry is read only while real foreground still reconciles`() {
        val visibilitySource = projectSource("HomeViewModelUiActions.kt")
            .substringAfter("internal fun HomeViewModel.onDashboardUiVisibilityChanged")
            .substringBefore("internal fun HomeViewModel.onProfilesUiVisibilityChanged")
        val foregroundSource = projectSource("HomeViewModelSupervisorsSupport.kt")
            .substringAfter("internal fun HomeViewModel.onAppForegroundedInternal")
            .substringBefore("private fun HomeViewModel.refreshIpInfoOnForegroundIfNeeded")

        // CliRouteVisibilityEffect emits false -> true on every dock return. Neither the first
        // entry nor any repeat may turn that presentation event into a runtime command.
        repeat(3) {
            assertFalse(visibilitySource.contains("syncLocalGuardWithPermissionRequest"))
            assertFalse(visibilitySource.contains("connectionController.connect"))
            assertFalse(visibilitySource.contains("connectionController.reload"))
            assertFalse(visibilitySource.contains("onToggleConnection"))
        }
        assertTrue(visibilitySource.contains("scheduleForegroundDashboardRefreshIfStale"))

        // Activity RESUMED remains the lifecycle-owned repair path, so real foreground/network
        // recovery did not get disabled merely to make in-app navigation read-only.
        assertTrue(foregroundSource.contains("reconcileActiveVpnNetworkIfNeeded"))
        assertTrue(foregroundSource.contains("syncLocalGuardWithPermissionRequest"))
    }

    private fun projectSource(name: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui", name),
            File("app/src/main/kotlin/com/foxhole/guard/ui", name),
            File("../app/src/main/kotlin/com/foxhole/guard/ui", name),
        ).first(File::isFile).readText()
}
