package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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
    fun `R8 keeps concrete Glance widget providers distinct`() {
        val rules = projectFile("proguard-rules.pro").readText()

        listOf("StatusWidget", "WebAppsWidget", "FoxStatusWidget").forEach { widget ->
            assertTrue(
                "Glance provider identity must survive release shrinking: $widget",
                rules.contains("-keep class com.foxhole.guard.widget.$widget { *; }"),
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
    fun `fdroid history preserves the reviewed build and hardens only the current entry`() {
        val metadata = projectFile("../metadata/com.foxhole.guard.yml").readText()
        val builds = fdroidBuildBlocks(metadata)

        assertEquals(listOf("0.0.2", "0.1.0"), builds.map(FdroidBuildBlock::versionName))

        val reviewed = builds.single { it.versionName == "0.0.2" }.body
        assertTrue(reviewed.contains("versionCode: 90"))
        assertTrue(reviewed.contains("commit: 96e963fc020ef9615c1301d7f252352c3ab84cdd"))
        assertTrue(reviewed.contains("FoxHoleCore@83c9f4b95080c254a4f88d2f2cb7fd48ad849c05"))
        assertTrue(reviewed.contains("/home/runner/work/foxhole-guard"))
        assertTrue(reviewed.contains("SOURCE_DATE_EPOCH=1787044986"))
        assertTrue(reviewed.contains("1e327f87ac68eff0b0c549402952a1c230fe4b22"))

        val current = builds.single { it.versionName == "0.1.0" }.body
        val sudo = current.substringAfter("    sudo:").substringBefore("    output:")
        val prebuild = current.substringAfter("    prebuild:").substringBefore("    build:")

        assertTrue(current.contains("versionCode: 117"))
        assertTrue(current.contains("FoxHoleCore@cd8bf71e950247d7bcebc8cd62142adef5c970fc"))
        assertTrue(current.contains("foxhole.splitApks=true"))
        assertTrue("missing base tools", sudo.contains("build-essential ca-certificates"))
        assertTrue("missing native toolchain", sudo.contains("cmake ninja-build perl pkg-config rustup"))
        assertFalse("unused curl", Regex("""\bcurl\b""").containsMatchIn(sudo))
        assertFalse("unused git", Regex("""\bgit\b""").containsMatchIn(sudo))
        assertFalse("unused Rust components", prebuild.contains("rustup component add"))
        assertFalse("absolute home path", current.contains("/home/"))
        assertFalse(
            "source-moving workaround",
            Regex("""(?m)^\s*-\s+mv\b""").containsMatchIn(current),
        )
    }

    @Test
    fun `store descriptions list all five independently signed data sets`() {
        val fdroid = projectFile("../metadata/com.foxhole.guard.yml").readText()
        val english = projectFile("../fastlane/metadata/android/en-US/full_description.txt").readText()
        val russian = projectFile("../fastlane/metadata/android/ru-RU/full_description.txt").readText()

        listOf(fdroid, english).forEach { description ->
            assertTrue(description.contains("five independently signed data sets"))
            assertTrue(description.contains("TLS fingerprint tables"))
            assertFalse(description.contains("four signed data sets"))
        }
        assertTrue(russian.contains("пять независимо подписанных наборов данных"))
        assertTrue(russian.contains("таблицы TLS-отпечатков"))
        assertFalse(russian.contains("четыре подписанных набора данных"))
    }

    @Test
    fun `i2pd build remaps host paths and gates every bundled binary`() {
        val build = projectFile("../scripts/build-i2pd.sh").readText()
        val verifier = projectFile("../scripts/verify-native-host-paths.sh").readText()
        val appBuild = projectFile("build.gradle.kts").readText()

        listOf("-ffile-prefix-map=", "-fmacro-prefix-map=", "-fdebug-prefix-map=").forEach { flag ->
            assertTrue(build.contains(flag))
        }
        assertTrue(build.contains("--prefix=\"${'$'}openssl_prefix\" --openssldir=/etc/ssl"))
        assertTrue(build.contains("make DESTDIR=\"${'$'}openssl_stage\" install_dev"))
        assertTrue(build.contains("normalize_i2pd_build_id"))
        assertTrue(build.contains("--remove-section=.note.gnu.build-id"))
        assertTrue(build.contains("--update-section \".note.gnu.build-id=${'$'}note\""))
        assertTrue(build.contains("\"${'$'}verify_host_paths\" \"${'$'}out_dir/libi2pd.so\""))

        assertTrue(verifier.contains("/Users/"))
        assertTrue(verifier.contains("/home/"))
        assertTrue(verifier.contains("foxhole-native-deps"))
        assertTrue(appBuild.contains("val verifyBundledI2pdHostPaths = tasks.register"))
        assertTrue(
            appBuild.contains("builder.environment()[\"I2PD_ABIS\"] = shippedAndroidAbis.joinToString(\" \")"),
        )
        assertTrue(appBuild.contains("dependsOn(verifyBundledI2pdHostPaths)"))
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
                "OpenSSL",
                "Boost",
                "Android libc++",
                "SQLCipher",
                "libsodium",
                "OkHttp",
                "AndroidX / Jetpack Compose",
                "ZXing Android Embedded",
                "lazysodium-android",
                "AdGuard DNS filter",
                "DB-IP / ip-location-db",
                "Stalkerware indicators (Echap)",
                "Tiny5",
                "JetBrains Mono",
                "flag-icons source set",
            )

        requiredComponents.forEach { component ->
            assertTrue("missing license entry for $component", screen.contains("\"$component\""))
        }
    }

    @Test
    fun `bundled typography ships only pinned fonts with exact upstream licenses`() {
        val fontDirectory = projectFile("src/main/res/font")
        val theme = projectFile("src/main/kotlin/com/foxhole/guard/ui/cli/CliTheme.kt").readText()
        val notices = projectFile("../THIRD_PARTY_NOTICES.md").readText()
        val inventory = projectFile("../third_party/fonts/README.md").readText()

        val expectedFonts =
            mapOf(
                "tiny5_regular.ttf" to
                    "756261726e160783bfa66723951f12e0e7ca53fa1dfcfbeced760e41093f9702",
                "jetbrains_mono_bold.ttf" to
                    "5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb",
            )
        assertEquals(
            expectedFonts.keys,
            fontDirectory.listFiles().orEmpty().map(File::getName).toSet(),
        )
        expectedFonts.forEach { (name, expectedHash) ->
            assertEquals(name, expectedHash, projectFile("src/main/res/font/$name").sha256())
            assertTrue("font hash missing from inventory: $name", inventory.contains(expectedHash))
        }
        val expectedLicenses =
            mapOf(
                "Tiny5-OFL.txt" to
                    "6fe7d64407c69d187748206265977654747d3e2fe9e38e45a62cd03ec4770df6",
                "JetBrainsMono-OFL.txt" to "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad",
            )
        expectedLicenses.forEach { (name, expectedHash) ->
            assertEquals(name, expectedHash, projectFile("../third_party/fonts/$name").sha256())
            assertTrue("license hash missing from inventory: $name", inventory.contains(expectedHash))
        }
        assertTrue(theme.contains("R.font.tiny5_regular"))
        assertTrue(theme.contains("R.font.jetbrains_mono_bold"))
        assertTrue(notices.contains("Tiny5"))
        assertTrue(notices.contains("JetBrains Mono"))
    }

    @Test
    fun `bundled native licenses and runtime flags match reviewed upstream bytes`() {
        val expectedLicenses =
            mapOf(
                "third_party/flags/flag-icons-LICENSE.txt" to
                    "8f1195d55a2fd315a07d812328470ca9ba2abb78c8d317ff19619d5125e00cea",
                "third_party/i2pd/LICENSE" to
                    "eb5ac2a5ede8cd6bed9e6d93ad943119a73bfaba378f21bafa307f9b026b2034",
                "third_party/icons/Tabler-MIT.txt" to
                    "b740a1d46122672da62833e97f7e7c8a13fa85cbc7445b584b297cc00dde93db",
                "third_party/licenses/JNA-Apache-2.0.txt" to
                    "0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594",
                "third_party/licenses/JNA-LGPL-2.1.txt" to
                    "eea173a556abac0370461e57e12aab266894ea6be3874c2be05fd87871f75449",
                "third_party/licenses/JNA-LICENSE.txt" to
                    "07c938b23950ab7d47a24ef35f9f5da3a05ae164278dc959ad6994135ed59ff1",
                "third_party/licenses/SQLCipher-BSD-3-Clause.txt" to
                    "09e4af560ce2e3c9c2aa6b564e35947b03db7d1ae345f22a32793ed46542cc14",
                "third_party/licenses/lazysodium-MPL-2.0.txt" to
                    "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5",
                "third_party/licenses/libsodium-ISC.txt" to
                    "43964d976a6db3fb986af689d05f8ca0e9971878bccae709750dac8fdc4a99cf",
            )
        expectedLicenses.forEach { (path, expectedHash) ->
            assertEquals(path, expectedHash, projectFile("../$path").sha256())
        }

        val manifest = projectFile("../third_party/flags/app-assets.sha256")
            .readLines()
            .associate { line ->
                val match = requireNotNull(Regex("([0-9a-f]{64})  ([a-z0-9-]+\\.png)").matchEntire(line))
                match.groupValues[2] to match.groupValues[1]
            }
        assertEquals(270, manifest.size)
        val assets = projectFile("src/main/assets/flags")
            .listFiles { file -> file.isFile && file.extension == "png" }
            .orEmpty()
            .associateBy(File::getName)
        assertEquals(manifest.keys, assets.keys)
        manifest.forEach { (name, expectedHash) ->
            assertEquals(name, expectedHash, requireNotNull(assets[name]).sha256())
        }

        val generator = projectFile("../scripts/generate-license-assets.py").readText()
        val reproducer = projectFile("../scripts/generate-app-flag-assets.sh").readText()
        assertTrue(generator.contains("require_flag_assets()"))
        assertTrue(reproducer.contains("086f7e97d657358203916dbe84f61c2bccaa81eb"))
        assertTrue(reproducer.contains("Expected 270 manifest entries"))
    }

    @Test
    fun `Tor integration config is pinned to the reviewed official bundle`() {
        val provenance = projectFile("../third_party/tor-config/README.md").readText()
        val expected =
            mapOf(
                "data/torrc-defaults" to
                    "4ac86cc6e2468e54b118430881d81fcaec3e95fcfcaccd6076629b5a125a8306",
                "tor/pluggable_transports/pt_config.json" to
                    "12852ac8bd29ac3bbb10e2d0d6878ab4f27c636371aaa95a93ed866a030c649f",
                "tor/pluggable_transports/README.CONJURE.md" to
                    "f7e9211dd0e0089c93a0a84329fed41b2ac7b988531475b5eb2c86d38ad64d6c",
            )
        val manifest = projectFile("../third_party/tor-config/assets.sha256")
            .readLines()
            .associate { line ->
                val match = requireNotNull(Regex("([0-9a-f]{64})  ([A-Za-z0-9_./-]+)").matchEntire(line))
                match.groupValues[2] to match.groupValues[1]
            }
        assertEquals(expected, manifest)
        assertTrue(provenance.contains("tor-expert-bundle-android-aarch64-15.0.9.tar.gz"))
        assertTrue(provenance.contains("5bdf7d70e3453d13ac5c7b094903b2aab987fbdeb99ea2145093a12119f7c154"))

        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            listOf("data/torrc-defaults", "tor/pluggable_transports/pt_config.json").forEach { path ->
                assertEquals(
                    "$abi/$path",
                    expected.getValue(path),
                    projectFile("src/main/assets/tor/$abi/$path").sha256(),
                )
            }
        }
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            val path = "tor/pluggable_transports/README.CONJURE.md"
            assertEquals("$abi/$path", expected.getValue(path), projectFile("src/main/assets/tor/$abi/$path").sha256())
        }
    }

    @Test
    fun `release packages complete user-readable license assets`() {
        val appBuild = projectFile("build.gradle.kts").readText()
        val generator = projectFile("../scripts/generate-license-assets.py").readText()
        val candidate = projectFile("../scripts/package-release-candidate.sh").readText()
        val workflow = projectFile("../.github/workflows/release.yml").readText()
        val about = projectFile("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliAboutSubScreen.kt").readText()
        val notices = projectFile("../THIRD_PARTY_NOTICES.md").readText()

        assertTrue(appBuild.contains("val prepareBundledLicenseAssets = tasks.register"))
        assertTrue(appBuild.contains("from(generatedLicenseAssetsDir)"))
        assertTrue(appBuild.contains("verifyReleaseContainsLicenseAssets"))
        listOf(
            "android/JNA-LICENSE.txt",
            "android/SQLCipher-BSD-3-Clause.txt",
            "android/lazysodium-MPL-2.0.txt",
            "android/libsodium-ISC.txt",
            "flags/flag-icons-LICENSE.txt",
            "foxcore/foxcore-aarch64-linux-android.cdx.json",
            "i2pd/Android-NDK-29-NOTICE.toolchain.txt",
            "i2pd/Boost-1.84.0-BSL-1.0.txt",
            "i2pd/OpenSSL-3.5.4-Apache-2.0.txt",
            "i2pd/i2pd-BSD-3-Clause.txt",
            "tor/conjure-client-BSD-3-Clause.txt",
            "tor/lyrebird-BSD-3-Clause.txt",
            "tor/lyrebird-GPL-3.0-or-later.txt",
            "tor-config/PROVENANCE.txt",
            "tor-config/upstream-members/README.CONJURE.md",
        ).forEach { required ->
            val generatorPath =
                when {
                    required.startsWith("android/") ->
                        "third_party/licenses/${required.removePrefix("android/")}"
                    required == "i2pd/Boost-1.84.0-BSL-1.0.txt" ->
                        "f\"i2pd/Boost-{boost_version}-BSL-1.0.txt\""
                    required == "i2pd/OpenSSL-3.5.4-Apache-2.0.txt" ->
                        "f\"i2pd/OpenSSL-{openssl_version}-Apache-2.0.txt\""
                    else -> required
                }
            assertTrue("generator does not package $required", generator.contains(generatorPath))
            assertTrue("APK gate does not require $required", appBuild.contains(required))
            assertTrue("candidate does not require $required", candidate.contains(required))
        }
        assertTrue(candidate.contains("licenseNotices"))
        assertTrue(candidate.contains("verify_license_notices_archive"))
        assertTrue(candidate.contains("verify_android_sbom_inventory"))
        assertTrue(generator.contains("require_lyrebird_composite_license"))
        assertTrue(generator.contains("not any(part.startswith(\".\") for part in relative.parts)"))
        assertTrue(generator.contains("expected_module_counts = {\"lyrebird\": 61, \"conjure-client\": 33}"))
        assertTrue(workflow.contains("[[ ${'$'}{#assets[@]} -eq 7 ]]"))
        assertFalse(about.contains("CliLicenseNoticesSheet"))
        assertFalse(about.contains("THIRD_PARTY_NOTICES.md"))
        assertTrue(notices.contains("GPL-3.0-or-later AND BSD-3-Clause"))
        assertTrue(notices.contains("does not populate component"))
        assertFalse(notices.contains("covers everything inside the APK"))
        assertFalse(notices.contains("License: **BSD-3-Clause**, © Yawning Angel"))
    }

    @Test
    fun `Tor transport bootstrap is safe on a fresh Linux runner`() {
        val script = projectFile("../scripts/build-tor-transports.sh").readText()
        val pins = projectFile("../scripts/native-deps.sh").readText()

        assertTrue(script.contains("if [[ \"${'$'}(uname -s)\" == \"Darwin\" ]]; then"))
        assertFalse(script.contains("[[ \"${'$'}(uname -s)\" == \"Darwin\" ]] &&"))
        assertTrue(script.contains("echo \"fetching ${'$'}name @ ${'$'}ref\" >&2"))
        assertTrue(script.contains("unset GOROOT GOTOOLDIR"))

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
    fun `CI seeds the native dependency tree before Gradle license generation`() {
        val setup = projectFile("../.github/actions/setup-foxcore/action.yml").readText()

        val fetchIndex = setup.indexOf("scripts/fetch-native-deps.sh")
        val gradleIndex = setup.indexOf("install cargo-ndk and fetch locked Rust dependencies")
        assertTrue(fetchIndex >= 0)
        assertTrue(gradleIndex > fetchIndex)
    }

    @Test
    fun `GitHub actions use Node 24 runtimes without the legacy override`() {
        val workflowPaths =
            listOf(
                "../.github/workflows/android.yml",
                "../.github/workflows/release.yml",
                "../.github/actions/setup-foxcore/action.yml",
            )
        val workflows = workflowPaths.joinToString("\n") { path -> projectFile(path).readText() }

        assertFalse(workflows.contains("FORCE_JAVASCRIPT_ACTIONS_TO_NODE24"))
        assertFalse(workflows.contains("11d5960a326750d5838078e36cf38b85af677262"))
        assertFalse(workflows.contains("cf277c60eb25467037889841efdb72551f06f6c3"))
        assertFalse(workflows.contains("0b6dd653ba04f4f93bf581ec31e66cbd7dcb644d"))
        assertFalse(workflows.contains("ea165f8d65b6e75b540449e92b4886f43607fa02"))
        assertFalse(workflows.contains("d3f86a106a0bac45b974a628896c90dbdf5c8093"))
        assertFalse(workflows.contains("96b4a1ef7235a096b17240c259729fdd70c83d45"))
        assertTrue(workflows.contains("actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09"))
        assertTrue(workflows.contains("actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961"))
        assertTrue(workflows.contains("gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c"))
        assertTrue(workflows.contains("actions/upload-artifact@b7c566a772e6b6bfb58ed0dc250532a479d7789f"))
        assertTrue(workflows.contains("actions/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131"))
        assertTrue(workflows.contains("actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8"))
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
        assertTrue(releaseWorkflow.contains("releases/${'$'}release_id"))
        assertTrue(releaseWorkflow.contains("releases/assets/${'$'}asset_id"))
        assertFalse(releaseWorkflow.contains("gh release download"))
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

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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

private data class FdroidBuildBlock(
    val versionName: String,
    val body: String,
)

private fun fdroidBuildBlocks(metadata: String): List<FdroidBuildBlock> {
    val buildsSection = metadata.substringAfter("Builds:\n").substringBefore("\nMaintainerNotes:")
    val starts = Regex("""(?m)^  - versionName: ([^\n]+)$""").findAll(buildsSection).toList()
    return starts.mapIndexed { index, match ->
        val end = starts.getOrNull(index + 1)?.range?.first ?: buildsSection.length
        FdroidBuildBlock(
            versionName = match.groupValues[1].trim(),
            body = buildsSection.substring(match.range.first, end),
        )
    }
}
