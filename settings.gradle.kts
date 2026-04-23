pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.google.com")
        gradlePluginPortal()
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
