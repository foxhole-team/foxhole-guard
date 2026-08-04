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
        // This used to demand the literal string "public beta 1.0" on every public surface, and it
        // kept demanding it for three version bumps: the build had been 1.1.0-beta3 (versionCode
        // 66) since the dev line closed, while README, the F-Droid metadata and the changelog all
        // still announced 1.0 — and the test enforced the mismatch instead of catching it.
        val publicSurfaceFiles =
            listOf(
                "README.md",
                "README.ru.md",
                "metadata/com.foxhole.guard.yml",
                "fastlane/metadata/android/en-US/changelogs/default.txt",
                "fastlane/metadata/android/ru-RU/changelogs/default.txt",
            )

        // Unit tests run on the debug variant, whose versionNameSuffix is "-Debug"; the public
        // surfaces name the release version, so the suffix has to come off before comparing.
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
