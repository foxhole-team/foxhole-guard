import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties
import java.util.zip.ZipFile

abstract class VerifyBundledLibboxInReleaseApkTask : DefaultTask() {
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

        val requiredEntries =
            setOf(
                "lib/arm64-v8a/libbox.so",
                "lib/armeabi-v7a/libbox.so",
                "lib/arm64-v8a/libTor.so",
                "lib/armeabi-v7a/libTor.so",
                "lib/arm64-v8a/liblyrebird.so",
                "lib/armeabi-v7a/liblyrebird.so",
                "lib/arm64-v8a/libconjure_client.so",
                "lib/armeabi-v7a/libconjure_client.so",
            )
        val discoveredEntries = linkedSetOf<String>()

        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { entryName ->
                        entryName.endsWith("/libbox.so") ||
                            entryName.endsWith("/libTor.so") ||
                            entryName.endsWith("/liblyrebird.so") ||
                            entryName.endsWith("/libconjure_client.so")
                    }
                    .forEach(discoveredEntries::add)
            }
        }

        val missingEntries = requiredEntries - discoveredEntries
        require(missingEntries.isEmpty()) {
            buildString {
                append("release APK was produced without bundled libbox runtime entries: ")
                append(missingEntries.joinToString())
                append(". Found release APKs: ")
                append(apks.joinToString { it.name })
                append(". Found libbox entries: ")
                append(discoveredEntries.joinToString().ifBlank { "none" })
            }
        }
    }
}

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.7"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    jacoco
}

val enableAbiSplitApks = providers.gradleProperty("foxhole.splitApks").map(String::toBoolean).orElse(false).get()
val enableReleaseProbe = providers.gradleProperty("foxhole.releaseProbe").map(String::toBoolean).orElse(false).get()
val enableStrictMode = providers.gradleProperty("foxhole.strictMode").map(String::toBoolean).orElse(false).get()
val releaseSigningPropertiesFile = rootProject.projectDir.parentFile.resolve("dev/signing/release-signing.properties")
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
val bundledLibbox = file("libs/libbox.aar")
val bundledLegacyLibbox = file("libs/libbox-legacy.aar")
val bundledLibboxVersionStamp = file("libs/libbox.version")
val bundledTorVersion =
    file("src/main/assets/tor/arm64-v8a/.version")
        .takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.substringAfterLast('-')
        ?.takeIf(String::isNotBlank)
        ?: "unknown"
val buildLibboxScript = rootProject.file("scripts/build-libbox.sh")
val releaseSigningReady =
    !releaseSigningStoreFilePath.isNullOrBlank() &&
        !releaseSigningStorePassword.isNullOrBlank() &&
        !releaseSigningKeyAlias.isNullOrBlank() &&
        !releaseSigningKeyPassword.isNullOrBlank() &&
        file(releaseSigningStoreFilePath).isFile

val prepareBundledLibbox by tasks.registering {
    val versionFile = rootProject.file("third_party/sing-box.version")
    inputs.file(versionFile)
    inputs.file(buildLibboxScript)
    outputs.files(bundledLibbox, bundledLegacyLibbox, bundledLibboxVersionStamp)

    doLast {
        val expectedVersion = versionFile.readText()
        if (
            bundledLibbox.isFile &&
            bundledLegacyLibbox.isFile &&
            bundledLibboxVersionStamp.isFile &&
            bundledLibboxVersionStamp.readText() == expectedVersion
        ) {
            return@doLast
        }
        require(buildLibboxScript.isFile) { "missing libbox bootstrap script: ${buildLibboxScript.absolutePath}" }
        val process =
            ProcessBuilder(buildLibboxScript.absolutePath)
                .directory(rootProject.projectDir)
                .inheritIO()
                .start()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "libbox bootstrap script failed with exit code $exitCode"
        }
        require(bundledLibbox.isFile && bundledLegacyLibbox.isFile) {
            "libbox bootstrap did not produce ${bundledLibbox.name} and ${bundledLegacyLibbox.name}"
        }
        bundledLibboxVersionStamp.writeText(expectedVersion)
    }
}

