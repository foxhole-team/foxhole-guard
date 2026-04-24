import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
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
            )
        val discoveredEntries = linkedSetOf<String>()

        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter { entryName -> entryName.endsWith("/libbox.so") }
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
}

val enableAbiSplitApks = providers.gradleProperty("foxhole.splitApks").map(String::toBoolean).orElse(false).get()
val enableReleaseProbe = providers.gradleProperty("foxhole.releaseProbe").map(String::toBoolean).orElse(false).get()
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
val buildLibboxScript = rootProject.file("scripts/build-libbox.sh")
val releaseSigningReady =
    !releaseSigningStoreFilePath.isNullOrBlank() &&
        !releaseSigningStorePassword.isNullOrBlank() &&
        !releaseSigningKeyAlias.isNullOrBlank() &&
        !releaseSigningKeyPassword.isNullOrBlank() &&
        file(releaseSigningStoreFilePath).isFile

val prepareBundledLibbox by tasks.registering {
    inputs.file(rootProject.file("third_party/sing-box.version"))
    inputs.file(buildLibboxScript)
    outputs.files(bundledLibbox, bundledLegacyLibbox)

    doLast {
        if (bundledLibbox.isFile && bundledLegacyLibbox.isFile) {
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
    }
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

val verifyReleaseContainsBundledLibbox by tasks.registering(VerifyBundledLibboxInReleaseApkTask::class) {
    dependsOn("assembleRelease")
    apkDirectory.set(layout.buildDirectory.dir("outputs/apk/release"))
}

tasks.matching { task -> task.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseContainsBundledLibbox)
}

android {
    namespace = "com.foxhole.beta"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.foxhole.beta"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0-beta1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        if (enableAbiSplitApks) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
        buildConfigField("String", "DEFAULT_SUPPORT_BOT_HANDLE", "\"@foxhole_app_support_bot\"")
        buildConfigField("String", "LIBBOX_SOURCE_VERSION", "\"1.13.6\"")
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
            if (!enableAbiSplitApks) {
                ndk {
                    abiFilters += listOf("arm64-v8a", "x86_64", "x86")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "ALLOW_INSECURE_TLS_BY_DEFAULT", "true")
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", if (enableReleaseProbe) "true" else "false")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs.useLegacyPackaging = false
    }

    lint {
        // Release distribution is intentionally ARM-only: arm64-v8a, armeabi-v7a, and an ARM universal APK.
        disable += "ChromeOsAbiSupport"
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
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
