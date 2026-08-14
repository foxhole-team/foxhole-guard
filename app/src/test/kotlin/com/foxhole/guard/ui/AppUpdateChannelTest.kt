package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateChannelTest {
    @Test
    fun `only github builds can use the in-app updater`() {
        assertTrue(selfUpdateEnabledFor("github"))
        assertFalse(selfUpdateEnabledFor("fdroid"))
        assertFalse(selfUpdateEnabledFor("play"))
        assertFalse(selfUpdateEnabledFor("GitHub"))
    }

    @Test
    fun `every app update action is fail-closed outside github builds`() {
        val source = uiSource("HomeViewModelAppUpdateSupport.kt")
        listOf(
            "onAppUpdateCheckRequested",
            "onAppUpdateDownloadRequested",
            "onAppUpdateInstallRequested",
        ).forEach { function ->
            val body =
                source
                    .substringAfter("fun HomeViewModel.$function")
                    .substringBefore("\ninternal fun HomeViewModel.")
            assertTrue("$function lost its channel gate", body.contains("!appUpdateChannelIsGithub"))
        }
    }

    @Test
    fun `fdroid and managed builds do not render the app updater panel`() {
        val source = cliSettingsSource("CliUpdatesSubScreen.kt")
        val gatedPanel =
            source
                .substringAfter("if (appUpdateChannelIsGithub)")
                .substringBefore("private data class FoxholeDbPhases")
        assertTrue(gatedPanel.contains("CliAppUpdatePanelBody("))
    }

    @Test
    fun `fdroid recipe forces the managed update channel`() {
        val metadata =
            listOf(
                File("metadata/com.foxhole.guard.yml"),
                File("../metadata/com.foxhole.guard.yml"),
            ).first(File::isFile).readText()
        val build = metadata.substringAfter("Builds:").substringBefore("AutoUpdateMode:")
        assertTrue(build.contains("gradleprops:"))
        assertTrue(build.contains("foxhole.updateChannel=fdroid"))
    }

    @Test
    fun `package installer permission follows the update channel`() {
        val build = projectFile("app/build.gradle.kts").readText()
        val mainManifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val githubManifest = projectFile("app/src/githubUpdater/AndroidManifest.xml").readText()
        val updater = uiSource("HomeViewModelAppUpdateSupport.kt")

        assertTrue(build.contains("if (appUpdateChannel == \"github\")"))
        assertTrue(build.contains("addStaticManifestFile(\"src/githubUpdater/AndroidManifest.xml\")"))
        assertFalse(mainManifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(githubManifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(updater.contains("packageManager.canRequestPackageInstalls()"))
        assertTrue(updater.contains("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
    }

    private fun uiSource(name: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui", name),
            File("app/src/main/kotlin/com/foxhole/guard/ui", name),
            File("../app/src/main/kotlin/com/foxhole/guard/ui", name),
        ).first(File::isFile).readText()

    private fun cliSettingsSource(name: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/settings", name),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/settings", name),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/settings", name),
        ).first(File::isFile).readText()

    private fun projectFile(path: String): File =
        listOf(
            File(path),
            File("../$path"),
        ).first(File::isFile)
}
