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
import java.util.Properties
import java.util.zip.ZipFile

abstract class VerifyBundledNativeRuntimeInReleaseApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    /**
     * The ABIs this build actually ships.
     *
     * Hard-coding the pair here made the gate assert a release shape rather
     * than verify the one that was built: narrowing `foxhole.abis` turned a
     * correct build into a gate failure, which trains people to widen the gate
     * instead of reading it. It has to come from the same value the packaging
     * uses.
     */
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

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    jacoco
}

val enableAbiSplitApks = providers.gradleProperty("foxhole.splitApks").map(String::toBoolean).orElse(false).get()
val enableReleaseProbe = providers.gradleProperty("foxhole.releaseProbe").map(String::toBoolean).orElse(false).get()
val enableStrictMode = providers.gradleProperty("foxhole.strictMode").map(String::toBoolean).orElse(false).get()
val publicApplicationId = "com.foxhole.guard"
// Whether the real last-uploaded code was actually supplied. Without it the monotonicity check
// below silently compares against the `1` default and enforces nothing beyond `> 1`; the preflight
// therefore requires it to be present so the gate is honest (see publicReleasePreflight).
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
val foxCoreAndroidBuildScript = foxCoreSourceRoot.resolve("scripts/android-build.sh")
val foxCoreVersion =
    Regex("""(?m)^version\s*=\s*"([^"]+)"\s*$""")
        .find(foxCoreSourceRoot.resolve("Cargo.toml").readText())
        ?.groupValues
        ?.get(1)
        ?: error("FoxCore workspace version is missing")
val generatedFoxCoreNativeLibs = layout.buildDirectory.dir("generated/foxCoreNativeLibs")
// What a release ships.
//
// The public beta ships **arm64-v8a only**. Not because 32-bit ARM cannot be
// built — it builds and passes the ELF gate — but because nothing verified it:
// no live traffic, no protocol matrix, no Tor leg ever ran on a 32-bit device
// in this cycle. Shipping an ABI whose only evidence is that it compiled is how
// a first public release earns a review that says "does not connect".
//
// Widening it back is a per-invocation property rather than an environment
// variable: `-Pfoxhole.abis="arm64-v8a armeabi-v7a"` has to be typed on the
// command line, so it cannot quietly become the shape of a release artifact the
// way an exported variable can.
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

