import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

abstract class VerifyBundledNativeRuntimeInReleaseApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val expectedAbis: SetProperty<String>

    @TaskAction
    fun verify() {
        val apks =
            apkDirectory
                .asFileTree
                .matching { include("*.apk") }
                .files
                .sortedBy { it.name }
        require(apks.isNotEmpty()) { "release APK is missing from ${apkDirectory.get().asFile}" }

        val requiredLibraries =
            listOf(
                "libfoxhole_native.so",
                "liblyrebird.so",
                "libconjure_client.so",
                "libi2pd.so",
            )
        val abis = expectedAbis.get()
        require(abis.isNotEmpty()) { "verifyReleaseContainsNativeRuntime has no ABIs to look for" }
        val requiredEntries =
            abis.flatMap { abi -> requiredLibraries.map { library -> "lib/$abi/$library" } }.toSet()
        val discoveredEntries = linkedSetOf<String>()

        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { entryName ->
                        entryName.endsWith("/libfoxhole_native.so") ||
                            entryName.endsWith("/liblyrebird.so") ||
                            entryName.endsWith("/libconjure_client.so") ||
                            entryName.endsWith("/libi2pd.so")
                    }
                    .forEach(discoveredEntries::add)
            }
        }

        val missingEntries = requiredEntries - discoveredEntries
        require(missingEntries.isEmpty()) {
            buildString {
                append("release APK was produced without required native runtime entries: ")
                append(missingEntries.joinToString())
                append(". Found release APKs: ")
                append(apks.joinToString { it.name })
                append(". Found native runtime entries: ")
                append(discoveredEntries.joinToString().ifBlank { "none" })
            }
        }
    }
}

abstract class VerifyReleaseBaselineProfileInApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val apks =
            apkDirectory
                .asFileTree
                .matching { include("*.apk") }
                .files
                .sortedBy { it.name }
        require(apks.isNotEmpty()) { "release APK is missing from ${apkDirectory.get().asFile}" }
        apks.forEach { apk ->
            val entries = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
            require("assets/dexopt/baseline.prof" in entries) {
                "release APK ${apk.name} is missing assets/dexopt/baseline.prof"
            }
            require("assets/dexopt/baseline.profm" in entries) {
                "release APK ${apk.name} is missing assets/dexopt/baseline.profm"
            }
            require(entries.any { it.startsWith("META-INF/androidx.profileinstaller_") }) {
                "release APK ${apk.name} is missing ProfileInstaller metadata"
            }
        }
    }
}

abstract class VerifyBundledLicenseAssetsInReleaseApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val apks =
            apkDirectory
                .asFileTree
                .matching { include("*.apk") }
                .files
                .sortedBy { it.name }
        require(apks.isNotEmpty()) { "release APK is missing from ${apkDirectory.get().asFile}" }
        val required =
            setOf(
                "assets/licenses/FoxHole-Guard-GPL-3.0-or-later.txt",
                "assets/licenses/MANIFEST.json",
                "assets/licenses/THIRD_PARTY_NOTICES.md",
                "assets/licenses/SHA256SUMS",
                "assets/licenses/android/JNA-Apache-2.0.txt",
                "assets/licenses/android/JNA-LGPL-2.1.txt",
                "assets/licenses/android/JNA-LICENSE.txt",
                "assets/licenses/android/SQLCipher-BSD-3-Clause.txt",
                "assets/licenses/android/lazysodium-MPL-2.0.txt",
                "assets/licenses/android/libsodium-ISC.txt",
                "assets/licenses/flags/app-assets.sha256",
                "assets/licenses/flags/flag-icons-LICENSE.txt",
                "assets/licenses/fonts/JetBrainsMono-OFL.txt",
                "assets/licenses/fonts/Tiny5-OFL.txt",
                "assets/licenses/foxcore/FoxHole-Core-GPL-3.0-or-later.txt",
                "assets/licenses/foxcore/THIRD_PARTY_NOTICES.md",
                "assets/licenses/foxcore/foxcore-aarch64-linux-android.cdx.json",
                "assets/licenses/i2pd/Android-NDK-29-NOTICE.txt",
                "assets/licenses/i2pd/Android-NDK-29-NOTICE.toolchain.txt",
                "assets/licenses/i2pd/Boost-1.84.0-BSL-1.0.txt",
                "assets/licenses/i2pd/OpenSSL-3.5.8-Apache-2.0.txt",
                "assets/licenses/i2pd/i2pd-BSD-3-Clause.txt",
                "assets/licenses/icons/Tabler-MIT.txt",
                "assets/licenses/tor/GO-MODULES.json",
                "assets/licenses/tor/Go-1.26.8-BSD-3-Clause.txt",
                "assets/licenses/tor/conjure-client-BSD-3-Clause.txt",
                "assets/licenses/tor/lyrebird-BSD-3-Clause.txt",
                "assets/licenses/tor/lyrebird-GPL-3.0-or-later.txt",
                "assets/licenses/tor-config/PROVENANCE.txt",
                "assets/licenses/tor-config/assets.sha256",
                "assets/licenses/tor-config/upstream-members/README.CONJURE.md",
                "assets/licenses/tor-config/upstream-members/pt_config.json",
                "assets/licenses/tor-config/upstream-members/torrc-defaults.txt",
            )
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                require(entries.containsAll(required)) {
                    "release APK ${apk.name} is missing license assets: ${(required - entries).joinToString()}"
                }
                val sums =
                    zip.getInputStream(requireNotNull(zip.getEntry("assets/licenses/SHA256SUMS")))
                        .bufferedReader()
                        .readLines()
                val checksumEntries =
                    sums.map { line ->
                        val match = requireNotNull(Regex("([0-9a-f]{64})  (.+)").matchEntire(line)) {
                            "invalid license checksum line: $line"
                        }
                        val relative = match.groupValues[2]
                        require(!relative.startsWith('/') && relative.split('/').none { segment -> segment == ".." }) {
                            "unsafe license checksum path: $relative"
                        }
                        match.groupValues[1] to relative
                    }
                require(checksumEntries.map { (_, relative) -> relative }.distinct().size == checksumEntries.size) {
                    "release APK ${apk.name} contains duplicate license checksum paths"
                }
                val expectedLicenseEntries =
                    checksumEntries.map { (_, relative) -> "assets/licenses/$relative" }.toSet() +
                        "assets/licenses/SHA256SUMS"
                val actualLicenseEntries =
                    entries.filter { entry -> entry.startsWith("assets/licenses/") && !entry.endsWith('/') }.toSet()
                require(actualLicenseEntries == expectedLicenseEntries) {
                    "release APK ${apk.name} license inventory does not match SHA256SUMS"
                }
                checksumEntries.forEach { (expected, relative) ->
                    val entry = requireNotNull(zip.getEntry("assets/licenses/$relative")) {
                        "license checksum references a missing APK asset: $relative"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val actual = zip.getInputStream(entry).use { input -> digest.digest(input.readBytes()) }
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                    require(actual == expected) { "license asset checksum mismatch: $relative" }
                }
            }
        }
    }
}

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    jacoco
}

val enableAbiSplitApks = providers.gradleProperty("foxhole.splitApks").map(String::toBoolean).orElse(false).get()
val enableReleaseProbe = providers.gradleProperty("foxhole.releaseProbe").map(String::toBoolean).orElse(false).get()
val enableStrictMode = providers.gradleProperty("foxhole.strictMode").map(String::toBoolean).orElse(false).get()
val publicApplicationId = "com.foxhole.guard"
val lastUploadedVersionCodeProvided =
    providers.gradleProperty("foxhole.lastUploadedVersionCode").isPresent
val lastUploadedPublicVersionCode =
    providers.gradleProperty("foxhole.lastUploadedVersionCode").map(String::toInt).orElse(1).get()
val defaultReleaseSigningPropertiesFile =
    rootProject.projectDir.parentFile.resolve("dev/signing/release-signing.properties")
val releaseSigningPropertiesFile =
    providers.gradleProperty("foxhole.releaseSigningProperties")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
        ?: defaultReleaseSigningPropertiesFile
val releaseSigningProperties =
    Properties().apply {
        if (releaseSigningPropertiesFile.isFile) {
            releaseSigningPropertiesFile.inputStream().use(::load)
        }
    }