val prepareTorNativeLibs by tasks.registering(Sync::class) {
    from("src/main/assets/tor") {
        include("*/tor/libTor.so")
        include("*/tor/pluggable_transports/lyrebird")
        include("*/tor/pluggable_transports/conjure-client")
        includeEmptyDirs = false
        eachFile {
            val abi = relativePath.segments.first()
            path =
                when (name) {
                    "libTor.so" -> "$abi/libTor.so"
                    "lyrebird" -> "$abi/liblyrebird.so"
                    "conjure-client" -> "$abi/libconjure_client.so"
                    else -> "$abi/$name"
                }
        }
    }
    into(layout.buildDirectory.dir("generated/torNativeLibs"))
}

tasks.matching { task ->
    task.name in
        setOf(
            "preBuild",
            "preDebugBuild",
            "preReleaseBuild",
            "preDebugAndroidTestBuild",
        )
}.configureEach {
    dependsOn(prepareBundledLibbox)
}

tasks.matching { task -> task.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(prepareTorNativeLibs)
}

val verifyReleaseContainsBundledLibbox by tasks.registering(VerifyBundledLibboxInReleaseApkTask::class) {
    dependsOn("assembleRelease")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
}

val verifyReleaseBuildConfigDefaults by tasks.registering {
    dependsOn("generateReleaseBuildConfig")
    val releaseBuildConfigFile =
        layout.buildDirectory.file("generated/source/buildConfig/release/com/foxhole/beta/BuildConfig.java")
    inputs.file(releaseBuildConfigFile)

    doLast {
        val content = releaseBuildConfigFile.get().asFile.readText()
        require("public static final boolean ALLOW_INSECURE_TLS_BY_DEFAULT = false;" in content) {
            "release BuildConfig must set ALLOW_INSECURE_TLS_BY_DEFAULT=false"
        }
    }
}

tasks.matching { task -> task.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseContainsBundledLibbox)
    finalizedBy(verifyReleaseBuildConfigDefaults)
}

tasks.named("check") {
    dependsOn(verifyReleaseBuildConfigDefaults)
}

