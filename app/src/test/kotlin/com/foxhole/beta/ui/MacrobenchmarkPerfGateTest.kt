package com.foxhole.beta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MacrobenchmarkPerfGateTest {
    @Test
    fun `macrobenchmark suite covers startup scroll bottom nav smart start and app picker`() {
        val benchmarkSource = projectFile(
            "macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/HomeMacrobenchmark.kt",
            "../macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/HomeMacrobenchmark.kt",
        ).readText()

        listOf(
            "fun startup()",
            "fun warmStartup()",
            "fun homeScroll()",
            "fun bottomNavigationRoundTrip()",
            "fun dashboardTrafficMapOpen()",
            "fun dashboardTrafficMapStress()",
            "fun dashboardTrafficMapStartVpnStress()",
            "fun permissionFlow()",
            "fun settingsSmartStartTransition()",
            "fun settingsRoutingAppsPickerSearch()",
            "TraceSectionMetric(",
            "\"HomeScreen first composition\"",
            "\"TrafficMap/loadShapes\"",
            "\"TrafficMap/renderHighlightBitmap\"",
            "\"AppPicker/filter\"",
            "\"AppIcon/load\"",
            "\"Settings/navigation\"",
            "toggleFirstUnlockedAppInPicker()",
            "openImportFilePickerAndReturn()",
            "clickConnectAndReturnFromVpnPermission()",
            "openTrafficMapDetailsAndReturn()",
            "trafficMapStress = true",
            "TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA",
            "cmd power set-mode",
            "cmd uimode night",
            "findTrafficMapDetailsActionAfterScroll()",
            "ensureFoxholeForeground()",
            "scrollDashboardToTrafficMapDetailsAction()",
            "TRAFFIC_MAP_CARD_SCROLL_ATTEMPTS = 8",
            "traffic_map_detail_screen",
        ).forEach { marker ->
            assertTrue(benchmarkSource.contains(marker))
        }
        assertFalse(benchmarkSource.contains("import org.junit.Ignore"))
        assertFalse(benchmarkSource.contains("@Ignore"))
    }

    @Test
    fun `full macrobenchmark gate requires added journeys and p95 frame thresholds`() {
        val thresholdScript = projectFile(
            "scripts/verify-macrobenchmark-thresholds.py",
            "../scripts/verify-macrobenchmark-thresholds.py",
        ).readText()

        listOf(
            "\"bottomNavigationRoundTrip\"",
            "\"warmStartup\"",
            "\"dashboardTrafficMapOpen\"",
            "\"dashboardTrafficMapStress\"",
            "\"dashboardTrafficMapStartVpnStress\"",
            "\"homeScroll\"",
            "\"settingsSmartStartTransition\"",
            "\"settingsRoutingAppsPickerSearch\"",
            "\"permissionFlow\"",
            "STRICT_FRAME_P95_MAX_MS = 16.6",
            "STRICT_FRAME_MAXIMUM_MAX_MS = 700.0",
            "FIRST_FRAME_P50_MAX_MS = 80.0",
            "FIRST_FRAME_P95_MAX_MS = 140.0",
            "FIRST_FRAME_MAXIMUM_MAX_MS = 220.0",
            "NAVIGATION_SKIPPED_FRAME_WINDOW_MS = 750.0",
            "verify_navigation_first_frames",
            "verify_navigation_skipped_frames",
            "navigationSkippedFrameEvents=0",
            "tag == \"Choreographer\"",
            "tag == \"FoxholeNavigation\"",
            "TRANSITION_FRAME_CPU_P95_MAX_MS",
            "TRANSITION_FRAME_OVERRUN_P95_MAX_MS",
            "REQUIRED_TRACE_METRIC_LABELS",
            "HomeScreenFirstCompositionSumMs",
            "TrafficMapRenderHighlightBitmapSumMs",
            "cpuP95",
            "overrunP95",
        ).forEach { marker ->
            assertTrue(thresholdScript.contains(marker))
        }
    }

    @Test
    fun `traffic map benchmark stress state is debug gated`() {
        val viewModelSource = projectFile(
            "app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModel.kt",
            "../app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModel.kt",
        ).readText()
        val stressSource = projectFile(
            "app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapBenchmarkStress.kt",
            "../app/src/main/kotlin/com/foxhole/beta/ui/TrafficMapBenchmarkStress.kt",
        ).readText()

        assertTrue(viewModelSource.contains("fun applyBenchmarkIntent(intent: Intent?)"))
        assertTrue(viewModelSource.contains("if (!BuildConfig.DEBUG)"))
        assertTrue(viewModelSource.contains("benchmarkTrafficMapUiStateMutable"))
        assertTrue(stressSource.contains("MaxLoadMode = \"max_load\""))
        assertTrue(stressSource.contains("TrafficMapRepository.MaxTrafficMapDestinations"))
        assertTrue(stressSource.contains("TrafficMapRepository.MaxRetainedConnectionSamples"))
        assertTrue(stressSource.contains("StressNewCountryCodes"))
    }

    @Test
    fun `ci macrobenchmark script generates baseline profile during full suite`() {
        val script = projectFile(
            "scripts/run-ci-macrobenchmark.sh",
            "../scripts/run-ci-macrobenchmark.sh",
        ).readText()
        val generator = projectFile(
            "macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/FoxholeBaselineProfileGenerator.kt",
            "../macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/FoxholeBaselineProfileGenerator.kt",
        ).readText()

        assertTrue(script.contains("run_baseline_profile_generation"))
        assertTrue(script.contains("run_baseline_profile_generation_when_supported"))
        assertTrue(script.contains("verify_macrobenchmark_tracing_available"))
        assertTrue(script.contains("run_macrobenchmark_with_log_gate"))
        assertTrue(script.contains("analyze-android-perf-logs.py"))
        assertTrue(script.contains("FOXHOLE_MACROBENCHMARK_MAX_SKIPPED_FRAMES"))
        assertTrue(script.contains("MAX_SKIPPED_FRAMES=\"${'$'}{FOXHOLE_MACROBENCHMARK_MAX_SKIPPED_FRAMES:-0}\""))
        assertTrue(script.contains("--max-skipped-frames \"${'$'}MAX_SKIPPED_FRAMES\""))
        assertTrue(script.contains("--fail-on-skipped-frames"))
        assertTrue(script.contains("--fail-on-fatal"))
        assertTrue(script.contains("--fail-on-anr"))
        assertTrue(script.contains("--fail-on-strict-disk"))
        assertTrue(script.contains("--navigation-log \"${'$'}PERF_LOG_ROOT/full-suite.logcat\""))
        assertTrue(script.contains("BASELINE_TARGET_PACKAGE"))
        assertTrue(script.contains("class=com.foxhole.beta.macrobenchmark.HomeMacrobenchmark"))
        assertTrue(script.contains("/sys/kernel/tracing has no readable entries"))
        assertTrue(script.contains("Skipping baseline profile generation for external target="))
        assertTrue(script.contains("baseline_output_dir=\"app/src/release/generated/baselineProfile\""))
        assertTrue(script.contains("androidx.benchmark.enabledRules=BaselineProfile"))
        assertTrue(script.contains("FoxholeBaselineProfileGenerator"))
        assertTrue(script.contains("macrobenchmark/build/outputs"))
        assertTrue(script.contains("baseline-prof.txt"))
        assertTrue(script.contains("Lcom/foxhole/beta/"))
        assertTrue(generator.contains("BaselineProfileRule"))
        assertTrue(generator.contains("includeInStartupProfile = true"))
        assertTrue(generator.contains("openRoutingAppsPickerSearch"))
        assertTrue(generator.contains("routing_apps_picker_search"))
    }

    @Test
    fun `app packages profile installer for generated baseline profiles`() {
        val appBuild = projectFile(
            "app/build.gradle.kts",
            "build.gradle.kts",
            "../app/build.gradle.kts",
        ).readText()
        val versions = projectFile(
            "gradle/libs.versions.toml",
            "../gradle/libs.versions.toml",
        ).readText()

        assertTrue(appBuild.contains("implementation(libs.androidx.profileinstaller)"))
        assertTrue(appBuild.contains("verifyReleaseContainsBaselineProfile"))
        assertTrue(appBuild.contains("assets/dexopt/baseline.prof"))
        assertTrue(appBuild.contains("metricsDestination.set(composeCompilerMetricsDir)"))
        assertTrue(appBuild.contains("reportsDestination.set(composeCompilerReportsDir)"))
        assertTrue(appBuild.contains("verifyComposeCompilerReports"))
        assertTrue(versions.contains("androidx-profileinstaller"))
        assertTrue(versions.contains("androidx.profileinstaller:profileinstaller"))
    }

    private fun projectFile(vararg paths: String): File =
        paths
            .map(::File)
            .firstOrNull(File::isFile)
            ?: error("None of these files exists: ${paths.joinToString()}")
}