fun releaseSigningValue(
    propertyName: String,
    envName: String,
): String? =
    providers.environmentVariable(envName).orNull
        ?: releaseSigningProperties.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)

val releaseSigningStoreFilePath = releaseSigningValue("storeFile", "FOXHOLE_RELEASE_STORE_FILE")
val releaseSigningStorePassword = releaseSigningValue("storePassword", "FOXHOLE_RELEASE_STORE_PASSWORD")
val releaseSigningKeyAlias = releaseSigningValue("keyAlias", "FOXHOLE_RELEASE_KEY_ALIAS")
val releaseSigningKeyPassword = releaseSigningValue("keyPassword", "FOXHOLE_RELEASE_KEY_PASSWORD")
val releaseSigningStoreType = releaseSigningValue("storeType", "FOXHOLE_RELEASE_STORE_TYPE") ?: "PKCS12"
val releaseSigningStoreFile =
    releaseSigningStoreFilePath
        ?.let { configuredPath ->
            val configuredFile = File(configuredPath)
            val candidates =
                if (configuredFile.isAbsolute) {
                    listOf(
                        configuredFile,
                        releaseSigningPropertiesFile.parentFile.resolve(configuredFile.name),
                    )
                } else {
                    listOf(
                        releaseSigningPropertiesFile.parentFile.resolve(configuredPath),
                        rootProject.file(configuredPath),
                        releaseSigningPropertiesFile.parentFile.resolve(configuredFile.name),
                    )
                }
            candidates.firstOrNull(File::isFile)
        }
val appUpdateChannel =
    providers.gradleProperty("foxhole.updateChannel").orNull?.trim()?.takeIf(String::isNotEmpty) ?: "github"

val appUpdateFloorVersionCode =
    providers.gradleProperty("foxhole.updateFloorVersionCode").orNull?.trim()?.toLongOrNull() ?: 0L
val appUpdateSupportedUntilEpochDay =
    providers.gradleProperty("foxhole.updateSupportedUntilEpochDay").orNull?.trim()?.toLongOrNull() ?: 0L

val foxCoreSourceRoot =
    providers.gradleProperty("foxhole.foxCoreSourceRoot")
        .orElse(providers.environmentVariable("FOXCORE_SOURCE_ROOT"))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(rootProject::file)
        ?: rootProject.projectDir.parentFile.resolve("foxhole-core")
require(foxCoreSourceRoot.resolve("Cargo.toml").isFile) {
    "FoxCore source is missing at ${foxCoreSourceRoot.absolutePath}. " +
        "Set FOXCORE_SOURCE_ROOT or -Pfoxhole.foxCoreSourceRoot=<path>."
}
val pinnedFoxCoreRevision =
    rootProject
        .file("config/foxcore-revision.txt")
        .takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.takeIf { revision -> revision.matches(Regex("^[0-9a-f]{40}$")) }
        ?: error("config/foxcore-revision.txt must contain one full Git commit SHA")
val checkedOutFoxCoreRevision: String? =
    runCatching {
        val git =
            providers.exec {
                commandLine("git", "-C", foxCoreSourceRoot.absolutePath, "rev-parse", "HEAD")
                isIgnoreExitValue = true
            }
        git.standardOutput.asText
            .get()
            .trim()
            .takeIf { head -> git.result.get().exitValue == 0 && head.matches(Regex("^[0-9a-f]{40}$")) }
    }.getOrNull()
val requirePinnedFoxCoreRevision =
    providers.gradleProperty("foxhole.requirePinnedFoxCore").orNull?.trim()?.toBooleanStrictOrNull()
        ?: gradle.startParameter.taskNames.any { task -> task.contains("elease") }
when (checkedOutFoxCoreRevision) {
    pinnedFoxCoreRevision -> Unit
    null ->
        logger.warn(
            "\n! FoxCore revision unverified: no Git HEAD readable at ${foxCoreSourceRoot.absolutePath}." +
                "\n! config/foxcore-revision.txt pins $pinnedFoxCoreRevision and nothing here can confirm it.\n",
        )

    else -> {
        val banner =
            buildString {
                append("\n")
                append("!".repeat(96)).append("\n")
                append("! FoxCore revision mismatch — this build would NOT be the pinned core.\n")
                append("!   pinned  (config/foxcore-revision.txt): $pinnedFoxCoreRevision\n")
                append("!   checked out (${foxCoreSourceRoot.absolutePath}): $checkedOutFoxCoreRevision\n")
                append("! Fix with: git -C ${foxCoreSourceRoot.absolutePath} checkout $pinnedFoxCoreRevision\n")
                append("! or update config/foxcore-revision.txt if the new revision is the one to ship.\n")
                append("!".repeat(96)).append("\n")
            }
        if (requirePinnedFoxCoreRevision) {
            error(banner)
        }
        logger.error(banner)
    }
}

val foxCoreAndroidBuildScript = foxCoreSourceRoot.resolve("scripts/android-build.sh")
val foxCoreVersion =
    Regex("""(?m)^version\s*=\s*"([^"]+)"\s*$""")
        .find(foxCoreSourceRoot.resolve("Cargo.toml").readText())
        ?.groupValues
        ?.get(1)
        ?: error("FoxCore workspace version is missing")
val generatedFoxCoreNativeLibs = layout.buildDirectory.dir("generated/foxCoreNativeLibs")
val shippedAndroidAbis =
    (providers.gradleProperty("foxhole.abis").orNull ?: "arm64-v8a")
        .split(" ", ",")
        .map(String::trim)
        .filter(String::isNotEmpty)

val artiVersion =
    Regex("""(?ms)\[\[package]]\s*name\s*=\s*"arti-client"\s*version\s*=\s*"([^"]+)"""")
        .find(foxCoreSourceRoot.resolve("Cargo.lock").readText())
        ?.groupValues
        ?.get(1)
        ?: error("Pinned arti-client version is missing from FoxCore Cargo.lock")
val releaseSigningReady =
    !releaseSigningStoreFilePath.isNullOrBlank() &&
        !releaseSigningStorePassword.isNullOrBlank() &&
        !releaseSigningKeyAlias.isNullOrBlank() &&
        !releaseSigningKeyPassword.isNullOrBlank() &&
        releaseSigningStoreFile?.isFile == true
val validateReleaseSigningInputs = tasks.register("validateReleaseSigningInputs") {
    group = "verification"
    description = "Fail early when release signing inputs are incomplete or the configured keystore is missing."

    doLast {
        require(releaseSigningReady) {
            "Release signing is incomplete. Configure " +
                "-Pfoxhole.releaseSigningProperties=<path>, ${defaultReleaseSigningPropertiesFile.absolutePath}, " +
                "or FOXHOLE_RELEASE_* env vars."
        }
        require(releaseSigningStoreFile?.isFile == true) {
            "Release keystore is missing: $releaseSigningStoreFilePath"
        }
    }
}
val publicReleaseBuildConfigFile =
    layout.buildDirectory.file("generated/source/buildConfig/publicRelease/com/foxhole/guard/BuildConfig.java")
val releaseBuildConfigFile =
    layout.buildDirectory.file("generated/source/buildConfig/release/com/foxhole/guard/BuildConfig.java")
val publicReleaseBundleDir = layout.buildDirectory.dir("outputs/bundle/publicRelease")
val publicReleaseMappingFile = layout.buildDirectory.file("outputs/mapping/publicRelease/mapping.txt")
val publicReleaseMergedNativeLibsDir =
    layout.buildDirectory.dir("intermediates/merged_native_libs/publicRelease/mergePublicReleaseNativeLibs/out/lib")
val publicReleaseNativeSymbolsArchive =
    layout.buildDirectory.file("outputs/native-debug-symbols/publicRelease/publicRelease-native-symbols.zip")
val publicReleaseLocaleConfigFile =
    layout.buildDirectory.file("generated/res/localeConfig/publicRelease/xml/_generated_res_locale_config.xml")
val filteredMainAssetsDir = layout.buildDirectory.dir("generated/filteredMainAssets")
val generatedLicenseAssetsDir = layout.buildDirectory.dir("generated/licenseAssets")

fun publicReleaseBundleFile(): File {
    val bundles =
        publicReleaseBundleDir.get().asFile
            .listFiles { file -> file.isFile && file.extension == "aab" }
            .orEmpty()
            .sortedBy { file -> file.name }
    require(bundles.size == 1) {
        "Expected exactly one publicRelease AAB under ${publicReleaseBundleDir.get().asFile}, found " +
            bundles.joinToString { file -> file.name }.ifBlank { "none" }
    }
    return bundles.single()
}

val nativeBootstrapOffline =
    providers.gradleProperty("foxhole.nativeOffline").orNull?.toBooleanStrictOrNull() ?: gradle.startParameter.isOffline

val prepareBundledI2pd = tasks.register("prepareBundledI2pd") {
    val buildI2pdScript = rootProject.file("scripts/build-i2pd.sh")
    val nativeDepsScript = rootProject.file("scripts/native-deps.sh")
    val i2pdPatches = rootProject.fileTree("scripts/patches") { include("i2pd-*.patch") }
    val i2pdLibraries = shippedAndroidAbis.map { abi -> file("src/main/jniLibs/$abi/libi2pd.so") }

    inputs.files(buildI2pdScript, nativeDepsScript)
    inputs.files(i2pdPatches)
    outputs.files(i2pdLibraries)

    doLast {
        require(buildI2pdScript.isFile) { "missing i2pd bootstrap script: ${buildI2pdScript.absolutePath}" }
        val process =
            ProcessBuilder(buildI2pdScript.absolutePath)
                .directory(rootProject.projectDir)
                .also { builder ->
                    builder.environment()["FOXHOLE_NATIVE_OFFLINE"] = if (nativeBootstrapOffline) "1" else "0"
                    builder.environment()["I2PD_ABIS"] = shippedAndroidAbis.joinToString(" ")
                }
                .inheritIO()
                .start()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "i2pd bootstrap script failed with exit code $exitCode" }
        val missing = i2pdLibraries.filterNot(File::isFile)
        require(missing.isEmpty()) {
            "i2pd bootstrap did not produce: ${missing.joinToString { library -> library.path }}"
        }
    }
}

