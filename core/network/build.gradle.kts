plugins {
    id("org.jetbrains.kotlin.jvm")
    // The app module's jacocoDebugUnitTestReport folds this module's classes and test run into
    // the release-critical coverage gate (com/foxhole/core/network moved here out of :app).
    jacoco
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
}
