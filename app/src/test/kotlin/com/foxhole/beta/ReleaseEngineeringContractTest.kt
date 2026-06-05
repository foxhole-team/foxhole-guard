package com.foxhole.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseEngineeringContractTest {
    @Test
    fun `public release build type cannot enable release probe diagnostics`() {
        val source = projectFile("build.gradle.kts").readText()
        val releaseBlock =
            source
                .substringAfter("release {")
                .substringBefore("create(\"internalRelease\")")

        assertTrue(source.contains("create(\"internalRelease\")"))
        assertTrue(source.contains("create(\"publicRelease\")"))
        assertTrue(source.contains("val verifyPublicReleasePrivacy by tasks.registering"))
        assertTrue(source.contains("val publicReleasePreflight by tasks.registering"))
        assertTrue(source.contains("foxhole.releaseProbe may only be used with internalRelease"))
        assertTrue(releaseBlock.contains("buildConfigField(\"boolean\", \"ENABLE_DIAGNOSTIC_LOGCAT\", \"false\")"))
        assertFalse(releaseBlock.contains("enableReleaseProbe"))
    }

    @Test
    fun `public release preflight verifies signing bundle sbom and native inventory`() {
        val source = projectFile("build.gradle.kts").readText()

        assertTrue(source.contains("releaseSigningReady"))
        assertTrue(source.contains("outputs/bundle/publicRelease"))
        assertTrue(source.contains("outputs/mapping/publicRelease/mapping.txt"))
        assertTrue(source.contains("collectPublicReleaseNativeSymbols"))
        assertTrue(source.contains("publicRelease-native-symbols.zip"))
        assertTrue(source.contains("val verifyReleaseSbom by tasks.registering"))
        assertTrue(source.contains("cyclonedxBom"))
        assertTrue(source.contains("val verifyPublicReleaseNativeInventory by tasks.registering"))
        assertTrue(source.contains("libbox.so"))
        assertTrue(source.contains("libTor.so"))
        assertTrue(source.contains("liblyrebird.so"))
        assertTrue(source.contains("libconjure_client.so"))
        assertTrue(source.contains("base/assets/"))
        assertTrue(source.contains("prepareFilteredMainAssets"))
        assertTrue(source.contains("tor/**/tor/libTor.so"))
    }

    @Test
    fun `about screen lists release-critical component licenses`() {
        val screen = projectFile("src/main/kotlin/com/foxhole/beta/ui/SettingsAboutDialog.kt").readText()
        val enStrings = projectFile("src/main/res/values/strings.xml").readText()
        val requiredLicenseStrings =
            listOf(
                "about_license_sing_box",
                "about_license_tor",
                "about_license_lyrebird",
                "about_license_conjure_client",
                "about_license_sqlcipher",
                "about_license_okhttp",
                "about_license_androidx_compose",
                "about_license_zxing",
                "about_license_blurview",
            )

        requiredLicenseStrings.forEach { stringName ->
            assertTrue(screen.contains("R.string.$stringName"))
            assertTrue(enStrings.contains("name=\"$stringName\""))
        }
    }

    @Test
    fun `connected test runner emits mandatory public QA matrix`() {
        val runner = projectFile("../scripts/run-connected-android-tests.sh").readText()

        assertTrue(runner.contains("readonly QA_MATRIX_FILE="))
        assertTrue(runner.contains("verify_qa_matrix"))
        assertTrue(runner.contains("Connected QA matrix is missing required passed dimensions"))
        assertTrue(runner.contains("com.foxhole.beta.core.data.LocalDataRepositoryDeviceTest"))
        assertTrue(runner.contains("com.foxhole.beta.vpn.BootReceiverRestoreAndroidTest"))
        listOf(
            "app_inventory",
            "boot_restore",
            "diagnostics_logs",
            "foreground_service",
            "local_data_privacy",
            "package_replace_restore",
            "profile_import",
            "proxy_runtime",
            "routing_apps",
            "routing_sites",
        ).forEach { dimension ->
            assertTrue("Missing QA dimension $dimension", runner.contains("\"$dimension\""))
        }
    }

    @Test
    fun `fgs boot compat runner captures platform proof artifacts`() {
        val runner = projectFile("../scripts/run-fgs-boot-compat-matrix.sh").readText()

        listOf(
            "ANDROID_SERIAL must be set",
            "build/fgs-boot-compat-matrix",
            "DEFAULT_COMPAT_FLAGS=(\"FGS_BOOT_COMPLETED_RESTRICTIONS\")",
            "am compat enable",
            "am compat disable",
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "com.foxhole.beta.vpn.BootReceiverRestoreAndroidTest",
            "com.foxhole.beta.vpn.ProxyRuntimeSmokeTest",
            "scripts/run-connected-android-tests.sh",
            "scripts/collect-foxhole-debug-state.sh",
            "scripts/analyze-android-perf-logs.py",
            ":app:installDebug",
        ).forEach { marker ->
            assertTrue("Missing FGS/boot compat runner marker: $marker", runner.contains(marker))
        }
    }

    @Test
    fun `api compatibility matrix runner requires release API coverage`() {
        val runner = projectFile("../scripts/run-api-compatibility-matrix.sh").readText()

        listOf(
            "FOXHOLE_API_MATRIX_SERIALS",
            "DEFAULT_API_LEVELS=(26 29 30 33 34 35 36 37)",
            "build/api-compat-matrix",
            "com.foxhole.beta.vpn.BootReceiverRestoreAndroidTest",
            "com.foxhole.beta.vpn.ProxyRuntimeSmokeTest",
            "com.foxhole.beta.ui.HomeRuntimeBehaviorTest",
            "FOXHOLE_REQUIRE_CONNECTED_QA_MATRIX=0",
            "scripts/run-connected-android-tests.sh",
            "scripts/run-fgs-boot-compat-matrix.sh",
            "FOXHOLE_API_MATRIX_RUN_FGS_COMPAT",
            ":app:installDebug",
            "API compatibility matrix missing required APIs",
        ).forEach { marker ->
            assertTrue("Missing API compatibility matrix marker: $marker", runner.contains(marker))
        }
    }

    @Test
    fun `device runtime proof runner captures fresh logs and debug state`() {
        val runner = projectFile("../scripts/run-device-runtime-proof.sh").readText()
        val runtimeStressRunner = projectFile("../scripts/run-runtime-stress-gate.sh").readText()

        listOf(
            "ANDROID_SERIAL must be set",
            "build/device-runtime-proof",
            "live-logcat-threadtime.log",
            "FOXHOLE_DEVICE_PROOF_RUN_CONNECTED",
            "FOXHOLE_DEVICE_PROOF_RUN_RUNTIME_STRESS",
            "FOXHOLE_DEVICE_PROOF_RUN_LOCAL_FIREWALL",
            "FOXHOLE_DEVICE_PROOF_RUN_MACROBENCHMARK",
            "scripts/run-connected-android-tests.sh",
            "scripts/run-runtime-stress-gate.sh",
            "LiveLocalFirewallGuardRuntimeTest",
            "scripts/run-ci-macrobenchmark.sh",
            "scripts/collect-foxhole-debug-state.sh",
            "scripts/analyze-android-perf-logs.py",
            ":app:installDebug",
        ).forEach { marker ->
            assertTrue("Missing device runtime proof marker: $marker", runner.contains(marker))
        }
        listOf(
            "FOXHOLE_RUNTIME_STRESS_ARTIFACT_DIR",
            "logcat-threadtime.log",
            "scripts/collect-foxhole-debug-state.sh",
            "scripts/analyze-android-perf-logs.py",
            "--fail-on-fatal",
            "--fail-on-anr",
            "--fail-on-oom",
            "--fail-on-strict-disk",
            ":app:installDebug",
        ).forEach { marker ->
            assertTrue("Missing runtime stress artifact marker: $marker", runtimeStressRunner.contains(marker))
        }
    }

    @Test
    fun `android workflow keeps release probe on internal release only`() {
        val workflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseStep =
            workflow
                .substringAfter("- name: build release")
                .substringBefore("- name: build internal release probe")
        val internalProbeStep =
            workflow
                .substringAfter("- name: build internal release probe")
                .substringBefore("- name: verify AdGuard DNS assets in app builds")

        assertTrue(releaseStep.contains(":app:assembleRelease"))
        assertTrue(releaseStep.contains("verifyReleaseBuildConfigDefaults"))
        assertFalse(releaseStep.contains("foxhole.releaseProbe=true"))
        assertTrue(internalProbeStep.contains("foxhole.releaseProbe=true"))
        assertTrue(internalProbeStep.contains(":app:assembleInternalRelease"))
        assertFalse(internalProbeStep.contains(":app:assembleRelease"))
    }

    @Test
    fun `release workflow runs public release preflight after sbom generation`() {
        val workflow = projectFile("../.github/workflows/release.yml").readText()
        val preflightStep =
            workflow
                .substringAfter("- name: public release preflight")
                .substringBefore("- name: verify signed release APKs")

        assertTrue(workflow.contains("- name: generate sbom"))
        assertTrue(preflightStep.contains("-Pfoxhole.sbom=true"))
        assertTrue(preflightStep.contains(":app:bundlePublicRelease"))
        assertTrue(preflightStep.contains(":app:publicReleasePreflight"))
        assertTrue(workflow.indexOf("- name: generate sbom") < workflow.indexOf("- name: public release preflight"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }
}