val verifyBundledI2pdHostPaths = tasks.register("verifyBundledI2pdHostPaths") {
    val verifyScript = rootProject.file("scripts/verify-native-host-paths.sh")
    val i2pdLibraries = shippedAndroidAbis.map { abi -> file("src/main/jniLibs/$abi/libi2pd.so") }

    group = "verification"
    description = "Fail when a bundled i2pd binary embeds host-specific build paths."
    dependsOn(prepareBundledI2pd)
    inputs.file(verifyScript)
    inputs.files(i2pdLibraries)

    doLast {
        require(verifyScript.isFile) { "missing native host-path verifier: ${verifyScript.absolutePath}" }
        val command = listOf(verifyScript.absolutePath) + i2pdLibraries.map(File::getAbsolutePath)
        val exitCode =
            ProcessBuilder(command)
                .directory(rootProject.projectDir)
                .inheritIO()
                .start()
                .waitFor()
        check(exitCode == 0) { "bundled i2pd host-path verification failed with exit code $exitCode" }
    }
}

val prepareBundledTorTransports = tasks.register("prepareBundledTorTransports") {
    val buildTransportsScript = rootProject.file("scripts/build-tor-transports.sh")
    val transports =
        shippedAndroidAbis.flatMap { abi ->
            listOf("lyrebird", "conjure-client").map { name ->
                file("src/main/assets/tor/$abi/tor/pluggable_transports/$name")
            }
        }

    inputs.files(buildTransportsScript, rootProject.file("scripts/native-deps.sh"), rootProject.file("scripts/prepare-tor-dependency.py"))
    inputs.files(rootProject.fileTree("config/native"))
    inputs.property("abis", shippedAndroidAbis)
    outputs.files(transports)

    doLast {
        require(buildTransportsScript.isFile) {
            "missing Tor transport build script: ${buildTransportsScript.absolutePath}"
        }
        val process =
            ProcessBuilder(buildTransportsScript.absolutePath)
                .directory(rootProject.projectDir)
                .also {
                    it.environment()["TOR_TRANSPORT_ABIS"] = shippedAndroidAbis.joinToString(" ")
                    it.environment()["FOXHOLE_NATIVE_OFFLINE"] = if (nativeBootstrapOffline) "1" else "0"
                }
                .inheritIO()
                .start()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "Tor transport build script failed with exit code $exitCode" }
        val missing = transports.filterNot(File::isFile)
        require(missing.isEmpty()) {
            "Tor transport build did not produce: ${missing.joinToString { transport -> transport.path }}"
        }
    }
}

val prebuiltFoxCoreDirectory = providers.environmentVariable("FOXCORE_PREBUILT_DIR").orNull?.let(::File)

val prepareFoxCoreNative = tasks.register("prepareFoxCoreNative") {
    group = "build"
    description = "Build the pinned FoxCore Rust JNI library for the explicitly selected Android ABIs."

    inputs.files(
        fileTree(foxCoreSourceRoot) {
            include(
                "Cargo.toml",
                "Cargo.lock",
                "rust-toolchain.toml",
                "crates/**/Cargo.toml",
                "crates/**/*.rs",
                "scripts/android-build.sh",
                "scripts/android-elf-gate.sh",
            )
            exclude("target/**", "android-testapp/**/build/**")
        },
    )
    val expectedLibraries =
        shippedAndroidAbis.map { abi ->
            generatedFoxCoreNativeLibs.map { directory ->
                directory.file("$abi/libfoxhole_native.so")
            }
        }
    outputs.files(expectedLibraries)
    inputs.property("prebuiltCore", prebuiltFoxCoreDirectory?.absolutePath.orEmpty())
    prebuiltFoxCoreDirectory?.let { inputs.dir(it) }

    doLast {
        if (prebuiltFoxCoreDirectory != null) {
            val command = listOf(
                "python3", rootProject.file("scripts/verify-core-release.py").absolutePath,
                foxCoreSourceRoot.absolutePath, prebuiltFoxCoreDirectory.absolutePath, pinnedFoxCoreRevision,
                shippedAndroidAbis.joinToString(" "), generatedFoxCoreNativeLibs.get().asFile.absolutePath,
            )
            check(ProcessBuilder(command).directory(rootProject.projectDir).inheritIO().start().waitFor() == 0) {
                "Prebuilt Core did not match the pinned release manifest"
            }
            check(ProcessBuilder(foxCoreSourceRoot.resolve("scripts/android-elf-gate.sh").absolutePath,
                generatedFoxCoreNativeLibs.get().asFile.absolutePath).inheritIO().start().waitFor() == 0) {
                "Prebuilt Core ELF verification failed"
            }
            return@doLast
        }
        require(foxCoreAndroidBuildScript.isFile) {
            "missing FoxCore Android build script: ${foxCoreAndroidBuildScript.absolutePath}"
        }
        val outputDirectory = generatedFoxCoreNativeLibs.get().asFile
        outputDirectory.mkdirs()
        val process =
            ProcessBuilder(foxCoreAndroidBuildScript.absolutePath)
                .directory(foxCoreSourceRoot)
                .inheritIO()
                .apply {
                    environment()["FOXCORE_JNI_OUTPUT"] = outputDirectory.absolutePath
                    environment()["FOXCORE_ANDROID_ABIS"] = shippedAndroidAbis.joinToString(" ")
                }.start()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "FoxCore Android build failed with exit code $exitCode"
        }
        val missing =
            expectedLibraries
                .map { it.get().asFile }
                .filterNot(File::isFile)
        require(missing.isEmpty()) {
            "FoxCore Android build did not produce: ${missing.joinToString { library -> library.path }}"
        }
    }
}

val verifyFoxCoreJniSeam = tasks.register("verifyFoxCoreJniSeam") {
    group = "verification"
    description = "Compare the shipped FoxCore ELF exports with Java declarations and production Kotlin references."
    dependsOn(prepareFoxCoreNative)

    val seamScript = rootProject.file("scripts/verify-foxcore-jni-seam.sh")
    inputs.file(seamScript)
    inputs.file(rootProject.file("core/runtime/src/main/java/com/foxhole/core/runtime/FoxholeNativeEngine.java"))
    inputs.files(
        fileTree(rootProject.file("core/runtime/src/main/kotlin")) { include("**/*.kt") },
        fileTree(rootProject.file("app/src/main/kotlin")) { include("**/*.kt") },
    )
    inputs.dir(generatedFoxCoreNativeLibs)

    doLast {
        val process =
            ProcessBuilder(
                seamScript.absolutePath,
                generatedFoxCoreNativeLibs.get().asFile.absolutePath,
                foxCoreSourceRoot.absolutePath,
            ).directory(rootProject.projectDir)
                .inheritIO()
                .start()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "FoxCore JNI seam verification failed with exit code $exitCode" }
    }
}

