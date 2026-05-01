pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.google.com")
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.gitlab.arturbosch.detekt") {
                useModule("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.google.com")
    }
}

rootProject.name = "foxhole"
include(":app")
include(":macrobenchmark")
