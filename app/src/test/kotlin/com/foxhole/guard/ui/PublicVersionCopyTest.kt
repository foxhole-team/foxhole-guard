package com.foxhole.guard.ui

import com.foxhole.guard.BuildConfig
import org.junit.Assert.assertFalse
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
    fun `candidate version and store copy follow their actual authorities`() {
        val delegation = projectFile("metadata/README.md")
        assertTrue(delegation.readText().contains("https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45667"))
        assertFalse(File(delegation.parentFile, "com.foxhole.guard.yml").exists())
        val rootBuild = File(delegation.parentFile.parentFile, "build.gradle.kts").readText()
        val declaredVersion = Regex("""(?m)^\s*version\s*=\s*["]([^"\r\n]+)["]\s*$""").find(rootBuild)!!.groupValues[1]
        assertEquals(declaredVersion, BuildConfig.VERSION_NAME.removeSuffix("-Debug").removeSuffix("-Internal"))
        val englishNotes = projectFile("fastlane/metadata/android/en-US/changelogs/${BuildConfig.VERSION_CODE}.txt").readText()
        listOf("en-US", "ru-RU", "es-ES").forEach { locale ->
            val directory = "fastlane/metadata/android/$locale/changelogs"
            val current = projectFile("$directory/${BuildConfig.VERSION_CODE}.txt").readText()
            val default = projectFile("$directory/default.txt").readText()
            assertTrue(current.isNotBlank())
            assertEquals("Current candidate notes remain English in every retained store locale", englishNotes, current)
            assertEquals("$locale current candidate/default store notes differ", current, default)
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
