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
        val releaseSuffix = declaredVersion.replace(".", "")
        mapOf("en-US" to "values", "ru-RU" to "values-ru", "es-ES" to "values-es").forEach { (locale, values) ->
            val directory = "fastlane/metadata/android/$locale/changelogs"
            val current = projectFile("$directory/${BuildConfig.VERSION_CODE}.txt").readText()
            val default = projectFile("$directory/default.txt").readText()
            assertTrue(current.isNotBlank())
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(projectFile("app/src/main/res/$values/local_patch_release_notes.xml"))
            val strings = document.getElementsByTagName("string")
            val expected = (0 until strings.length).map { strings.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("name").startsWith("cli_release_notes_") && it.getAttribute("name").endsWith("_$releaseSuffix") }
                .joinToString("") { "- ${it.textContent}\n" }
            assertTrue("Missing localized current release notes", expected.isNotBlank())
            assertEquals("$locale store notes must follow current in-app copy", expected, current)
            assertEquals("$locale current candidate/default store notes differ", current, default)
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first { file -> file.isFile }
}