val preparePrivacyNativeLibs = tasks.register<Sync>("preparePrivacyNativeLibs") {
    dependsOn(prepareBundledTorTransports)
    from("src/main/assets/tor") {
        include("*/tor/pluggable_transports/lyrebird")
        include("*/tor/pluggable_transports/conjure-client")
        includeEmptyDirs = false
        eachFile {
            val abi = relativePath.segments.first()
            path =
                when (name) {
                    "lyrebird" -> "$abi/liblyrebird.so"
                    "conjure-client" -> "$abi/libconjure_client.so"
                    else -> "$abi/$name"
                }
        }
    }
    // i2pd ships as a lib*.so executable because Android only executes from nativeLibraryDir.
    from("src/main/assets/i2pd") {
        include("*/libi2pd.so")
        includeEmptyDirs = false
        eachFile {
            val abi = relativePath.segments.first()
            path = "$abi/libi2pd.so"
        }
    }
    into(layout.buildDirectory.dir("generated/privacyNativeLibs"))
}

val prepareBundledLicenseAssets = tasks.register("prepareBundledLicenseAssets") {
    val generator = rootProject.file("scripts/generate-license-assets.py")
    dependsOn(prepareBundledI2pd)
    dependsOn(prepareBundledTorTransports)
    inputs.files(
        generator,
        rootProject.file("LICENSE"),
        rootProject.file("THIRD_PARTY_NOTICES.md"),
        rootProject.file("scripts/native-deps.sh"),
        rootProject.file("scripts/prepare-tor-dependency.py"),
        fileTree(rootProject.file("config/native")),
        rootProject.file("config/foxcore-revision.txt"),
        rootProject.file("gradle/libs.versions.toml"),
        fileTree(rootProject.file("third_party/fonts")),
        fileTree(rootProject.file("third_party/flags")),
        fileTree(rootProject.file("third_party/icons")),
        fileTree(rootProject.file("third_party/licenses")),
        fileTree(rootProject.file("third_party/tor-config")),
        rootProject.file("third_party/i2pd.version"),
        rootProject.file("third_party/i2pd/LICENSE"),
        foxCoreSourceRoot.resolve("LICENSE"),
        foxCoreSourceRoot.resolve("THIRD_PARTY_NOTICES.md"),
        foxCoreSourceRoot.resolve("sbom/foxcore-aarch64-linux-android.cdx.json"),
    )
    inputs.property("androidAbis", shippedAndroidAbis.joinToString(" "))
    shippedAndroidAbis.forEach { abi ->
        inputs.files(
            rootProject.file("app/src/main/assets/tor/$abi/tor/pluggable_transports/lyrebird"),
            rootProject.file("app/src/main/assets/tor/$abi/tor/pluggable_transports/conjure-client"),
        )
    }
    outputs.dir(generatedLicenseAssetsDir)

    doLast {
        val output = generatedLicenseAssetsDir.get().asFile
        delete(output)
        val buildLog = layout.buildDirectory.file("reports/native/license-assets.log").get().asFile
        buildLog.parentFile.mkdirs()
        val process =
            ProcessBuilder(
                "python3",
                generator.absolutePath,
                "--output",
                output.absolutePath,
                "--foxcore-root",
                foxCoreSourceRoot.absolutePath,
                "--android-abis",
                shippedAndroidAbis.joinToString(" "),
            ).directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .redirectOutput(buildLog)
                .start()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "license asset generator failed with exit code $exitCode:\n${buildLog.readText().takeLast(8_000)}"
        }
    }
}

val prepareFilteredMainAssets = tasks.register<Sync>("prepareFilteredMainAssets") {
    dependsOn(prepareBundledTorTransports)
    dependsOn(prepareBundledLicenseAssets)
    from("src/main/assets") {
        exclude("tor/**/tor/pluggable_transports/conjure-client")
        exclude("tor/**/tor/pluggable_transports/lyrebird")
        exclude("i2pd/**/libi2pd.so")
        exclude("tor/**/data/geoip")
        exclude("tor/**/data/geoip6")
        exclude("maps/ne_50m_admin_0_countries.geojson")
        val shipped = shippedAndroidAbis.toSet()
        exclude { candidate ->
            val segments = candidate.relativePath.segments
            segments.size > 1 && segments[0] == "tor" && segments[1] !in shipped
        }
    }
    from(generatedLicenseAssetsDir) {
        into("licenses")
    }
    into(filteredMainAssetsDir)
}

tasks.matching { task -> task.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(preparePrivacyNativeLibs)
    dependsOn(verifyBundledI2pdHostPaths)
    dependsOn(prepareFoxCoreNative)
}

tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(verifyBundledI2pdHostPaths)
    dependsOn(prepareFoxCoreNative)
}

tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareFilteredMainAssets)
}

tasks.matching { task -> "Lint" in task.name || "lint" in task.name }.configureEach {
    dependsOn(prepareFilteredMainAssets)
}

val verifyReleaseContainsNativeRuntime =
    tasks.register<VerifyBundledNativeRuntimeInReleaseApkTask>("verifyReleaseContainsNativeRuntime") {
    dependsOn("assembleRelease")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
    expectedAbis.set(shippedAndroidAbis)
}

val verifyReleaseContainsBaselineProfile = tasks.register<VerifyReleaseBaselineProfileInApkTask>("verifyReleaseContainsBaselineProfile") {
    dependsOn("assembleRelease")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
}

val verifyReleaseContainsLicenseAssets =
    tasks.register<VerifyBundledLicenseAssetsInReleaseApkTask>("verifyReleaseContainsLicenseAssets") {
        dependsOn("assembleRelease")
        apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
    }

val verifyReleaseBuildConfigDefaults = tasks.register("verifyReleaseBuildConfigDefaults") {
    dependsOn("generateReleaseBuildConfig")
    inputs.file(releaseBuildConfigFile)

    doLast {
        val content = releaseBuildConfigFile.get().asFile.readText()
        require(!enableReleaseProbe) {
            "foxhole.releaseProbe is only supported by internalRelease; public release builds must keep probes off."
        }
        require("public static final boolean ALLOW_INSECURE_TLS_BY_DEFAULT = false;" in content) {
            "release BuildConfig must set ALLOW_INSECURE_TLS_BY_DEFAULT=false"
        }
        require("public static final boolean ENABLE_DIAGNOSTIC_LOGCAT = false;" in content) {
            "release BuildConfig must set ENABLE_DIAGNOSTIC_LOGCAT=false"
        }
        require("public static final boolean ENABLE_STRICT_MODE = false;" in content) {
            "release BuildConfig must set ENABLE_STRICT_MODE=false"
        }
    }
}

val verifyPublicReleasePrivacy = tasks.register("verifyPublicReleasePrivacy") {
    group = "verification"
    description = "Fail when publicRelease can expose diagnostics, insecure TLS, or strict-mode debug flags."

    dependsOn("generatePublicReleaseBuildConfig")
    inputs.file(publicReleaseBuildConfigFile)

    doLast {
        require(!enableReleaseProbe) {
            "foxhole.releaseProbe may only be used with internalRelease, never with publicRelease."
        }
        val content = publicReleaseBuildConfigFile.get().asFile.readText()
        require("public static final boolean ENABLE_DIAGNOSTIC_LOGCAT = false;" in content) {
            "publicRelease BuildConfig must set ENABLE_DIAGNOSTIC_LOGCAT=false"
        }
        require("public static final boolean ALLOW_INSECURE_TLS_BY_DEFAULT = false;" in content) {
            "publicRelease BuildConfig must set ALLOW_INSECURE_TLS_BY_DEFAULT=false"
        }
        require("public static final boolean ENABLE_STRICT_MODE = false;" in content) {
            "publicRelease BuildConfig must set ENABLE_STRICT_MODE=false"
        }
    }
}

