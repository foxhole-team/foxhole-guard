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
                    useVersion("4.1.136.Final")
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
        classpath("com.android.tools.build:gradle:9.3.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

val enableSbom = providers.gradleProperty("foxhole.sbom").map(String::toBoolean).orElse(false)

if (enableSbom.get()) {
    apply(from = layout.projectDirectory.file("gradle/sbom.gradle.kts"))
}

allprojects {
    group = "com.foxhole"
    version = "0.0.2"
}

val hardenedToolDependencyVersions =
    mapOf(
        "org.apache.commons:commons-lang3" to "3.20.0",
        "org.apache.httpcomponents:httpclient" to "4.5.14",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
        "org.jdom:jdom2" to "2.0.6.1",
    )

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            when {
                requested.group == "io.netty" && requested.name.startsWith("netty-") ->
                    useVersion("4.1.136.Final")
                requested.group == "org.bouncycastle" && requested.name in
                    setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on") ->
                    useVersion("1.85")
                coordinate in hardenedToolDependencyVersions ->
                    useVersion(hardenedToolDependencyVersions.getValue(coordinate))
            }
        }
    }
}
