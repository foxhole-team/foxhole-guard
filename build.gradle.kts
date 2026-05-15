import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

buildscript {
    repositories {
        mavenCentral()
        maven("https://maven.google.com")
    }
    configurations.classpath {
        // Keep Gradle/Android build-tool transitives above OSV fixed ranges.
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            when {
                requested.group == "io.netty" && requested.name.startsWith("netty-") ->
                    useVersion("4.1.132.Final")
                coordinate == "org.apache.commons:commons-lang3" ->
                    useVersion("3.18.0")
                coordinate == "org.apache.httpcomponents:httpclient" ->
                    useVersion("4.5.14")
                coordinate == "org.bitbucket.b_c:jose4j" ->
                    useVersion("0.9.6")
                requested.group == "org.bouncycastle" && requested.name in
                    setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") ->
                    useVersion("1.84")
                coordinate == "org.jdom:jdom2" ->
                    useVersion("2.0.6.1")
            }
        }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
    }
}

plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.cyclonedx.bom") version "3.2.4"
}

val hardenedToolDependencyVersions =
    mapOf(
        "org.apache.commons:commons-lang3" to "3.18.0",
        "org.apache.httpcomponents:httpclient" to "4.5.14",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
        "org.jdom:jdom2" to "2.0.6.1",
    )

allprojects {
    configurations.configureEach {
        // Covers project, unit-test, lint, and UTP configurations resolved outside buildscript.
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            when {
                requested.group == "io.netty" && requested.name.startsWith("netty-") ->
                    useVersion("4.1.132.Final")
                requested.group == "org.bouncycastle" && requested.name in
                    setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") ->
                    useVersion("1.84")
                coordinate in hardenedToolDependencyVersions ->
                    useVersion(hardenedToolDependencyVersions.getValue(coordinate))
            }
        }
    }
    tasks.withType<CyclonedxDirectTask>().configureEach {
        projectType.set(Component.Type.APPLICATION)
        includeConfigs.set(listOf("releaseRuntimeClasspath"))
        skipConfigs.set(listOf(".*[Tt]est.*", ".*[Bb]enchmark.*", ".*[Ll]int.*", ".*[Kk]sp.*"))
        includeBuildEnvironment.set(false)
        includeMetadataResolution.set(false)
    }
}

subprojects {
    if (name == "macrobenchmark") {
        tasks.withType<CyclonedxDirectTask>().configureEach {
            enabled = false
        }
    }
}

tasks.withType<CyclonedxAggregateTask>().configureEach {
    projectType.set(Component.Type.APPLICATION)
    componentName.set("foxhole-android")
    includeBuildSystem.set(true)
}