android {
    namespace = "com.foxhole.beta"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foxhole.beta"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0-beta1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        if (enableAbiSplitApks) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
        buildConfigField("String", "DEFAULT_SUPPORT_BOT_HANDLE", "\"@foxhole_repo_support_bot\"")
        buildConfigField("String", "LIBBOX_SOURCE_VERSION", "\"1.13.12\"")
        buildConfigField("String", "TOR_BUNDLE_VERSION", "\"$bundledTorVersion\"")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(releaseSigningStoreFilePath!!)
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
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "true")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "true")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", enableStrictMode.toString())
            if (!enableAbiSplitApks) {
                ndk {
                    abiFilters += listOf("arm64-v8a", "x86_64", "x86")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "false")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", if (enableReleaseProbe) "true" else "false")
            buildConfigField("boolean", "ENABLE_STRICT_MODE", "false")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            if (!enableAbiSplitApks) {
                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
            jniLibs.directories.add(layout.buildDirectory.dir("generated/torNativeLibs").get().asFile.path)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols +=
            listOf(
                "**/libTor.so",
                "**/libandroidx.graphics.path.so",
                "**/libbox.so",
                "**/libconjure_client.so",
                "**/libdatastore_shared_counter.so",
                "**/liblyrebird.so",
                "**/libsqlcipher.so",
            )
    }

    lint {
        // Release distribution is intentionally ARM-only: arm64-v8a, armeabi-v7a, and an ARM universal APK.
        disable += "ChromeOsAbiSupport"
        // Dependency freshness is handled by the release audit/update pass; lint must stay focused on app defects.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    if (enableAbiSplitApks) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = true
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

composeCompiler {
    includeComposeMappingFile.set(false)
}

jacoco {
    toolVersion = "0.8.14"
}

val detektCli by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val detektPlugins by configurations.creating {
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
        ).map(::file)

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

tasks.register<JavaExec>("updateDetektBaseline") {
    group = "verification"
    description = "Regenerate the detekt baseline with the stable CLI."

    val detektConfig = rootProject.file("config/detekt/detekt.yml")
    val detektBaseline = rootProject.file("config/detekt/baseline.xml")
    val detektSources =
        listOf(
            "src/main/kotlin",
            "src/test/kotlin",
        ).map(::file)

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

val verifyDetektBaseline by tasks.registering {
    group = "verification"
    description = "Fail when detekt baseline changes without an intentional threshold update."

    val detektBaseline = rootProject.file("config/detekt/baseline.xml")
    val expectedBaselineIssues = 755
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
    dependsOn(verifyDetektBaseline)
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
    )

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(jacocoDebugClassDirectories)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )
    doLast {
        val xmlReport = reports.xml.outputLocation.get().asFile
        require(xmlReport.readText().contains("<counter ")) {
            "Jacoco debug unit test report did not include coverage counters: ${xmlReport.absolutePath}"
        }
    }
}

val verifyJacocoFocusedCoverage by tasks.registering {
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
                        .find(packageXml)
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
            Triple("core package instruction coverage", listOf("com/foxhole/beta/core"), 0.45),
            Triple(
                "domain core instruction coverage",
                listOf(
                    "com/foxhole/beta/core/model",
                    "com/foxhole/beta/core/profile",
                    "com/foxhole/beta/core/importer",
                    "com/foxhole/beta/core/smart",
                ),
                0.70,
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
    classDirectories.setFrom(jacocoDebugClassDirectories)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )
    violationRules {
        rule {
            limit {
                minimum = "0.21".toBigDecimal()
            }
        }
    }
}

tasks.register("verifyRequiredBehaviorTests") {
    dependsOn("testDebugUnitTest")
    doLast {
        val requiredSuites =
            listOf(
                "com.foxhole.beta.core.data.BoundedPublicHttpFetchTest",
                "com.foxhole.beta.core.data.ProfileInsecureTlsSupportTest",
                "com.foxhole.beta.core.data.ProtocolOptionRefreshSelectionTest",
                "com.foxhole.beta.core.data.ProfileSecretMutationSupportTest",
                "com.foxhole.beta.core.data.SubscriptionCertificateTrustTest",
                "com.foxhole.beta.core.anomaly.DnsRuntimeStatsTest",
                "com.foxhole.beta.core.diagnostics.DiagnosticsSessionStoreTest",
                "com.foxhole.beta.core.network.IpInfoRepositoryTest",
                "com.foxhole.beta.core.settings.SettingsRepositoryTest",
                "com.foxhole.beta.core.smart.SmartStartControllerTest",
                "com.foxhole.beta.core.traffic.TorGeoIpCountryResolverTest",
                "com.foxhole.beta.core.traffic.TrafficMapRepositoryTest",
                "com.foxhole.beta.vpn.AppOwnedRequestPathTest",
                "com.foxhole.beta.vpn.FoxholeConnectionControllerLatencyTest",
                "com.foxhole.beta.vpn.RuntimeCommandActorTest",
                "com.foxhole.beta.vpn.RuntimeConfigAssemblerTest",
                "com.foxhole.beta.vpn.RuntimeInstanceStoreTest",
                "com.foxhole.beta.vpn.RuntimeServiceCommandSupportTest",
                "com.foxhole.beta.vpn.RuntimeStaticSafetyGuardTest",
                "com.foxhole.beta.vpn.RuntimeStopSupportTest",
                "com.foxhole.beta.vpn.RuntimeSupervisorTest",
                "com.foxhole.beta.vpn.RuntimeUpdatePolicyTest",
                "com.foxhole.beta.vpn.TunnelValidationPolicyTest",
                "com.foxhole.beta.vpn.VpnDnsServerSelectorTest",
                "com.foxhole.beta.vpn.VpnRuntimeErrorsTest",
            )
        val resultFiles =
            fileTree(layout.buildDirectory.dir("test-results/testDebugUnitTest")) {
                include("TEST-*.xml")
            }.files.associateBy { file -> file.name.removePrefix("TEST-").removeSuffix(".xml") }
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
    implementation(files(bundledLibbox))

    constraints {
        listOf(
            "protobuf-java",
            "protobuf-javalite",
            "protobuf-java-util",
            "protobuf-kotlin",
        ).forEach { moduleName ->
            implementation("com.google.protobuf:$moduleName:3.25.5") {
                because("CVE-2024-7254 affects protobuf-java/protobuf-javalite before 3.25.5")
            }
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.sqlcipher.android)
    implementation(libs.errorprone.annotations)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    implementation(libs.blurview)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    detektCli(libs.detekt.cli)
    detektPlugins(libs.detekt.formatting)
}
