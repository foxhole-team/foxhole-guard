package com.foxhole.guard.ui

import com.foxhole.guard.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PublicVersionCopyTest {
    @Test
    fun `visible app version uses build version from generated config`() {
        val state = HomeUiState()

        assertEquals(BuildConfig.VERSION_NAME, state.appVersion)
    }

    @Test
    fun `public surfaces name the version this build actually is`() {
        val publicSurfaceFiles =
            listOf(
                "metadata/com.foxhole.guard.yml",
                "fastlane/metadata/android/en-US/changelogs/default.txt",
                "fastlane/metadata/android/ru-RU/changelogs/default.txt",
            )

        val releaseVersionName =
            BuildConfig.VERSION_NAME
                .removeSuffix("-Debug")
                .removeSuffix("-Internal")

        publicSurfaceFiles.forEach { path ->
            val content = projectFile(path).readText()

            if (path.endsWith(".yml")) {
                val configuredVersion =
                    Regex("""(?m)^CurrentVersion: (.+)$""").find(content)!!.groupValues[1]
                assertEquals(
                    "$path must name the configured candidate version",
                    releaseVersionName,
                    configuredVersion,
                )
            } else {
                assertTrue(
                    "$path must start with the actual Stable candidate version",
                    content.lineSequence().first().startsWith("$releaseVersionName Stable"),
                )
            }
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
