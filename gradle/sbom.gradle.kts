import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.gradle.CyclonedxPlugin
import org.cyclonedx.model.Component

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    configurations.classpath {
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            when {
                requested.group == "com.squareup.wire" && requested.name.startsWith("wire-runtime") ->
                    useVersion("6.3.0")
                requested.group == "io.netty" && requested.name.startsWith("netty-") ->
                    useVersion("4.1.137.Final")
                coordinate == "org.apache.commons:commons-lang3" ->
                    useVersion("3.20.0")
                coordinate == "org.apache.httpcomponents:httpclient" ->
                    useVersion("4.5.14")
                coordinate == "org.bitbucket.b_c:jose4j" ->
                    useVersion("0.9.6")
                requested.group == "org.bouncycastle" && requested.name in
                    setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") ->
                    useVersion("1.85")
                coordinate == "org.jdom:jdom2" ->
                    useVersion("2.0.6.1")
            }
        }
    }
    dependencies {
        classpath("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.3.0")
    }
}

apply<CyclonedxPlugin>()

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        projectType.set(Component.Type.APPLICATION)
        includeConfigs.set(listOf("releaseRuntimeClasspath"))
        skipConfigs.set(listOf(".*[Tt]est.*", ".*[Bb]enchmark.*", ".*[Ll]int.*", ".*[Kk]sp.*"))
        includeBuildEnvironment.set(false)
        includeMetadataResolution.set(false)
        jsonOutput.unsetConvention()
        resolvedDependencies.setFrom(emptyList<Any>())
        outputs.upToDateWhen { false }
    }
}

subprojects {
    if (name == "macrobenchmark") {
        tasks.withType<CyclonedxDirectTask>().configureEach {
            enabled = false
        }
    }
}

allprojects {
    afterEvaluate {
        tasks.findByName("cyclonedxDirectBom")
    }
}

tasks.withType<CyclonedxAggregateTask>().configureEach {
    projectType.set(Component.Type.APPLICATION)
    componentName.set("foxhole-android")
    componentGroup.set("com.foxhole")
    componentVersion.set(providers.provider { rootProject.version.toString() })
    includeBuildSystem.set(true)
}