val verifyPublicReleaseNativeInventory = tasks.register("verifyPublicReleaseNativeInventory") {
    group = "verification"
    description = "Fail when the public release bundle contains unknown native or executable assets."

    dependsOn("bundlePublicRelease")
    inputs.dir(publicReleaseBundleDir)

    doLast {
        val bundleFile = publicReleaseBundleFile()
        require(bundleFile.isFile) { "publicRelease AAB is missing: ${bundleFile.absolutePath}" }
        val entries = ZipFile(bundleFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val nativeEntries = entries.filter { entry -> entry.endsWith(".so") }
        val nativeLibraries = nativeEntries.map { entry -> entry.substringAfterLast('/') }.toSet()
        val allowedNativeLibraries =
            setOf(
                "libandroidx.graphics.path.so",
                "libconjure_client.so",
                "libdatastore_shared_counter.so",
                "libfoxhole_native.so",
                "libi2pd.so",
                "libjnidispatch.so",
                "liblyrebird.so",
                "libsodium.so",
                "libsqlcipher.so",
            )
        val requiredRuntimeLibraries =
            setOf(
                "libconjure_client.so",
                "libfoxhole_native.so",
                "libi2pd.so",
                "liblyrebird.so",
            )
        val unknownNativeLibraries = nativeLibraries - allowedNativeLibraries
        require(unknownNativeLibraries.isEmpty()) {
            "publicRelease AAB contains unknown native libraries: ${unknownNativeLibraries.joinToString()}"
        }
        val missingRuntimeLibraries = requiredRuntimeLibraries - nativeLibraries
        require(missingRuntimeLibraries.isEmpty()) {
            "publicRelease AAB is missing bundled runtime libraries: ${missingRuntimeLibraries.joinToString()}"
        }
        val executableAssets =
            entries.filter { entry ->
                entry.startsWith("base/assets/") &&
                    (entry.endsWith(".so") || entry.endsWith(".dex") || entry.endsWith(".jar"))
            }
        require(executableAssets.isEmpty()) {
            "publicRelease AAB must not ship executable assets outside native lib packaging: " +
                executableAssets.joinToString()
        }
    }
}

val collectPublicReleaseNativeSymbols = tasks.register<Zip>("collectPublicReleaseNativeSymbols") {
    group = "build"
    description = "Archive merged publicRelease native libraries before strip for release symbol retention."

    dependsOn("mergePublicReleaseNativeLibs")
    from(publicReleaseMergedNativeLibsDir)
    archiveFileName.set("publicRelease-native-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/publicRelease"))
}

val verifyReleaseSbom = tasks.register("verifyReleaseSbom") {
    group = "verification"
    description = "Fail when the release dependency inventory is missing or omits release-critical coordinates."

    if (rootProject.tasks.names.contains("cyclonedxBom")) {
        dependsOn(rootProject.tasks.named("cyclonedxBom"))
    }
    val sbomJson = rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json")
    inputs.file(sbomJson).optional()

    doLast {
        require(rootProject.tasks.names.contains("cyclonedxBom")) {
            "Release SBOM task is unavailable. Run public release preflight with -Pfoxhole.sbom=true."
        }
        val sbomFile = sbomJson.get().asFile
        require(sbomFile.isFile) { "Release SBOM is missing: ${sbomFile.absolutePath}" }
        val sbomDocument = JsonSlurper().parse(sbomFile) as? Map<*, *>
        val sbomMetadata = sbomDocument?.get("metadata") as? Map<*, *>
        val sbomComponent = sbomMetadata?.get("component") as? Map<*, *>
        require(sbomComponent?.get("name") == "foxhole-android") {
            "Release SBOM metadata component must be foxhole-android"
        }
        val sbomVersion = sbomComponent?.get("version")
        require(sbomVersion == rootProject.version.toString()) {
            "Release SBOM metadata version $sbomVersion must match ${rootProject.version}"
        }
        val rawComponents = requireNotNull(sbomDocument?.get("components") as? List<*>) {
            "Release SBOM has no dependency component array"
        }
        require(rawComponents.isNotEmpty()) { "Release SBOM has no dependency components" }
        val inventory =
            rawComponents.mapIndexed { index, rawComponent ->
                val component = rawComponent as? Map<*, *>
                    ?: error("Release SBOM component $index is not an object")
                val reference = (component["bom-ref"] as? String).orEmpty()
                val name = (component["name"] as? String).orEmpty()
                val version = (component["version"] as? String).orEmpty()
                val purl = (component["purl"] as? String).orEmpty()
                require(reference.isNotBlank() && name.isNotBlank() && version.isNotBlank() && purl.isNotBlank()) {
                    "Release SBOM component $index is missing bom-ref, name, version, or purl"
                }
                reference to Triple(name, version, purl)
            }
        require(inventory.map { (reference, _) -> reference }.distinct().size == inventory.size) {
            "Release SBOM contains duplicate component references"
        }
        val requiredCoordinates =
            mapOf(
                "jna" to ("5.19.1" to "pkg:maven/net.java.dev.jna/jna@5.19.1"),
                "lazysodium-android" to ("5.2.0" to "pkg:maven/com.goterl/lazysodium-android@5.2.0"),
                "okhttp" to ("5.4.0" to "pkg:maven/com.squareup.okhttp3/okhttp@5.4.0"),
                "sqlcipher-android" to ("4.17.0" to "pkg:maven/net.zetetic/sqlcipher-android@4.17.0"),
                "zxing-android-embedded" to
                    ("4.3.0" to "pkg:maven/com.journeyapps/zxing-android-embedded@4.3.0"),
            )
        requiredCoordinates.forEach { (requiredName, required) ->
            val (requiredVersion, requiredPurl) = required
            require(
                inventory.any { (_, coordinate) ->
                    val (name, version, purl) = coordinate
                    name == requiredName && version == requiredVersion && purl.startsWith(requiredPurl)
                },
            ) {
                "Release SBOM does not include required coordinate: $requiredName@$requiredVersion"
            }
        }
    }
}

