package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeNavigationPresentationTest {
    @Test
    fun `detail transition uses subtle material-style offset instead of full page slide`() {
        assertEquals(151, detailTransitionOffsetPx(1080))
        assertEquals(1, detailTransitionOffsetPx(1))
    }

    @Test
    fun `settings detail gate rejects same route`() {
        val gate = SettingsDetailNavigationGate(clock = { 1_000L })

        val decision =
            gate.tryAccept(
                currentRoute = "settings/dns",
                targetRoute = "settings/dns",
            )

        assertEquals(
            SettingsDetailNavigationDecision.Rejected(1_000L, "same_route"),
            decision,
        )
    }

    @Test
    fun `settings detail gate suppresses duplicate rapid taps`() {
        var nowMs = 1_000L
        val gate = SettingsDetailNavigationGate(clock = { nowMs })

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(1_000L),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )

        nowMs += SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS - 1

        assertEquals(
            SettingsDetailNavigationDecision.Rejected(nowMs, "duplicate_tap"),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )
    }

    @Test
    fun `settings detail gate accepts after suppression window`() {
        var nowMs = 1_000L
        val gate = SettingsDetailNavigationGate(clock = { nowMs })

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(1_000L),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )

        nowMs += SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(nowMs),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )
    }

    @Test
    fun `root navigation returns to dashboard by popping existing home destination`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val navigateToSectionBlock =
            source.substringAfter("private fun NavHostController.navigateToSection(")
                .substringBefore("private const val SECTION_SWIPE_THRESHOLD_FRACTION")

        assertTrue(navigateToSectionBlock.contains("section.rootRoute == AppRoute.HOME"))
        assertTrue(navigateToSectionBlock.contains("popBackStack(AppRoute.HOME, inclusive = false)"))
    }

    @Test
    fun `dashboard settings root swap uses system detail forward and back transitions`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val homeRootBlock =
            source.substringAfter("composable(AppRoute.HOME) {")
                .substringBefore("composable(AppRoute.PROFILES)")

        assertTrue(homeRootBlock.contains("AnimatedContent("))
        assertTrue(homeRootBlock.contains("targetState = rootSection"))
        assertTrue(homeRootBlock.contains("label = \"root-section-transition\""))
        assertTrue(homeRootBlock.contains("targetState.ordinal > initialState.ordinal"))
        assertTrue(homeRootBlock.contains("detailForwardEnter() togetherWith detailForwardExit()"))
        assertTrue(homeRootBlock.contains("detailBackEnter() togetherWith detailBackExit()"))
        assertFalse(homeRootBlock.contains("slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth"))
    }

    @Test
    fun `bottom dock container stays opaque in chrome and theme palettes`() {
        val chromeSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
            ).first { file -> file.isFile }.readText()
        val themeSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/theme/Theme.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/theme/Theme.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/theme/Theme.kt"),
            ).first { file -> file.isFile }.readText()
        val appSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(chromeSource.contains("BOTTOM_DOCK_CONTAINER_DARK_ALPHA = 1f"))
        assertTrue(chromeSource.contains("BOTTOM_DOCK_CONTAINER_LIGHT_ALPHA = 1f"))
        assertTrue(chromeSource.contains("FoxholeElevationRole.Dock -> 12.dp"))
        assertTrue(chromeSource.contains("internal fun foxholeBottomDockBorderColor()"))
        assertTrue(chromeSource.contains("internal fun foxholeBottomDockElevation()"))
        assertTrue(chromeSource.contains("role = FoxholeElevationRole.Dock"))
        assertFalse(chromeSource.contains("containerColor.alpha * 0.96f"))
        assertTrue(appSource.contains("val borderColor = foxholeBottomDockBorderColor().toArgb()"))
        assertTrue(appSource.contains("val dockElevationPx = with(density) { foxholeBottomDockElevation().toPx() }"))
        assertTrue(appSource.contains("elevation = dockElevationPx"))
        assertTrue(appSource.contains("borderColor = foxholeBottomDockBorderColor()"))
        assertTrue(themeSource.contains("bottomBarContainerColor = FoxholeDarkSurface,"))
        assertTrue(themeSource.contains("bottomBarContainerColor = colorScheme.surface,"))
        assertTrue(themeSource.contains("bottomBarContainerColor = colorScheme.surfaceContainerHigh,"))
    }
}
