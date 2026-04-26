package com.foxhole.beta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicVersionCopyTest {
    @Test
    fun `visible app version uses public beta label while keeping technical debug suffix readable`() {
        assertEquals(PUBLIC_BETA_VERSION_LABEL, displayAppVersion("1.0.0-beta1"))
        assertEquals("$PUBLIC_BETA_VERSION_LABEL Debug", displayAppVersion("1.0.0-beta1-Debug"))
        assertEquals("1.0.1", displayAppVersion("1.0.1"))
    }

    @Test
    fun `public surfaces keep public beta copy instead of technical version copy`() {
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

            assertTrue("$path should mention public beta 1.0", content.contains(PUBLIC_BETA_VERSION_LABEL))
            if (!path.endsWith("com.foxhole.beta.yml")) {
                assertFalse("$path should not expose the technical version label", content.contains("1.0.0-beta1"))
            }
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
