plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
}

android {
    namespace = "com.foxhole.core.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Release-safe default; the debug build type flips diagnostics on below. Mirrors the
        // app module so the engine (now living here) logs to logcat in debug exactly as before.
        buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "false")
        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.foxhole.guard\"")
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
    }

    buildTypes {
        // `debug` and `release` always exist for a library module. Only override the debug field;
        // release keeps the false default. The app's release/publicRelease keep matching `release`.
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "true")
            // The app module's coverage gate reads this module's debug unit-test coverage
            // (com/foxhole/core/runtime moved here out of :app).
            enableUnitTestCoverage = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // JVM tests drive real runtime error paths that end in android.util.Log; default-value
        // stubs keep those paths testable without a device.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
}