val publicReleasePreflight = tasks.register("publicReleasePreflight") {
    group = "verification"
    description = "Verify public release signing, privacy flags, bundle contents, mapping, symbols, locale config, and SBOM."

    dependsOn(
        "bundlePublicRelease",
        collectPublicReleaseNativeSymbols,
        verifyBundledI2pdHostPaths,
        verifyPublicReleasePrivacy,
        verifyPublicReleaseNativeInventory,
        verifyReleaseSbom,
    )
    inputs.dir(publicReleaseBundleDir)
    inputs.file(publicReleaseBuildConfigFile)
    inputs.file(publicReleaseMappingFile)
    inputs.file(publicReleaseNativeSymbolsArchive)
    inputs.file(publicReleaseLocaleConfigFile)

    doLast {
        require(releaseSigningReady) {
            "publicRelease requires release signing. Configure " +
                "-Pfoxhole.releaseSigningProperties=<path>, ${defaultReleaseSigningPropertiesFile.absolutePath}, " +
                "or FOXHOLE_RELEASE_* env vars."
        }
        require(releaseSigningStoreFile?.isFile == true) {
            "publicRelease keystore is missing: $releaseSigningStoreFilePath"
        }
        val bundleFile = publicReleaseBundleFile()
        require(bundleFile.isFile) { "publicRelease AAB is missing: ${bundleFile.absolutePath}" }
        val bundleEntries = ZipFile(bundleFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        val signatureEntries =
            bundleEntries.filter { entry ->
                entry.startsWith("META-INF/") &&
                    (entry.endsWith(".RSA") || entry.endsWith(".DSA") || entry.endsWith(".EC"))
            }
        require(signatureEntries.isNotEmpty()) {
            "publicRelease AAB is not signed with a JAR signature block."
        }
        require(publicApplicationId == "com.foxhole.guard") {
            "publicRelease applicationId must be com.foxhole.guard, was $publicApplicationId"
        }
        val versionCode = requireNotNull(android.defaultConfig.versionCode) { "publicRelease versionCode is missing" }
        require(lastUploadedVersionCodeProvided) {
            "publicReleasePreflight needs -Pfoxhole.lastUploadedVersionCode=<latest published public code> so the " +
                "monotonicity check verifies against the real uploaded code instead of the hardcoded default. " +
                "Resolve it from the latest published update manifest before building."
        }
        require(versionCode > lastUploadedPublicVersionCode) {
            "publicRelease versionCode $versionCode must be greater than uploaded $lastUploadedPublicVersionCode"
        }
        val versionName = requireNotNull(android.defaultConfig.versionName) { "publicRelease versionName is missing" }
        require(rootProject.version.toString() == versionName) {
            "Gradle/SBOM version ${rootProject.version} must match publicRelease versionName $versionName"
        }
        require(!versionName.contains("debug", ignoreCase = true)) {
            "publicRelease versionName must not contain Debug: $versionName"
        }
        require(!versionName.contains("internal", ignoreCase = true)) {
            "publicRelease versionName must not contain internal: $versionName"
        }
        require(!versionName.contains("-dev", ignoreCase = true)) {
            "publicRelease versionName must not use the developer-preview line: $versionName"
        }
        val buildConfig = publicReleaseBuildConfigFile.get().asFile.readText()
        require("public static final boolean ENABLE_DIAGNOSTIC_LOGCAT = false;" in buildConfig) {
            "publicRelease must keep diagnostic logcat disabled."
        }
        val mappingFile = publicReleaseMappingFile.get().asFile
        require(mappingFile.isFile && mappingFile.length() > 0L) {
            "publicRelease mapping file is missing or empty: ${mappingFile.absolutePath}"
        }
        val nativeSymbolsArchive = publicReleaseNativeSymbolsArchive.get().asFile
        require(nativeSymbolsArchive.isFile && nativeSymbolsArchive.length() > 0L) {
            "publicRelease native symbol archive is missing or empty: ${nativeSymbolsArchive.absolutePath}"
        }
        val localeConfig = publicReleaseLocaleConfigFile.get().asFile
        require(localeConfig.isFile && "<locale-config" in localeConfig.readText() && "<locale " in localeConfig.readText()) {
            "publicRelease generated locale config is missing or invalid: ${localeConfig.absolutePath}"
        }
    }
}

val verifyReleaseJniSurface = tasks.register("verifyReleaseJniSurface") {
    group = "verification"
    description = "Fail when a member the native runtime calls by name did not survive minification."

    doLast {
        val jniMembers = listOf("protectSocket", "signingDigestForPackage")
        val apks =
            layout.buildDirectory.dir("outputs/apk/release").get().asFile
                .listFiles { file -> file.isFile && file.extension == "apk" }
                .orEmpty()
        require(apks.isNotEmpty()) { "no release APK to verify" }
        apks.forEach { apk ->
            val found = mutableSetOf<String>()
            ZipFile(apk).use { zip ->
                zip.entries()
                    .toList()
                    .filter { entry -> entry.name.startsWith("classes") && entry.name.endsWith(".dex") }
                    .forEach { entry ->
                        val text = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                        jniMembers.filterTo(found) { member -> member in text }
                    }
            }
            val missing = jniMembers - found
            require(missing.isEmpty()) {
                "minification removed or renamed ${missing.joinToString()} in ${apk.name}; the native " +
                    "runtime resolves it by name and the build cannot protect its sockets without it"
            }
        }
    }
}

tasks.matching { task -> task.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseContainsNativeRuntime)
    finalizedBy(verifyReleaseContainsBaselineProfile)
    finalizedBy(verifyReleaseContainsLicenseAssets)
    finalizedBy(verifyReleaseBuildConfigDefaults)
    finalizedBy(verifyReleaseJniSurface)
}

tasks.named("check") {
    dependsOn(verifyReleaseBuildConfigDefaults)
    dependsOn(verifyFoxCoreJniSeam)
}

verifyReleaseBuildConfigDefaults.configure {
    dependsOn(verifyFoxCoreJniSeam)
}

tasks.matching { task ->
    task.name in
        setOf(
            "lintAnalyzeDebug",
            "lintAnalyzeDebugUnitTest",
            "lintAnalyzeDebugAndroidTest",
            "lintAnalyzeRelease",
            "lintAnalyzeInternalRelease",
            "lintAnalyzePublicRelease",
        )
}.configureEach {
    dependsOn("kspDebugKotlin", "kspReleaseKotlin", "kspInternalReleaseKotlin", "kspPublicReleaseKotlin")
}

android {
    namespace = "com.foxhole.guard"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = publicApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = 122
        versionName = project.version.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"$appUpdateChannel\"")
        buildConfigField("long", "UPDATE_FLOOR_VERSION_CODE", "${appUpdateFloorVersionCode}L")
        buildConfigField("long", "UPDATE_SUPPORTED_UNTIL_EPOCH_DAY", "${appUpdateSupportedUntilEpochDay}L")
        buildConfigField("String", "DEFAULT_SUPPORT_BOT_HANDLE", "\"@foxhole_repo_support_bot\"")
        buildConfigField("String", "FOXCORE_SOURCE_VERSION", "\"$foxCoreVersion\"")
        buildConfigField("String", "ARTI_VERSION", "\"$artiVersion\"")
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseSigningStoreFile
                storePassword = releaseSigningStorePassword
                keyAlias = releaseSigningKeyAlias
                keyPassword = releaseSigningKeyPassword
                storeType = releaseSigningStoreType
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-Debug"
            manifestPlaceholders["appLabel"] = "FoxHole Guard Debug"
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "true")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "true")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", enableStrictMode.toString())
            if (!enableAbiSplitApks) {
                ndk {
                    abiFilters += shippedAndroidAbis
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "false")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "false")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "false")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            if (!enableAbiSplitApks) {
                ndk {
                    abiFilters += shippedAndroidAbis
                    debugSymbolLevel = "SYMBOL_TABLE"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("internalRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-Internal"
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "false")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", if (enableReleaseProbe) "true" else "false")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "false")
        }
        create("publicRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "false")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "false")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ru")
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(layout.buildDirectory.dir("generated/privacyNativeLibs").get().asFile.path)
            jniLibs.directories.add(generatedFoxCoreNativeLibs.get().asFile.path)
            assets.directories.clear()
            assets.directories.add(filteredMainAssetsDir.get().asFile.path)
        }
        getByName("androidTest") {
            assets.directories.add(file("schemas").path)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols +=
            listOf(
                "**/libandroidx.graphics.path.so",
                "**/libconjure_client.so",
                "**/libdatastore_shared_counter.so",
                "**/libfoxhole_native.so",
                "**/libi2pd.so",
                "**/liblyrebird.so",
                "**/libsqlcipher.so",
            )
    }

    lint {
        disable += "ChromeOsAbiSupport"
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    if (enableAbiSplitApks) {
        splits {
            abi {
                isEnable = true
                reset()
                include(*shippedAndroidAbis.toTypedArray())
                isUniversalApk = false
            }
        }
    }

    bundle {
        abi {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants { variant ->
        if (appUpdateChannel == "github") {
            variant.sources.manifests.addStaticManifestFile("src/githubUpdater/AndroidManifest.xml")
        }
    }
}

val composeCompilerMetricsDir = layout.buildDirectory.dir("reports/compose/metrics")
val composeCompilerReportsDir = layout.buildDirectory.dir("reports/compose/reports")

composeCompiler {
    includeComposeMappingFile.set(false)
    includeTraceMarkers.set(true)
    metricsDestination.set(composeCompilerMetricsDir)
    reportsDestination.set(composeCompilerReportsDir)
}

val verifyComposeCompilerReports = tasks.register("verifyComposeCompilerReports") {
    group = "verification"
    description = "Fail when Compose compiler metrics/reports are not produced for debug UI builds."

    dependsOn("compileDebugKotlin")
    inputs.dir(composeCompilerMetricsDir).optional()
    inputs.dir(composeCompilerReportsDir).optional()

    doLast {
        val metricFiles =
            composeCompilerMetricsDir.get().asFile
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "json" }
                .toList()
        val reportFiles =
            composeCompilerReportsDir.get().asFile
                .walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("txt", "csv", "json") }
                .toList()
        require(metricFiles.any { file -> file.name == "module.json" || file.name.endsWith("-module.json") }) {
            "Compose compiler metrics did not include a module.json file in ${composeCompilerMetricsDir.get().asFile}"
        }
        require(reportFiles.isNotEmpty()) {
            "Compose compiler reports were not produced in ${composeCompilerReportsDir.get().asFile}"
        }
    }
}

