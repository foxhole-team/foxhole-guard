package com.foxhole.guard.ui

import com.foxhole.guard.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

            assertTrue(
                "$path names no version; it must name $releaseVersionName",
                content.contains(releaseVersionName, ignoreCase = true),
            )
            assertFalse("$path should not announce a stable release", content.contains("stable 1.0", ignoreCase = true))
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
