plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
}

android {
    namespace = "com.foxhole.core.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "false")
        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")
        buildConfigField("String", "APPLICATION_ID", "\"com.foxhole.guard\"")
        buildConfigField("String", "DEFAULT_IP_INFO_ENDPOINT", "\"https://ipwho.is/\"")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_DIAGNOSTIC_LOGCAT", "true")

            enableUnitTestCoverage = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
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
