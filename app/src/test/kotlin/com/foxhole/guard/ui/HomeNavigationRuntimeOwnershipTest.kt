package com.foxhole.guard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the ownership boundary between flat CLI navigation and process/runtime reconciliation.
 *
 * Switching a dock tab disposes and recreates the Home content, but it is not an Android lifecycle
 * transition. It must therefore neither create another [HomeViewModel] nor dispatch runtime repair.
 * The activity RESUMED boundary remains the single owner of foreground reconciliation, while the
 * terminal and its single-consumer banner collector live above the tab switch.
 */
class HomeNavigationRuntimeOwnershipTest {
    private val activitySource = source("ui/cli/CliMainActivity.kt")
    private val cliAppSource = source("ui/cli/CliApp.kt")
    private val uiActionsSource = source("ui/HomeViewModelUiActions.kt")
    private val supervisorsSource = source("ui/HomeViewModelSupervisorsSupport.kt")

    @Test
    fun `the activity owns one home view model for every dock screen`() {
        assertEquals(1, activitySource.occurrencesOf("HomeViewModel by viewModels"))
        assertEquals(1, activitySource.occurrencesOf("CliApp(viewModel = homeViewModel)"))
        assertFalse(cliAppSource.contains("HomeViewModel("))
        assertFalse(cliAppSource.contains("by viewModels"))
        assertFalse(cliAppSource.contains("hiltViewModel"))
    }

    @Test
    fun `home tab re-entry is visibility only and cannot reconcile or dispatch runtime`() {
        val routeVisibility = cliAppSource.functionBody("private fun CliRouteVisibilityEffect(")
        val dashboardVisibility =
            uiActionsSource.functionBody("internal fun HomeViewModel.onDashboardUiVisibilityChanged(")
        val forbiddenRuntimeOperations =
            listOf(
                "onAppForegrounded",
                "reconcileActiveVpnNetworkIfNeeded",
                "syncLocalGuard",
                "startForegroundService",
                "reload(",
                "reconnect(",
                "toggleConnection",
            )

        forbiddenRuntimeOperations.forEach { operation ->
            assertFalse("tab visibility must not call $operation", routeVisibility.contains(operation))
            assertFalse("dashboard visibility must not call $operation", dashboardVisibility.contains(operation))
        }
        assertTrue(routeVisibility.contains("onDashboardUiVisibilityChanged(screen == CliScreen.HOME)"))
    }

    @Test
    fun `foreground reconciliation belongs only to the resumed activity lifecycle`() {
        val resumedCollector =
            activitySource.substringAfter("repeatOnLifecycle(Lifecycle.State.RESUMED)")
                .substringBefore("setContent {")
        val foregroundBody =
            supervisorsSource.functionBody("internal fun HomeViewModel.onAppForegroundedInternal()")

        assertEquals(1, activitySource.occurrencesOf("homeViewModel.onAppForegrounded()"))
        assertTrue(resumedCollector.contains("homeViewModel.onAppForegrounded()"))
        assertEquals(1, supervisorsSource.occurrencesOf("reconcileActiveVpnNetworkIfNeeded()"))
        assertTrue(foregroundBody.contains("reconcileActiveVpnNetworkIfNeeded()"))
        assertFalse(cliAppSource.contains("onAppForegrounded"))
        assertFalse(cliAppSource.contains("reconcileActiveVpnNetworkIfNeeded"))
    }

    @Test
    fun `terminal state and its single consumer stay above the tab content switch`() {
        val terminalOwner = cliAppSource.indexOf("val terminal = rememberCliTerminalState")
        val tabSwitch = cliAppSource.indexOf("AnimatedContent(")
        val tabContent = cliAppSource.indexOf("private fun CliTabContent(")

        assertTrue(terminalOwner > 0)
        assertTrue(tabSwitch > terminalOwner)
        assertTrue(tabContent > tabSwitch)
        assertEquals(1, cliAppSource.occurrencesOf("rememberCliTerminalState("))
        assertEquals(1, cliAppSource.occurrencesOf("snackbars.stream.collect"))
        assertFalse(cliAppSource.substring(tabContent).contains("rememberCliTerminalState("))
    }

    private fun source(relativePath: String): String =
        repoRoot()
            .resolve("app/src/main/kotlin/com/foxhole/guard/$relativePath")
            .also { file -> check(file.isFile) { "Missing source: $file" } }
            .readText()

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
            .first { directory -> directory.resolve("settings.gradle.kts").isFile }

    private fun String.functionBody(signature: String): String {
        val signatureStart = indexOf(signature)
        check(signatureStart >= 0) { "Missing function: $signature" }
        val bodyStart = indexOf('{', startIndex = signatureStart)
        check(bodyStart >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(bodyStart + 1, index)
                }
            }
        }
        error("Unclosed function body: $signature")
    }

    private fun String.occurrencesOf(token: String): Int = windowed(token.length).count { it == token }
}