// libi2pd.so is built from the pinned i2pd submodule rather than committed. Without this a clean
// checkout would quietly produce an app whose I2P mode is dead; the release native-inventory gate
// now requires the binary, so a miss fails the build instead of shipping.
val prepareBundledI2pd = tasks.register("prepareBundledI2pd") {
    val buildI2pdScript = rootProject.file("scripts/build-i2pd.sh")
    val i2pdLibraries = shippedAndroidAbis.map { abi -> file("src/main/jniLibs/$abi/libi2pd.so") }

    inputs.file(buildI2pdScript)
    outputs.files(i2pdLibraries)

    doLast {
        if (i2pdLibraries.all(File::isFile)) {
            return@doLast
        }
        require(buildI2pdScript.isFile) { "missing i2pd bootstrap script: ${buildI2pdScript.absolutePath}" }
        val process =
            ProcessBuilder(buildI2pdScript.absolutePath)
                .directory(rootProject.projectDir)
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

// The transports are executables Android can only run from nativeLibraryDir, so they cannot be
// fetched at runtime (W^X) and must be compiled here instead of committed.
val prepareBundledTorTransports = tasks.register("prepareBundledTorTransports") {
    val buildTransportsScript = rootProject.file("scripts/build-tor-transports.sh")
    val transports =
        shippedAndroidAbis.flatMap { abi ->
            listOf("lyrebird", "conjure-client").map { name ->
                file("src/main/assets/tor/$abi/tor/pluggable_transports/$name")
            }
        }

    inputs.file(buildTransportsScript)
    outputs.files(transports)

    doLast {
        if (transports.all(File::isFile)) {
            return@doLast
        }
        require(buildTransportsScript.isFile) {
            "missing Tor transport build script: ${buildTransportsScript.absolutePath}"
        }
        val process =
            ProcessBuilder(buildTransportsScript.absolutePath)
                .directory(rootProject.projectDir)
                .also { it.environment()["TOR_TRANSPORT_ABIS"] = shippedAndroidAbis.joinToString(" ") }
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

    doLast {
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

val prepareFilteredMainAssets = tasks.register<Sync>("prepareFilteredMainAssets") {
    dependsOn(prepareBundledTorTransports)
    from("src/main/assets") {
        exclude("tor/**/tor/pluggable_transports/conjure-client")
        exclude("tor/**/tor/pluggable_transports/lyrebird")
        exclude("i2pd/**/libi2pd.so")
        // Arti performs its own directory/certificate handling; the external Tor GeoIP databases
        // and executable are deliberately not part of the application package.
        exclude("tor/**/data/geoip")
        exclude("tor/**/data/geoip6")
        // Build-time input of the map preprocessor; the runtime reads only the
        // *_preprocessed.json (TrafficMapCountryShapes).
        exclude("maps/ne_50m_admin_0_countries.geojson")
        // Tor assets are per-ABI and the tree carries all four (92 MiB). Only
        // the shipped ABIs can ever be read — `TorRuntimeInstaller` resolves the
        // asset path from the device's own ABI — so the rest is dead weight the
        // user downloads. Filtering here rather than deleting them from the tree
        // keeps a wider `-Pfoxhole.abis` working without a git operation.
        val shipped = shippedAndroidAbis.toSet()
        exclude { candidate ->
            val segments = candidate.relativePath.segments
            segments.size > 1 && segments[0] == "tor" && segments[1] !in shipped
        }
    }
    into(filteredMainAssetsDir)
}

tasks.matching { task -> task.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(preparePrivacyNativeLibs)
    dependsOn(prepareBundledI2pd)
    dependsOn(prepareFoxCoreNative)
}

tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(prepareBundledI2pd)
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
                // JNA + libsodium ride in with lazysodium 5.2.0 (the 16KB-page-size fix):
                // libsodium backs the sealed-journal crypto, libjnidispatch.so is JNA's bridge.
                "libjnidispatch.so",
                "liblyrebird.so",
                "libsodium.so",
                "libsqlcipher.so",
            )
        // Every binary the runtime execs or loads. libi2pd.so is built from source by
        // scripts/build-i2pd.sh (it is deliberately not committed), so without it in this gate a
        // clean build would ship an app whose I2P mode is silently dead.
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
    description = "Fail when the release SBOM is missing or does not include release-critical components."

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
        val sbom = sbomFile.readText()
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
        listOf(
            "foxhole-android",
            "sqlcipher-android",
            "okhttp",
            "zxing-android-embedded",
        ).forEach { component ->
            require(component in sbom) { "Release SBOM does not include required component: $component" }
        }
    }
}

val publicReleasePreflight = tasks.register("publicReleasePreflight") {
    group = "verification"
    description = "Verify public release signing, privacy flags, bundle contents, mapping, symbols, locale config, and SBOM."

    dependsOn(
        "bundlePublicRelease",
        collectPublicReleaseNativeSymbols,
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

// The one member of ours FoxCore resolves by NAME through JNI. R8 cannot see that call, so a
// missing keep rule silently shrinks the method away — and because the dialer refuses a socket it
// could not protect, the release build then cannot connect at all. It shipped that way once: the
// bench Pixel recorded `NoSuchMethodError ... protectSocket(I)Z` on v66 release while every debug
// build, unminified, worked. A keep rule alone is not enough of a guard either: the first attempt
// at one named the FILE (RuntimeNativeSupport.kt) instead of the type it declares
// (RuntimeServiceHost), matched nothing, and looked exactly like a fix. So the check reads the
// shrunk output rather than the rule.
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
    finalizedBy(verifyReleaseBuildConfigDefaults)
    finalizedBy(verifyReleaseJniSurface)
}

tasks.named("check") {
    dependsOn(verifyReleaseBuildConfigDefaults)
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

    defaultConfig {
        applicationId = publicApplicationId
        minSdk = 26
        targetSdk = 37
        // Android orders updates by this integer, independently of the visible version name.
        // Release-candidate code 88 is already installed on the physical Pixel. Every subsequent
        // candidate and the public 0.0.1 artifact must remain an in-place upgrade: rolling the
        // integer back would require an uninstall and wipe encrypted settings, statistics and
        // Android special-access grants.
        versionCode = 89
        // The public-facing line starts at 0.0.1. The name must never carry
        // "-dev"/"debug"/"internal" — publicReleasePreflight enforces that.
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
        // Self-update is for GitHub-installed builds only; F-Droid and Play manage their own
        // updates. The F-Droid recipe passes -Pfoxhole.updateChannel=fdroid, which makes the
        // in-app updater inert.
        buildConfigField("String", "UPDATE_CHANNEL", "\"$appUpdateChannel\"")
        buildConfigField("String", "DEFAULT_SUPPORT_BOT_HANDLE", "\"@foxhole_repo_support_bot\"")
        buildConfigField("String", "FOXCORE_SOURCE_VERSION", "\"$foxCoreVersion\"")
        buildConfigField("String", "ARTI_VERSION", "\"$artiVersion\"")
        // Launcher label; the debug build type overrides it so a side-by-side debug install is
        // clearly distinguishable from the release "FoxHole Guard".
        manifestPlaceholders["appLabel"] = "@string/app_name"
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
                    // armeabi-v7a included: the exp test device (Realme RMX3690) is 32-bit only.
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
        // Release distribution is intentionally arm64-v8a only.
        disable += "ChromeOsAbiSupport"
        // Dependency freshness is handled by the release audit/update pass; lint must stay focused on app defects.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    if (enableAbiSplitApks) {
        splits {
            abi {
                isEnable = true
                reset()
                include(*shippedAndroidAbis.toTypedArray())
                // With one shipped ABI, a "universal" output is byte-for-byte identical to the
                // arm64 split. Publishing that duplicate would only make release verification and
                // user choice ambiguous.
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
        // Preserve the existing variant/task names while keeping the package-installer permission
        // physically absent from F-Droid and every unknown managed channel. ManifestFiles is the
        // AGP variant API for adding a static, high-priority overlay to a selected build.
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
    // Auto-correction rewrites sources; findings that cannot be corrected still exit non-zero.
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
    // Road-to-beta cleanup: wildcard imports expanded + detekt formatting auto-corrected dropped
    // this from 586. The remaining entries are structural debt (LongMethod/LargeClass/ReturnCount…)
    // burned down by the god-file refactor; this threshold must only ever shrink.
    //
    // 2026-07-05: corrected from a stale 71. Pass 1–3 feature work (map/geo, network rules, help
    // sheets) added findings without a baseline refresh, so the tree actually carried 114 while the
    // committed baseline still claimed 71 — detekt analysis was silently red. This pass burned the
    // true count down to 84 (formatting auto-fix, dead-code/param removal, and complexity extraction
    // in CountryTrafficAggregator/SettingsRepositorySupport/GeoIpUpdateClient) and re-synced the
    // baseline to reality. Keep shrinking from here.
    //
    // 2026-07-05 (pass 4): burned to 0. The remaining god-files were split by responsibility into
    // per-domain extension files (SettingsRepository → 7 files, HomeViewModelSettingsSupport → 6,
    // ProfileRepository subscription/secret pipelines extracted, FoxholeProxyService/Controller
    // health+reconcile support), long composables/methods extracted, and the CyclomaticComplexMethod
    // / TooManyFunctions / LargeClass thresholds re-tuned in detekt.yml with documented rationale
    // (flat `when` lookups, cohesive repositories, test catalogs). The baseline is now empty — every
    // finding is fixed rather than suppressed. This threshold must stay 0.
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
    // Run the actual static analysis under `check`, not just the baseline entry count — otherwise
    // `./gradlew check` reports a green analysis result without ever executing detekt (the analysis
    // only ran because CI invokes `:app:detekt` explicitly). `detekt` is finalizedBy
    // verifyDetektBaseline, so the baseline check still runs too.
    dependsOn("detekt")
    // Compile the instrumented sources too. `check` builds and runs the unit tests but never
    // touched androidTest, so a production signature change could break every device test and
    // nothing said so until somebody physically reached a phone — which is exactly what happened:
    // a parameter added to buildWebAppWebView left LiveWebAppsAndroidTest uncompilable, and it was
    // found by a device run, not by the gate. Compiling is deliberate; running them needs a device
    // and stays a separate, explicit step.
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

// The focused gate below watches com/foxhole/core/network and com/foxhole/core/runtime, which
// live in their own modules since the core extraction — the report has to fold their classes,
// sources and test runs in, or those prefixes silently vanish from the XML.
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
        // com/foxhole/core/runtime classes compile in :core:runtime since the extraction; the
        // app-local trees above keep the include for the few app-side leftovers.
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
    // lazysodium ships libsodium as an .aar with bundled .so; jna must ride the aar variant
    // (with the Android natives). lazysodium pulls jna as a plain jar transitively, so exclude
    // it there and add jna@aar explicitly — otherwise both the jar and aar land and the build
    // fails on duplicate com.sun.jna classes.
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
