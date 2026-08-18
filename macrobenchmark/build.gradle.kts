plugins {
    id("com.android.test")
}

val targetPackageName = providers.gradleProperty("macrobenchmark.targetPackage").orElse("com.foxhole.guard.debug").get()

android {
    namespace = "com.foxhole.guard.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,DEBUGGABLE"
        buildConfigField("String", "TARGET_PACKAGE_NAME", "\"$targetPackageName\"")
    }

    buildFeatures {
        buildConfig = true
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.keepDebugSymbols +=
            listOf(
                "**/libbenchmarkNative.so",
                "**/libtracing_perfetto.so",
            )
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
}