tasks.matching { task -> task.name == "compileDebugKotlin" }.configureEach {
    outputs.upToDateWhen {
        composeCompilerMetricsDir.get().asFile.isDirectory &&
            composeCompilerReportsDir.get().asFile.isDirectory
    }
}

tasks.named("check") {
    dependsOn(verifyComposeCompilerReports)
}

jacoco {
    toolVersion = "0.8.15"
}

val detektCli = configurations.create("detektCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val detektPlugins = configurations.create("detektPlugins") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Run detekt analysis with the stable CLI."

    val detektReportDir = layout.buildDirectory.dir("reports/detekt")
    val detektConfig = rootProject.file("config/detekt/detekt.yml")
    val detektBaseline = rootProject.file("config/detekt/baseline.xml")
    val detektSources =
        listOf(
            "src/main/kotlin",
            "src/test/kotlin",
        ).map(::file) +
            listOf("importer", "model", "network", "profile", "runtime", "sentinel")
                .flatMap { module ->
                    listOf(
                        rootProject.file("core/$module/src/main/kotlin"),
                        rootProject.file("core/$module/src/test/kotlin"),
                    )
                }
                .filter(File::isDirectory)

    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli

    inputs.files(detektSources)
    inputs.files(detektConfig, detektBaseline)
    outputs.dir(detektReportDir)

    args(
        "--build-upon-default-config",
        "--config",
        detektConfig.path,
        "--baseline",
        detektBaseline.path,
        "--input",
        detektSources.joinToString(separator = ",") { source -> source.path },
    )
    doFirst {
        args(
            "--plugins",
            detektPlugins.files.joinToString(separator = ",") { plugin -> plugin.path },
        )
    }
    listOf("html", "md", "sarif", "txt", "xml").forEach { reportId ->
        args(
            "--report",
            "$reportId:${detektReportDir.get().file("detekt.$reportId").asFile.path}",
        )
    }
}

tasks.register<JavaExec>("detektFormat") {
    group = "verification"
    description = "Auto-correct detekt formatting findings (ktlint-backed) with the stable CLI."

    val detektConfig = rootProject.file("config/detekt/detekt.yml")
    val detektSources =
        listOf(
            "src/main/kotlin",
            "src/test/kotlin",
        ).map(::file) +
            listOf("importer", "model", "network", "profile", "runtime", "sentinel")
                .flatMap { module ->
                    listOf(
                        rootProject.file("core/$module/src/main/kotlin"),
                        rootProject.file("core/$module/src/test/kotlin"),
                    )
                }
                .filter(File::isDirectory)

    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli

    args(
        "--build-upon-default-config",
        "--auto-correct",
        "--config",
        detektConfig.path,
        "--input",
        detektSources.joinToString(separator = ",") { source -> source.path },
    )
    doFirst {
        args(
            "--plugins",
            detektPlugins.files.joinToString(separator = ",") { plugin -> plugin.path },
        )
    }
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("updateDetektBaseline") {
    group = "verification"
    description = "Regenerate the detekt baseline with the stable CLI."

    val detektConfig = rootProject.file("config/detekt/detekt.yml")
    val detektBaseline = rootProject.file("config/detekt/baseline.xml")
    val detektSources =
        listOf(
            "src/main/kotlin",
            "src/test/kotlin",
        ).map(::file) +
            listOf("importer", "model", "network", "profile", "runtime", "sentinel")
                .flatMap { module ->
                    listOf(
                        rootProject.file("core/$module/src/main/kotlin"),
                        rootProject.file("core/$module/src/test/kotlin"),
                    )
                }
                .filter(File::isDirectory)

    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli

    inputs.files(detektSources)
    inputs.file(detektConfig)
    outputs.file(detektBaseline)

    args(
        "--build-upon-default-config",
        "--config",
        detektConfig.path,
        "--baseline",
        detektBaseline.path,
        "--create-baseline",
        "--input",
        detektSources.joinToString(separator = ",") { source -> source.path },
    )
    doFirst {
        args(
            "--plugins",
            detektPlugins.files.joinToString(separator = ",") { plugin -> plugin.path },
        )
    }
}

val verifyDetektBaseline = tasks.register("verifyDetektBaseline") {
    group = "verification"
    description = "Fail when detekt baseline changes without an intentional threshold update."

    val detektBaseline = rootProject.file("config/detekt/baseline.xml")
    val expectedBaselineIssues = 0
    inputs.file(detektBaseline)

    doLast {
        val issueCount = "<ID>".toRegex().findAll(detektBaseline.readText()).count()
        require(issueCount == expectedBaselineIssues) {
            "Detekt baseline has $issueCount issues; expected $expectedBaselineIssues. Update the threshold with an intentional baseline change."
        }
    }
}

tasks.named("detekt") {
    finalizedBy(verifyDetektBaseline)
}

tasks.named("check") {
    dependsOn("detekt")
    dependsOn("compileDebugAndroidTestKotlin")
}

val jacocoExcludes =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "**/*Preview*.*",
        "**/*ComposableSingletons*.*",
        "**/*\$serializer.*",
        "**/*_Impl*.*",
        "**/*Dao_Impl*.*",
        "**/*Database_Impl*.*",
    )

val jacocoCoreModuleClassDirectories =
    files(
        fileTree(rootDir.resolve("core/network/build/classes/kotlin/main")) {
            exclude(jacocoExcludes)
        },
        fileTree(rootDir.resolve("core/runtime/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(jacocoExcludes)
        },
        fileTree(rootDir.resolve("core/runtime/build/tmp/kotlin-classes/debug")) {
            exclude(jacocoExcludes)
        },
    )

val jacocoCoreModuleSourceDirectories =
    files(
        rootDir.resolve("core/network/src/main/kotlin"),
        rootDir.resolve("core/runtime/src/main/kotlin"),
    )

val jacocoCoreModuleExecutionData =
    fileTree(rootDir) {
        include(
            "core/network/build/jacoco/test.exec",
            "core/runtime/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            "core/runtime/build/jacoco/testDebugUnitTest.exec",
        )
    }

val jacocoDebugClassDirectories =
    files(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            exclude(jacocoExcludes)
        },
        jacocoCoreModuleClassDirectories,
    )

