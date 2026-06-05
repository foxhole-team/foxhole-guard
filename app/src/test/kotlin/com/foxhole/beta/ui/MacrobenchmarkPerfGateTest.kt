package com.foxhole.beta.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacrobenchmarkPerfGateTest {
    @Test
    fun `macrobenchmark suite covers startup scroll bottom nav smart start and app picker`() {
        val benchmarkSource = projectFile(
            "macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/HomeMacrobenchmark.kt",
            "../macrobenchmark/src/main/kotlin/com/foxhole/beta/macrobenchmark/HomeMacrobenchmark.kt",
        ).readText()

        listOf(
            "fun startup()",
            "fun homeScroll()",
            "fun bottomNavigationRoundTrip()",
            "fun settingsSmartStartTransition()",
            "fun settingsRoutingAppsPickerSearch()",
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
            "\"homeScroll\"",
            "\"settingsSmartStartTransition\"",
            "\"settingsRoutingAppsPickerSearch\"",
            "TRANSITION_FRAME_CPU_P95_MAX_MS",
            "TRANSITION_FRAME_OVERRUN_P95_MAX_MS",
            "cpuP95",
            "overrunP95",
        ).forEach { marker ->
            assertTrue(thresholdScript.contains(marker))
        }
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
        assertTrue(script.contains("androidx.benchmark.enabledRules=BaselineProfile"))
        assertTrue(script.contains("FoxholeBaselineProfileGenerator"))
        assertTrue(script.contains("*baseline-prof*.txt"))
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
        assertTrue(versions.contains("androidx-profileinstaller"))
        assertTrue(versions.contains("androidx.profileinstaller:profileinstaller"))
    }

    private fun projectFile(vararg paths: String): File =
        paths
            .map(::File)
            .firstOrNull(File::isFile)
            ?: error("None of these files exists: ${paths.joinToString()}")
}
