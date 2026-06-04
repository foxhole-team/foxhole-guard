package com.foxhole.beta.ui

import com.foxhole.beta.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicVersionCopyTest {
    @Test
    fun `visible app version uses build version from generated config`() {
        val state = HomeUiState()

        assertEquals(BuildConfig.VERSION_NAME, state.appVersion)
    }

    @Test
    fun `public surfaces stay on public beta release copy`() {
        val publicSurfaceFiles =
            listOf(
                "README.md",
                "README.ru.md",
                "metadata/com.foxhole.beta.yml",
                "fastlane/metadata/android/en-US/changelogs/default.txt",
                "fastlane/metadata/android/ru-RU/changelogs/default.txt",
            )

        publicSurfaceFiles.forEach { path ->
            val content = projectFile(path).readText()

            assertTrue("$path should mention public beta 1.0", content.contains("public beta 1.0", ignoreCase = true))
            assertFalse("$path should not mention stable 1.0", content.contains("stable 1.0", ignoreCase = true))
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
