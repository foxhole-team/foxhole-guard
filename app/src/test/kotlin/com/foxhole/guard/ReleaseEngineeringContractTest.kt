package com.foxhole.guard

import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("val verifyPublicReleasePrivacy = tasks.register(\"verifyPublicReleasePrivacy\")"))
        assertTrue(source.contains("val publicReleasePreflight = tasks.register(\"publicReleasePreflight\")"))
        assertTrue(source.contains("foxhole.releaseProbe may only be used with internalRelease"))
        assertTrue(releaseBlock.contains("buildConfigField(\"boolean\", \"ENABLE_DIAGNOSTIC_LOGCAT\", \"false\")"))
        assertFalse(releaseBlock.contains("enableReleaseProbe"))
    }

    @Test
    fun `public release preflight verifies signing bundle sbom and native inventory`() {
        val source = projectFile("build.gradle.kts").readText()

        assertTrue(source.contains("releaseSigningReady"))
        assertTrue(source.contains("foxhole.releaseSigningProperties"))
        assertTrue(
            source.contains("val validateReleaseSigningInputs = tasks.register(\"validateReleaseSigningInputs\")")
        )
        assertTrue(source.contains("outputs/bundle/publicRelease"))
        assertTrue(source.contains("outputs/mapping/publicRelease/mapping.txt"))
        assertTrue(source.contains("collectPublicReleaseNativeSymbols"))
        assertTrue(source.contains("publicRelease-native-symbols.zip"))
        assertTrue(source.contains("val verifyReleaseSbom = tasks.register(\"verifyReleaseSbom\")"))
        assertTrue(source.contains("cyclonedxBom"))
        assertTrue(
            source.contains(
                "val verifyPublicReleaseNativeInventory = tasks.register(\"verifyPublicReleaseNativeInventory\")"
            )
        )
        assertTrue(source.contains("must not use the developer-preview line"))
    }

    // The runtime library names live ONLY in build.gradle.kts; this test cross-checks the build
    // script against itself instead of duplicating the literals, so a native-payload change
    // (new transport, dropped lib, new ABI) is a one-file edit that this contract re-validates.
    @Test
    fun `native inventory gates agree on one runtime library set and assets stay stripped`() {
        val source = projectFile("build.gradle.kts").readText()

        val requiredRuntimeLibraries =
            nativeLibraryNames(source.between("val requiredRuntimeLibraries =", "val unknownNativeLibraries"))
        val allowedNativeLibraries =
            nativeLibraryNames(source.between("val allowedNativeLibraries =", "val requiredRuntimeLibraries"))
        assertTrue("requiredRuntimeLibraries must not be empty", requiredRuntimeLibraries.isNotEmpty())
        assertTrue(
            "every required runtime library must also be in the AAB allowlist; missing: " +
                "${requiredRuntimeLibraries - allowedNativeLibraries}",
            allowedNativeLibraries.containsAll(requiredRuntimeLibraries),
        )

        // The APK-level gate (verifyReleaseContainsNativeRuntime) and the AAB-level gate
        // (verifyPublicReleaseNativeInventory) must require the same runtime libraries, each for
        // the same set of ABIs.
        //
        // The APK gate used to spell out every `lib/<abi>/<library>.so` pair as a literal, and
        // this test read those literals back. That made narrowing `foxhole.abis` fail a gate on a
        // correct build, so the gate now takes its ABIs from `shippedAndroidAbis` — the same value
        // the packaging uses — and lists the libraries once. The contract is unchanged and the
        // drift it guards against is narrower: there is now one ABI source instead of two
        // hand-maintained lists that could disagree.
        val apkRequiredLibraries =
            nativeLibraryNames(source.between("val requiredLibraries =", "val abis ="))
        assertEquals(
            "the APK gate and the AAB gate disagree on the required runtime libraries",
            requiredRuntimeLibraries,
            apkRequiredLibraries,
        )
        assertTrue(
            "the APK gate must take its ABI set from shippedAndroidAbis, not a second list",
            source.contains("expectedAbis.set(shippedAndroidAbis)"),
        )

        // Every payload preparePrivacyNativeLibs relocates from assets into jniLibs must be stripped
        // from the shipped assets by prepareFilteredMainAssets, or the APK ships it twice (once
        // executable, once dead weight that still trips the executable-assets gate).
        val relocatedPayloads =
            Regex("""include\("([^"]+)"\)""")
                .findAll(source.between("val preparePrivacyNativeLibs", "val prepareFilteredMainAssets"))
                .map { match -> match.groupValues[1].substringAfterLast('/') }
                .toSet()
        val strippedAssetNames =
            Regex("""exclude\("([^"]+)"\)""")
                .findAll(source.between("val prepareFilteredMainAssets", "tasks.matching"))
                .map { match -> match.groupValues[1].substringAfterLast('/') }
                .toSet()
        assertTrue("preparePrivacyNativeLibs must relocate at least one payload", relocatedPayloads.isNotEmpty())
        assertTrue(
            "payloads relocated into jniLibs must be excluded from shipped assets; missing excludes for: " +
                "${relocatedPayloads - strippedAssetNames}",
            strippedAssetNames.containsAll(relocatedPayloads),
        )

        // The AAB gate must keep refusing executable payloads smuggled in through assets.
        val inventoryBlock =
            source.between("val verifyPublicReleaseNativeInventory", "val collectPublicReleaseNativeSymbols")
        assertTrue(inventoryBlock.contains("base/assets/"))
    }

    @Test
    fun `about screen lists release-critical component licenses`() {
        // The license table lives in code (component names / SPDX ids are not localized): the
        // contract checks every shipped component appears among the About screen's table entries.
        val screen = projectFile("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliAboutSubScreen.kt").readText()
        val requiredComponents =
            listOf(
                "FoxCore",
                // Named for what actually ships: the Tor client linked into the .so is Arti, and
                // the entry used to say "Tor / BSD-3-Clause" — a licence belonging to the C daemon
                // this build does not contain.
                "Arti (Tor)",
                "lyrebird",
                "conjure-client",
                "i2pd (PurpleI2P)",
                "SQLCipher",
                "OkHttp",
                "AndroidX / Jetpack Compose",
                "ZXing Android Embedded",
                "lazysodium-android",
                "AdGuard DNS filter",
                "DB-IP / ip-location-db",
                "Stalkerware indicators (Echap)",
                "Silkscreen / Press Start 2P / LanaPixel",
                "1-bit Pixel Icons (Nikoichu)",
                "Pixel flags (R74n)",
            )

        requiredComponents.forEach { component ->
            assertTrue("missing license entry for $component", screen.contains("\"$component\""))
        }
    }

    @Test
    fun `connected test runner emits mandatory public QA matrix`() {
        val runner = projectFile("../scripts/run-connected-android-tests.sh").readText()

        assertTrue(runner.contains("readonly QA_MATRIX_FILE="))
        assertTrue(runner.contains("verify_qa_matrix"))
        assertTrue(runner.contains("Connected QA matrix is missing required passed dimensions"))
        assertTrue(runner.contains("com.foxhole.guard.core.data.LocalDataRepositoryDeviceTest"))
        assertTrue(runner.contains("com.foxhole.guard.ui.cli.CliAppNavigationTest"))
        assertTrue(runner.contains("com.foxhole.guard.core.data.ProfileDatabaseSchemaMigrationTest"))
        assertTrue(runner.contains("com.foxhole.core.runtime.BootReceiverRestoreAndroidTest"))
        listOf(
            "back_stack",
            "boot_restore",
            "database_migration",
            "diagnostics_logs",
            "dns_filter",
            "foreground_service",
            "local_data_privacy",
            "package_replace_restore",
            "profile_import",
            "storage_integrity",
            "tor_runtime",
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
            "com.foxhole.core.runtime.BootReceiverRestoreAndroidTest",
            "com.foxhole.guard.ui.cli.CliAppNavigationTest",
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
            "com.foxhole.core.runtime.BootReceiverRestoreAndroidTest",
            "com.foxhole.guard.ui.cli.CliAppNavigationTest",
            "com.foxhole.guard.ui.cli.home.CliHomeRuntimeBehaviorTest",
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
        val localSoakRunner = projectFile("../scripts/run-local-runtime-soak.sh").readText()

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
            "ProfileRuntimeGuardSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles",
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
        assertTrue(
            localSoakRunner.contains("com.foxhole.guard.ProfileRuntimeGuardSessionAndroidTest"),
        )
        assertFalse(
            runtimeStressRunner.contains(
                "ProfileRuntimeSessionAndroidTest#manualSmartSubscriptionRuntimeStressCycles",
            ),
        )
        assertFalse(localSoakRunner.contains("ProfileRuntimeSessionAndroidTest}\""))
    }

    @Test
    fun `signed releases are built only by the tagged release workflow`() {
        val pullRequestWorkflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseWorkflow = projectFile("../.github/workflows/release.yml").readText()
        val releaseStep =
            releaseWorkflow
                .substringAfter("- name: build release")
                .substringBefore("- name: ")

        // Nothing on the PR path may produce a signed artifact: the public
        // repository runs it on untrusted forks, where release secrets are absent
        // and a release-shaped APK would be unsigned yet indistinguishable.
        assertFalse(pullRequestWorkflow.contains("assembleRelease"))
        assertFalse(pullRequestWorkflow.contains("validateReleaseSigningInputs"))

        assertTrue(releaseStep.contains(":app:validateReleaseSigningInputs"))
        assertTrue(releaseStep.contains("assembleRelease"))
        assertTrue(releaseStep.contains("verifyReleaseBuildConfigDefaults"))
        // The probe flag relaxes release checks; a shipped build must never carry it.
        assertFalse(releaseStep.contains("foxhole.releaseProbe=true"))
    }

    @Test
    fun `android pull request paths include all build critical source trees`() {
        val workflow = projectFile("../.github/workflows/android.yml").readText()
        val pullRequestPaths =
            workflow
                .substringAfter("pull_request:")
                .substringBefore("workflow_dispatch:")

        listOf("app/**", "config/**", "core/**", "third_party/**").forEach { path ->
            assertTrue("Missing CI path filter for $path", pullRequestPaths.contains("\"$path\""))
        }
    }

    @Test
    fun `required behavior gate includes core network policy tests`() {
        val appBuild = projectFile("build.gradle.kts").readText()
        val requiredGate =
            appBuild
                .substringAfter("tasks.register(\"verifyRequiredBehaviorTests\")")
                .substringBefore("\ndependencies {")

        assertTrue(requiredGate.contains("\"testDebugUnitTest\""))
        assertTrue(requiredGate.contains("\":core:model:test\""))
        assertTrue(requiredGate.contains("\":core:network:test\""))
        assertTrue(requiredGate.contains("\":core:runtime:testDebugUnitTest\""))
        assertTrue(requiredGate.contains("project(\":core:model\").layout.buildDirectory.dir(\"test-results/test\")"))
        assertTrue(requiredGate.contains("project(\":core:network\").layout.buildDirectory.dir(\"test-results/test\")"))
        assertTrue(
            requiredGate.contains(
                "project(\":core:runtime\").layout.buildDirectory.dir(\"test-results/testDebugUnitTest\")",
            ),
        )
        assertTrue(requiredGate.contains("com.foxhole.core.model.DnsRuntimeStatsTest"))
        assertTrue(requiredGate.contains("com.foxhole.core.network.PublicUrlPolicyTest"))
        assertTrue(requiredGate.contains("com.foxhole.core.network.SubscriptionCertificateTrustTest"))
        assertTrue(requiredGate.contains("com.foxhole.core.runtime.network.IpInfoRepositoryTest"))
        assertTrue(requiredGate.contains("com.foxhole.guard.traffic.TrafficMapRepositoryAggregationTest"))
        assertTrue(requiredGate.contains("com.foxhole.guard.traffic.TrafficMapRepositoryStateTest"))
    }

    @Test
    fun `jacoco gates focus release critical app runtime packages`() {
        val appBuild = projectFile("build.gradle.kts").readText()
        val coverageBlock =
            appBuild
                .substringAfter("val jacocoReleaseCriticalClassDirectories =")
                .substringBefore("tasks.register<JacocoReport>")
        val focusedGate =
            appBuild
                .substringAfter("val verifyJacocoFocusedCoverage = tasks.register(\"verifyJacocoFocusedCoverage\")")
                .substringBefore("tasks.register<JacocoCoverageVerification>")
        val verificationGate =
            appBuild
                .substringAfter("tasks.register<JacocoCoverageVerification>")
                .substringBefore("tasks.register(\"verifyRequiredBehaviorTests\")")

        assertTrue(coverageBlock.contains("com/foxhole/guard/core/**"))
        assertTrue(coverageBlock.contains("com/foxhole/core/runtime/**"))
        assertTrue(focusedGate.contains("traffic core instruction coverage"))
        assertTrue(focusedGate.contains("network core instruction coverage"))
        assertTrue(focusedGate.contains("runtime vpn instruction coverage"))
        assertTrue(focusedGate.contains(".findAll(packageXml)"))
        assertTrue(focusedGate.contains(".lastOrNull()"))
        assertTrue(verificationGate.contains("classDirectories.setFrom(jacocoReleaseCriticalClassDirectories)"))
    }

    @Test
    fun `release workflow runs public release preflight after sbom generation`() {
        val workflow = projectFile("../.github/workflows/release.yml").readText()
        val preflightStep =
            workflow
                .substringAfter("- name: public release preflight")
                .substringBefore("- name: verify signed release APKs")

        assertTrue(workflow.contains("- name: generate sbom"))
        assertTrue(workflow.contains(":app:validateReleaseSigningInputs"))
        assertFalse(workflow.contains("validateSigningRelease"))
        assertTrue(preflightStep.contains("-Pfoxhole.sbom=true"))
        assertTrue(preflightStep.contains("vars.FOXHOLE_LAST_UPLOADED_VERSION_CODE"))
        assertTrue(preflightStep.contains("-Pfoxhole.lastUploadedVersionCode="))
        assertTrue(preflightStep.contains(":app:bundlePublicRelease"))
        assertTrue(preflightStep.contains(":app:publicReleasePreflight"))
        assertTrue(preflightStep.contains("if-no-files-found: error"))
        assertTrue(preflightStep.contains("outputs/bundle/publicRelease/*.aab"))
        assertTrue(preflightStep.contains("outputs/mapping/publicRelease/mapping.txt"))
        assertTrue(
            preflightStep.contains(
                "outputs/native-debug-symbols/publicRelease/publicRelease-native-symbols.zip",
            ),
        )
        assertTrue(workflow.indexOf("- name: generate sbom") < workflow.indexOf("- name: public release preflight"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }

    private fun nativeLibraryNames(block: String): Set<String> =
        Regex(""""([^"/]+\.so)"""")
            .findAll(block)
            .map { match -> match.groupValues[1] }
            .toSet()

    private fun String.between(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "Could not isolate source block $start -> $end" }
        return substring(startIndex, endIndex)
    }
}
