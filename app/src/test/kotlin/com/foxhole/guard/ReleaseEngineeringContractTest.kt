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

    @Test
    fun `release candidates reject signing metadata forbidden by F-Droid`() {
        val appBuild = projectFile("build.gradle.kts").readText()
        val dependenciesInfo =
            appBuild
                .substringAfter("dependenciesInfo {")
                .substringBefore("}")
        val candidateScript = projectFile("../scripts/package-release-candidate.sh").readText()

        assertTrue(dependenciesInfo.contains("includeInApk = false"))
        assertTrue(dependenciesInfo.contains("includeInBundle = false"))
        assertTrue(candidateScript.contains("verify_fdroid_signing_blocks"))
        assertTrue(candidateScript.contains("0x504B4453: \"Dependency metadata\""))
        assertTrue(candidateScript.contains("verify_fdroid_signing_blocks \"${'$'}apk\""))
    }

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

        val inventoryBlock =
            source.between("val verifyPublicReleaseNativeInventory", "val collectPublicReleaseNativeSymbols")
        assertTrue(inventoryBlock.contains("base/assets/"))
    }

    @Test
    fun `about screen lists release-critical component licenses`() {
        val screen = projectFile("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliAboutSubScreen.kt").readText()
        val requiredComponents =
            listOf(
                "FoxHole Core",
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
            )

        requiredComponents.forEach { component ->
            assertTrue("missing license entry for $component", screen.contains("\"$component\""))
        }
    }

    @Test
    fun `Tor transport bootstrap is safe on a fresh Linux runner`() {
        val script = projectFile("../scripts/build-tor-transports.sh").readText()
        val pins = projectFile("../scripts/native-deps.sh").readText()

        assertTrue(script.contains("if [[ \"${'$'}(uname -s)\" == \"Darwin\" ]]; then"))
        assertFalse(script.contains("[[ \"${'$'}(uname -s)\" == \"Darwin\" ]] &&"))
        assertTrue(script.contains("echo \"fetching ${'$'}name @ ${'$'}ref\" >&2"))

        assertTrue(pins.contains("conjure_ref=\"${'$'}conjure_commit\""))
        listOf("lyrebird_commit", "conjure_commit").forEach { pin ->
            assertTrue(
                "$pin is not pinned to a full 40-character commit id",
                Regex("""(?m)^$pin="[0-9a-f]{40}"${'$'}""").containsMatchIn(pins),
            )
        }
        assertTrue(script.contains("\"${'$'}name pin mismatch: expected ${'$'}commit, got ${'$'}head\""))
        assertTrue(script.contains("require_offline_seed"))
    }

    @Test
    fun `signed candidate is built only on trusted dev push and main publishes those exact bytes`() {
        val androidWorkflow = projectFile("../.github/workflows/android.yml").readText()
        val releaseWorkflow = projectFile("../.github/workflows/release.yml").readText()
        val untrustedVerifyJob = androidWorkflow.substringBefore("  release-candidate:")
        val candidateJob = androidWorkflow.substringAfter("  release-candidate:")

        assertFalse(untrustedVerifyJob.contains("assembleRelease"))
        assertFalse(untrustedVerifyJob.contains("validateReleaseSigningInputs"))
        assertTrue(candidateJob.contains("github.event_name == 'push'"))
        assertTrue(candidateJob.contains("github.ref == 'refs/heads/dev'"))
        assertTrue(candidateJob.contains(":app:validateReleaseSigningInputs"))
        assertTrue(candidateJob.contains(":app:assembleRelease"))
        assertTrue(candidateJob.contains("package-release-candidate.sh"))
        assertTrue(candidateJob.contains("foxhole-app-${'$'}{{ github.sha }}"))

        // main is a publish-only trust boundary: no Gradle, keystore, or rebuild. It finds a
        assertFalse(releaseWorkflow.contains("./gradlew"))
        assertFalse(releaseWorkflow.contains("FOXHOLE_RELEASE_STORE_FILE_B64"))
        assertTrue(releaseWorkflow.contains(".commit.verification.verified"))
        assertTrue(releaseWorkflow.contains("dev_tree"))
        assertTrue(releaseWorkflow.contains("dev_tree\" == \"${'$'}MAIN_TREE"))
        assertTrue(releaseWorkflow.contains("actions/download-artifact@"))
        assertTrue(releaseWorkflow.contains("package-release-candidate.sh verify"))
        assertTrue(releaseWorkflow.contains("cmp --silent"))
        assertTrue(releaseWorkflow.contains("--draft"))
        assertTrue(releaseWorkflow.contains("--draft=false"))
        assertFalse(candidateJob.contains("foxhole.releaseProbe=true"))
    }

    @Test
    fun `candidate packager reads version name from the Gradle single source`() {
        val rootBuild = projectFile("../build.gradle.kts").readText()
        val appBuild = projectFile("build.gradle.kts").readText()
        val candidateScript = projectFile("../scripts/package-release-candidate.sh").readText()
        val coordinateReader = candidateScript.substringAfter("read_release_coordinates()")
            .substringBefore("normalized_expected_cert()")

        assertTrue(Regex("""(?m)^\s*version\s*=\s*"[^"]+"""").containsMatchIn(rootBuild))
        assertTrue(appBuild.contains("versionName = project.version.toString()"))
        assertTrue(coordinateReader.contains("version[[:space:]]*="))
        assertTrue(coordinateReader.contains("build.gradle.kts"))
        assertFalse(coordinateReader.contains("versionName[[:space:]]*="))
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
        assertTrue(candidateJob.contains("if-no-files-found: error"))
        assertTrue(candidateJob.contains("outputs/mapping/publicRelease/mapping.txt"))
        assertTrue(
            candidateJob.contains(
                "outputs/native-debug-symbols/publicRelease/publicRelease-native-symbols.zip",
            ),
        )
        assertTrue(releaseWorkflow.contains("actions: read"))
        assertTrue(releaseWorkflow.contains("attestations: write"))
        assertTrue(releaseWorkflow.contains("actions/attest-build-provenance@"))
        assertTrue(releaseWorkflow.contains("published-code"))
        assertTrue(releaseWorkflow.contains("version_code > published_code"))
        assertFalse(releaseWorkflow.contains("assembleRelease"))
        assertFalse(releaseWorkflow.contains("publicReleasePreflight"))
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