val jacocoReleaseCriticalClassDirectories =
    files(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            include("com/foxhole/guard/core/**", "com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            include("com/foxhole/guard/core/**", "com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            include("com/foxhole/guard/core/**", "com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            include("com/foxhole/guard/core/**", "com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
        fileTree(rootDir.resolve("core/runtime/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            include("com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
        fileTree(rootDir.resolve("core/runtime/build/tmp/kotlin-classes/debug")) {
            include("com/foxhole/core/runtime/**")
            exclude(jacocoExcludes)
        },
    )

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest", ":core:network:test", ":core:runtime:testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(jacocoDebugClassDirectories)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"), jacocoCoreModuleSourceDirectories)
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
        jacocoCoreModuleExecutionData,
    )
    doLast {
        val xmlReport = reports.xml.outputLocation.get().asFile
        require(xmlReport.readText().contains("<counter ")) {
            "Jacoco debug unit test report did not include coverage counters: ${xmlReport.absolutePath}"
        }
    }
}

val verifyJacocoFocusedCoverage = tasks.register("verifyJacocoFocusedCoverage") {
    group = "verification"
    description = "Fail when release-critical unit coverage falls below focused package thresholds."

    dependsOn("jacocoDebugUnitTestReport")
    val xmlReport = layout.buildDirectory.file("reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml")
    inputs.file(xmlReport)

    doLast {
        val reportText = xmlReport.get().asFile.readText()
        val packages =
            Regex("""<package name="([^"]+)">([\s\S]*?)</package>""")
                .findAll(reportText)
                .associate { match -> match.groupValues[1] to match.groupValues[2] }
        fun instructionCoverageForPrefixes(prefixes: List<String>): Double {
            var missed = 0L
            var covered = 0L
            packages
                .filterKeys { packageName -> prefixes.any(packageName::startsWith) }
                .values
                .forEach { packageXml ->
                    Regex("""<counter type="INSTRUCTION" missed="(\d+)" covered="(\d+)"[^>]*/>""")
                        .findAll(packageXml)
                        .lastOrNull()
                        ?.let { counter ->
                            missed += counter.groupValues[1].toLong()
                            covered += counter.groupValues[2].toLong()
                        }
                }
            val total = missed + covered
            require(total > 0L) {
                "No Jacoco instruction counters found for package prefixes: ${prefixes.joinToString()}"
            }
            return covered.toDouble() / total.toDouble()
        }

        listOf(
            Triple("traffic core instruction coverage", listOf("com/foxhole/guard/traffic"), 0.60),
            Triple(
                "network core instruction coverage",
                listOf("com/foxhole/core/network"),
                0.40,
            ),
            Triple(
                "runtime vpn instruction coverage",
                listOf("com/foxhole/core/runtime"),
                0.28,
            ),
        ).forEach { (label, prefixes, minimum) ->
            val actual = instructionCoverageForPrefixes(prefixes)
            require(actual + 1e-9 >= minimum) {
                "$label is ${"%.2f".format(actual * 100)}%; minimum is ${"%.2f".format(minimum * 100)}%"
            }
        }
    }
}

tasks.register<JacocoCoverageVerification>("jacocoDebugUnitTestCoverageVerification") {
    dependsOn("jacocoDebugUnitTestReport", "verifyRequiredBehaviorTests", verifyJacocoFocusedCoverage)
    classDirectories.setFrom(jacocoReleaseCriticalClassDirectories)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"), jacocoCoreModuleSourceDirectories)
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
        jacocoCoreModuleExecutionData,
    )
    violationRules {
        rule {
            limit {
                minimum = "0.215".toBigDecimal()
            }
        }
    }
}

tasks.register("verifyRequiredBehaviorTests") {
    dependsOn("testDebugUnitTest", ":core:model:test", ":core:network:test", ":core:runtime:testDebugUnitTest")
    doLast {
        val requiredSuites =
            listOf(
                "com.foxhole.guard.core.data.BoundedPublicHttpFetchTest",
                "com.foxhole.guard.core.data.LocalDataRepositoryTest",
                "com.foxhole.guard.core.data.ProfileDatabaseMigrationsTest",
                "com.foxhole.guard.core.data.ProfileImportPayloadLimitsTest",
                "com.foxhole.guard.core.data.ProfileInsecureTlsSupportTest",
                "com.foxhole.guard.core.data.ProtocolOptionRefreshSelectionTest",
                "com.foxhole.guard.core.data.ProfileSecretMutationSupportTest",
                "com.foxhole.guard.core.data.SubscriptionFetchUseCaseTest",
                "com.foxhole.core.network.SubscriptionCertificateTrustTest",
                "com.foxhole.core.model.DnsRuntimeStatsTest",
                "com.foxhole.core.model.DiagnosticSanitizerTest",
                "com.foxhole.core.model.DiagnosticSanitizerStorageTest",
                "com.foxhole.guard.core.diagnostics.DiagnosticsLoggerTest",
                "com.foxhole.guard.core.diagnostics.DiagnosticsSessionStoreTest",
                "com.foxhole.core.runtime.network.IpInfoRepositoryTest",
                "com.foxhole.core.network.PublicUrlPolicyTest",
                "com.foxhole.guard.core.settings.SettingsRepositoryTest",
                "com.foxhole.core.runtime.TorGeoIpCountryResolverTest",
                "com.foxhole.guard.traffic.TrafficMapRepositoryAggregationTest",
                "com.foxhole.guard.traffic.TrafficMapRepositoryStateTest",
                "com.foxhole.guard.BuildConfigDiagnosticsTest",
                "com.foxhole.guard.ReleaseEngineeringContractTest",
                "com.foxhole.guard.ui.cli.home.CliTerminalStateTest",
                "com.foxhole.guard.ui.cli.settings.CliDataScreenContractTest",
                "com.foxhole.guard.ui.RouteStateIsolationTest",
                "com.foxhole.guard.ui.cli.stats.CliStatsOverviewTest",
                "com.foxhole.guard.ui.cli.map.CliRouteModeTest",
                "com.foxhole.core.runtime.AppOwnedRequestPathTest",
                "com.foxhole.core.runtime.BootReceiverTest",
                "com.foxhole.core.runtime.DnsFilterUpdateClientTest",
                "com.foxhole.core.runtime.FoxholeConnectionControllerLatencyTest",
                "com.foxhole.core.runtime.FoxholeConnectionServiceContractTest",
                "com.foxhole.core.runtime.RouteExcludeCompatibilityTest",
                "com.foxhole.core.runtime.RuntimeAutoReconnectPolicyTest",
                "com.foxhole.core.runtime.RuntimeSupervisorCommandQueueTest",
                "com.foxhole.core.runtime.RuntimeSupervisorMailboxCleanupTimeoutTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerRouteRulesTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerInboundsTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerLocalGuardDnsTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerWireguardMtuTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerFingerprintTest",
                "com.foxhole.core.runtime.RuntimeConfigAssemblerManagedRoutesTest",
                "com.foxhole.core.runtime.RuntimeInstanceStoreTest",
                "com.foxhole.core.runtime.RuntimeReloadRecoveryPolicyTest",
                "com.foxhole.core.runtime.RuntimeServiceCommandSupportTest",
                "com.foxhole.core.runtime.RuntimeStartTimeoutPolicyTest",
                "com.foxhole.core.runtime.RuntimeStateReducerTest",
                "com.foxhole.core.runtime.RuntimeStaticSafetyGuardTest",
                "com.foxhole.core.runtime.RuntimeStopSupportTest",
                "com.foxhole.core.runtime.RuntimeSupervisorTest",
                "com.foxhole.core.runtime.RuntimeUpdatePolicyTest",
                "com.foxhole.core.runtime.TunnelValidationPolicyTest",
                "com.foxhole.core.runtime.VpnDnsServerSelectorTest",
                "com.foxhole.core.runtime.VpnRuntimeErrorsTest",
            )
        val resultFiles =
            listOf(
                layout.buildDirectory.dir("test-results/testDebugUnitTest").get().asFile,
                project(":core:model").layout.buildDirectory.dir("test-results/test").get().asFile,
                project(":core:network").layout.buildDirectory.dir("test-results/test").get().asFile,
                project(":core:runtime").layout.buildDirectory.dir("test-results/testDebugUnitTest").get().asFile,
            ).flatMap { resultRoot ->
                fileTree(resultRoot) {
                    include("TEST-*.xml")
                }.files
            }.associateBy { file -> file.name.removePrefix("TEST-").removeSuffix(".xml") }
        val missing = requiredSuites.filterNot(resultFiles::containsKey)
        require(missing.isEmpty()) {
            "Required behavior test suites did not run: ${missing.joinToString()}"
        }
        val invalid =
            requiredSuites.mapNotNull { suite ->
                val xml = requireNotNull(resultFiles[suite]).readText()
                val tests = requireNotNull("tests=\"(\\d+)\"".toRegex().find(xml)) { "Missing tests counter for $suite" }.groupValues[1].toInt()
                val failures = requireNotNull("failures=\"(\\d+)\"".toRegex().find(xml)) { "Missing failures counter for $suite" }.groupValues[1].toInt()
                val errors = requireNotNull("errors=\"(\\d+)\"".toRegex().find(xml)) { "Missing errors counter for $suite" }.groupValues[1].toInt()
                suite.takeIf { tests == 0 || failures != 0 || errors != 0 }
            }
        require(invalid.isEmpty()) {
            "Required behavior test suites failed or reported zero tests: ${invalid.joinToString()}"
        }
    }
}

dependencies {
    implementation(project(":core:importer"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:profile"))
    implementation(project(":core:sentinel"))
    implementation(project(":core:runtime"))

    constraints {
        listOf(
            "protobuf-java",
            "protobuf-javalite",
            "protobuf-java-util",
            "protobuf-kotlin",
        ).forEach { moduleName ->
            implementation("com.google.protobuf:$moduleName:4.35.1") {
                because("Keep optional protobuf transitives on the current patched stable line")
            }
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.biometric)
    implementation(libs.lazysodium.android) {
        artifact { type = "aar" }
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.errorprone.annotations)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.konsist)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    detektCli(libs.detekt.cli)
    detektPlugins(libs.detekt.formatting)
}
