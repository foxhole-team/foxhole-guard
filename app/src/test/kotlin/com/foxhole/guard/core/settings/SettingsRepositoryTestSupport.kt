package com.foxhole.guard.core.settings

import kotlinx.serialization.json.Json

internal open class SettingsRepositoryTestSupport {
    protected val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    protected fun settingsRepositorySource(): String =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/guard/core/settings"),
            java.io.File("app/src/main/kotlin/com/foxhole/guard/core/settings"),
            java.io.File("../app/src/main/kotlin/com/foxhole/guard/core/settings"),
        ).first { dir -> dir.isDirectory }
            .listFiles { file -> file.name.startsWith("SettingsRepository") && file.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { file -> file.name }
            .joinToString("\n") { file -> file.readText() }

    protected fun settingsNormalizationSource(): String =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/guard/core/settings/SettingsNormalizationSupport.kt"),
            java.io.File("app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsNormalizationSupport.kt"),
            java.io.File("../app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsNormalizationSupport.kt"),
        ).first(java.io.File::isFile).readText()
}
