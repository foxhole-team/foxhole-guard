package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxholeNavigationPresentationTest {
    @Test
    fun `detail transitions use FoxholeMotionTokens fadeIn slide approach`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()

        // Detail transitions use fadeIn+slide with FoxholeMotionTokens
        assertTrue(source.contains("private fun detailForwardEnter()"))
        assertTrue(source.contains("private fun detailBackExit()"))
        assertTrue(source.contains("slideInHorizontally"))
        assertTrue(source.contains("slideOutHorizontally"))
        assertTrue(source.contains("fadeIn("))
        assertTrue(source.contains("fadeOut("))
        assertTrue(source.contains("detailTransitionOffsetPx"))
        assertTrue(source.contains("detailSecondaryOffsetPx"))
        // No plain slide-only helpers or instant-None root transitions
        assertFalse(source.contains("private fun detailSlideIn()"))
        assertFalse(source.contains("private fun detailSlideOut()"))
        assertFalse(source.contains("predictivePopTransitionSpec"))
    }

    @Test
    fun `navigation transitions stay close to platform defaults`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()

        // Detail transitions use FoxholeMotionTokens; no raw pixel constants
        assertFalse(source.contains("NAV_SLIDE_MS"))
        assertTrue(source.contains("private fun rootEnter()"))
        assertTrue(source.contains("private fun rootExit()"))
        assertTrue(source.contains("DETAIL_ENTER_TRANSITION_MS"))
        assertTrue(source.contains("DETAIL_EXIT_TRANSITION_MS"))
        assertTrue(source.contains("ROOT_TRANSITION_MS"))
        assertFalse(source.contains("DETAIL_TRANSITION_OFFSET_FRACTION = 0.14f"))
        assertTrue(source.contains("DETAIL_TRANSITION_OFFSET_FRACTION = FoxholeMotionTokens.NavigationSlideFraction"))
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
                .substringBefore("private fun requestQuickSettingsTile(")

        assertTrue(navigateToSectionBlock.contains("section.rootRoute == AppRoute.HOME"))
        assertTrue(navigateToSectionBlock.contains("popBackStack(AppRoute.HOME, inclusive = false)"))
    }

    @Test
    fun `dashboard and settings roots are distinct top level destinations`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val homeRootBlock =
            source.substringAfter("composable(AppRoute.HOME) {")
                .substringBefore("composable(AppRoute.PROFILES)")
        val settingsRootBlock =
            source.substringAfter("composable(AppRoute.SETTINGS) {")
                .substringBefore("composable(AppRoute.SMART_START)")

        assertTrue(source.contains("private sealed interface RootGraph"))
        assertTrue(source.contains("data object Dashboard : RootGraph"))
        assertTrue(source.contains("data object Settings : RootGraph"))
        assertTrue(source.contains("startDestination = RootGraph.Dashboard.route"))
        assertTrue(source.contains("route = RootGraph.Dashboard.route"))
        assertTrue(source.contains("startDestination = AppRoute.HOME"))
        assertTrue(source.contains("route = RootGraph.Settings.route"))
        assertTrue(source.contains("startDestination = AppRoute.SETTINGS"))
        assertTrue(source.contains("graphRoute = RootGraph.Dashboard.route"))
        assertTrue(source.contains("graphRoute = RootGraph.Settings.route"))
        assertTrue(source.contains("hierarchy.any { destination ->"))
        assertTrue(source.contains("navigate(section.graphRoute)"))
        assertTrue(homeRootBlock.contains("HomeScreen("))
        assertFalse(homeRootBlock.contains("SettingsHomeScreen("))
        assertTrue(settingsRootBlock.contains("SettingsHomeScreen("))
        assertFalse(settingsRootBlock.contains("\n                    HomeScreen("))
        assertFalse(source.contains("rootSection"))
        assertFalse(homeRootBlock.contains("AnimatedContent("))
        assertFalse(homeRootBlock.contains("label = \"root-section-transition\""))
        assertFalse(homeRootBlock.contains("targetState.ordinal > initialState.ordinal"))
        assertFalse(homeRootBlock.contains("detailForwardEnter() togetherWith detailForwardExit()"))
        assertFalse(homeRootBlock.contains("detailBackEnter() togetherWith detailBackExit()"))
        assertFalse(homeRootBlock.contains("slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth"))
    }

    @Test
    fun `settings detail transitions keep root host static`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val navHostBlock =
            source.substringAfter("NavHost(")
                .substringBefore(") {\n                    navigation(")

        // Root transitions use fade; detail transitions use isSettingsDetailRoute gate
        assertTrue(navHostBlock.contains("initialState.destination.route.isSettingsDetailRoute() &&"))
        assertTrue(navHostBlock.contains("targetState.destination.route.isSettingsDetailRoute()"))
        assertFalse(navHostBlock.contains("EnterTransition.None"))
        assertFalse(navHostBlock.contains("ExitTransition.None"))
        // Detail transition helpers are present
        assertTrue(source.contains("detailForwardExit()"))
        assertTrue(source.contains("detailBackEnter()"))
    }

    @Test
    fun `settings details avoid custom predictive back and edge swipe overrides`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()

        // No custom BackHandler — system predictive back handles root navigation
        assertFalse(source.contains("BackHandler(enabled = currentRoute.isRootRoute() && currentSection == AppSection.SETTINGS)"))
        assertFalse(source.contains("PredictiveBackHandler("))
        assertFalse(source.contains("settingsDetailBackEnabled"))
        assertFalse(source.contains("settingsBackProgress"))
        assertFalse(source.contains("progress.collect { event ->"))
        assertFalse(source.contains("DETAIL_PREDICTIVE_BACK_PROGRESS_OFFSET"))
        assertFalse(source.contains("DETAIL_PREDICTIVE_BACK_ALPHA_RANGE"))
        assertFalse(source.contains("translationX = predictiveBackDirection"))
        assertFalse(source.contains("settingsBackSwipeNavigation"))
        assertFalse(source.contains("DETAIL_BACK_SWIPE_EDGE_WIDTH"))
    }

    @Test
    fun `settings detail navigation exposes macrobenchmark trace section`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val navigationBlock =
            source.substringAfter("private fun NavHostController.navigateToSettingsDetail(")
                .substringBefore("private fun NavHostController.navigateToSection(")

        assertTrue(navigationBlock.contains("traceSettingsNavigationSection(\"Settings/navigation\")"))
        assertTrue(navigationBlock.contains("telemetry.recordNavigateCall(route)"))
        assertTrue(navigationBlock.contains("navigate(route)"))
    }

    @Test
    fun `navigation first frame telemetry is scoped to destination composition`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("NavigationTransitionTelemetryEffect(currentRoute, navigationTransitionTelemetry)"))
        listOf(
            "HOME",
            "TRAFFIC_MAP_DETAIL",
            "PROFILES",
            "SETTINGS",
            "SMART_START",
            "TRAFFIC",
            "DNS",
            "SECURITY",
            "ROUTING_APPS",
            "ROUTING_APPS_PICKER",
            "DNS_APPS_PICKER",
            "ROUTING_SITES",
            "APPLICATION",
            "DIAGNOSTICS",
            "STATISTICS",
        ).forEach { route ->
            assertTrue(
                "Missing destination-local telemetry probe for AppRoute.$route",
                source.contains("NavigationTransitionTelemetryEffect(AppRoute.$route, navigationTransitionTelemetry)"),
            )
        }
    }

    @Test
    fun `navigation telemetry records first frame from next choreographer frame`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/NavigationTransitionTelemetry.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/NavigationTransitionTelemetry.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/NavigationTransitionTelemetry.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("scheduleFirstFrameCallback { recordFirstFrame(routeTo) }"))
        assertTrue(source.contains("Choreographer.getInstance().postFrameCallback"))
        assertTrue(source.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue(source.contains("Handler(Looper.getMainLooper()).post"))
        assertTrue(source.contains("NavigationTransitionTelemetryEffect("))
    }

    @Test
    fun `privacy route settings save configuration without runtime tor control`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val privacyRouteBlock =
            source.substringAfter("composable(AppRoute.PRIVACY_ROUTE) {")
                .substringBefore("composable(AppRoute.ROUTING_APPS)")

        assertTrue(privacyRouteBlock.contains("viewModel::onPrivacyRouteModeConfigured"))
        assertTrue(privacyRouteBlock.contains("viewModel::onPrivacyRouteScopeConfigured"))
        assertTrue(privacyRouteBlock.contains("viewModel::onPrivacyRouteBypassVpnTunnelConfigured"))
        assertTrue(privacyRouteBlock.contains("viewModel::onPrivacyRouteSelectedPackagesConfigured"))
        assertFalse(privacyRouteBlock.contains("= viewModel::onPrivacyRouteModeSelected"))
        assertFalse(privacyRouteBlock.contains("= viewModel::onPrivacyRouteScopeSelected"))
        assertFalse(privacyRouteBlock.contains("= viewModel::onPrivacyRouteBypassVpnTunnelChanged"))
        assertFalse(privacyRouteBlock.contains("= viewModel::onPrivacyRouteSelectedPackagesChanged"))
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

    @Test
    fun `bottom dock items expose tab semantics and clipped material ripple`() {
        val source =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val itemBlock =
            source.substringAfter("private fun RowScope.FoxholeBottomBarItem(")
                .substringBefore("private fun NavDestination.rootAppSection(")

        assertTrue(itemBlock.contains(".clip(MaterialTheme.shapes.medium)"))
        assertTrue(itemBlock.contains(".selectable("))
        assertTrue(itemBlock.contains("selected = selected"))
        assertTrue(itemBlock.contains("role = Role.Tab"))
        assertFalse(itemBlock.contains("indication = null"))
        assertFalse(itemBlock.contains(".clickable("))
    }

    @Test
    fun `chrome defaults to static glass and gates blur overlays behind explicit mode`() {
        val appSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/FoxholeApp.kt"),
            ).first { file -> file.isFile }.readText()
        val chromeSource =
            listOf(
                java.io.File("src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                java.io.File("app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                java.io.File("../app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(chromeSource.contains("enum class ChromeMode"))
        assertTrue(chromeSource.contains("Material"))
        assertTrue(chromeSource.contains("GlassStatic"))
        assertTrue(chromeSource.contains("GlassBlur"))
        assertTrue(chromeSource.contains("FoxholeDefaultChromeMode = ChromeMode.GlassStatic"))
        assertTrue(appSource.contains("chromeMode: ChromeMode = FoxholeDefaultChromeMode"))
        assertTrue(appSource.contains("chromeMode == ChromeMode.GlassBlur"))
        assertTrue(appSource.contains("ChromeMode.Material ->"))
        assertTrue(appSource.contains("NavigationBar("))
        assertTrue(appSource.contains("NavigationBarItem("))
    }
}
