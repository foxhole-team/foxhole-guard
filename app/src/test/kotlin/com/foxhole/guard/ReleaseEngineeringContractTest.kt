package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseEngineeringContractTest {
    @Test
    fun `R8 preserves every runtime host callback invoked by JNI name`() {
        val rules = projectFile("proguard-rules.pro").readText()

        listOf(
            "public boolean protectSocket(int);",
            "public java.lang.String signingDigestForPackage(java.lang.String);",
        ).forEach { signature ->
            assertEquals(
                "JNI callback must be kept on both the interface and its implementation: $signature",
                2,
                Regex(Regex.escape(signature)).findAll(rules).count(),
            )
        }
    }

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
                "FoxHole Core",
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
                // Pixel flags (rendered in-house from flag-icons, MIT) and Natural Earth left the About
                // table by product decision:
                // their full attribution stays in THIRD_PARTY_NOTICES.md.
            )

        requiredComponents.forEach { component ->
            assertTrue("missing license entry for $component", screen.contains("\"$component\""))
        }
    }

    @Test
    fun `Tor transport bootstrap is safe on a fresh Linux runner`() {
        val script = projectFile("../scripts/build-tor-transports.sh").readText()

        assertTrue(script.contains("if [[ \"${'$'}(uname -s)\" == \"Darwin\" ]]; then"))
        assertFalse(script.contains("[[ \"${'$'}(uname -s)\" == \"Darwin\" ]] &&"))
        assertTrue(script.contains("CONJURE_REF=\"${'$'}CONJURE_COMMIT\""))
        assertTrue(script.contains("echo \"fetching ${'$'}name @ ${'$'}ref\" >&2"))
    }

    @Test
    fun `signed candidate is built only on trusted dev dispatch and main publishes those exact bytes`() {
        val androidWorkflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseWorkflow = projectFile("../.github/workflows/release.yml").readText()
        val untrustedVerifyJob = androidWorkflow.substringBefore("  release-candidate:")
        val candidateJob = androidWorkflow.substringAfter("  release-candidate:")

        // App CI is owner-dispatched. The secret-bearing candidate job is additionally gated to
        // the dev branch and the explicit candidate input, so a fork cannot produce release bytes.
        assertFalse(untrustedVerifyJob.contains("assembleRelease"))
        assertFalse(untrustedVerifyJob.contains("validateReleaseSigningInputs"))
        assertTrue(candidateJob.contains("github.event_name == 'workflow_dispatch'"))
        assertTrue(candidateJob.contains("github.ref == 'refs/heads/dev'"))
        assertTrue(candidateJob.contains("inputs.release_candidate_only"))
        assertTrue(candidateJob.contains(":app:validateReleaseSigningInputs"))
        assertTrue(candidateJob.contains(":app:assembleRelease"))
        assertTrue(candidateJob.contains("package-release-candidate.sh"))
        assertTrue(candidateJob.contains("staging_tag=\"candidate-${'$'}GITHUB_SHA\""))
        assertTrue(candidateJob.contains("--draft"))
        assertTrue(candidateJob.contains("Accept: application/octet-stream"))
        assertFalse(candidateJob.contains("actions/upload-artifact@"))

        // main is a publish-only trust boundary: no Gradle, keystore, or rebuild. It finds a
        // successful dev run with the identical Git tree and verifies/downloads that hidden draft.
        assertFalse(releaseWorkflow.contains("./gradlew"))
        assertFalse(releaseWorkflow.contains("FOXHOLE_RELEASE_STORE_FILE_B64"))
        assertTrue(releaseWorkflow.contains(".commit.verification.verified"))
        assertTrue(releaseWorkflow.contains("dev_tree"))
        assertTrue(releaseWorkflow.contains("dev_tree\" == \"${'$'}MAIN_TREE"))
        assertTrue(releaseWorkflow.contains("staging_release_id"))
        assertTrue(releaseWorkflow.contains("releases/assets/${'$'}asset_id"))
        assertFalse(releaseWorkflow.contains("actions/download-artifact@"))
        assertTrue(releaseWorkflow.contains("package-release-candidate.sh verify"))
        assertTrue(releaseWorkflow.contains("cmp --silent"))
        assertTrue(releaseWorkflow.contains("--draft"))
        assertTrue(releaseWorkflow.contains("releases/${'$'}release_id"))
        assertTrue(releaseWorkflow.contains("-F draft=false"))
        assertTrue(releaseWorkflow.contains("releases/tags/${'$'}RELEASE_TAG"))
        assertFalse(releaseWorkflow.contains("gh release download \"${'$'}RELEASE_TAG\""))
        assertFalse(candidateJob.contains("foxhole.releaseProbe=true"))
    }

    @Test
    fun `android workflow is manual only`() {
        val workflow = projectFile("../.github/workflows/android.yml").readText()
        val triggerBlock = workflow.substringAfter("on:").substringBefore("permissions:")

        assertTrue(triggerBlock.contains("workflow_dispatch:"))
        assertFalse(triggerBlock.contains("pull_request:"))
        assertFalse(triggerBlock.contains("push:"))
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
    fun `dev candidate runs public preflight and main release never rebuilds`() {
        val androidWorkflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseWorkflow = projectFile("../.github/workflows/release.yml").readText()
        val candidateJob = androidWorkflow.substringAfter("  release-candidate:")

        assertTrue(candidateJob.contains("published-code"))
        assertTrue(candidateJob.contains("-Pfoxhole.sbom=true"))
        assertTrue(candidateJob.contains("-Pfoxhole.lastUploadedVersionCode="))
        assertTrue(candidateJob.contains(":app:validateReleaseSigningInputs"))
        assertTrue(candidateJob.contains(":app:assembleRelease"))
        assertTrue(candidateJob.contains(":app:publicReleasePreflight"))
        assertTrue(candidateJob.contains("stage immutable candidate"))
        assertTrue(candidateJob.contains("Candidate staging release must contain exactly six assets"))
        assertFalse(candidateJob.contains("actions/upload-artifact@"))
        assertTrue(releaseWorkflow.contains("actions: read"))
        assertTrue(releaseWorkflow.contains("attestations: write"))
        assertTrue(releaseWorkflow.contains("actions/attest-build-provenance@"))
        assertTrue(releaseWorkflow.contains("published-code"))
        assertTrue(releaseWorkflow.contains("version_code > published_code"))
        assertFalse(releaseWorkflow.contains("assembleRelease"))
        assertFalse(releaseWorkflow.contains("publicReleasePreflight"))
    }

    @Test
    fun `release automation uses native Node 24 actions`() {
        val androidWorkflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseWorkflow = projectFile("../.github/workflows/release.yml").readText()
        val setupAction = projectFile("../.github/actions/setup-foxcore/action.yml").readText()
        val automation = androidWorkflow + releaseWorkflow + setupAction

        assertFalse(automation.contains("FORCE_JAVASCRIPT_ACTIONS_TO_NODE24"))
        assertFalse(automation.contains("actions/checkout@11d5960"))
        assertTrue(androidWorkflow.contains("actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5"))
        assertTrue(
            releaseWorkflow.contains(
                "actions/attest-build-provenance@977bb373ede98d70efdf65b84cb5f73e068dcc2a # v3",
            ),
        )
        assertTrue(setupAction.contains("actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5"))
        assertTrue(setupAction.contains("gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c # v5"))
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
