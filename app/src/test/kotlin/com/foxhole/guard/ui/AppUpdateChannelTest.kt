package com.foxhole.guard.ui

import com.foxhole.guard.BuildConfig
import org.junit.Assert.assertEquals
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
    fun `every Binaries recipe matches the channel of its published APK`() {
        val metadata =
            listOf(
                File("metadata/com.foxhole.guard.yml"),
                File("../metadata/com.foxhole.guard.yml"),
            ).first(File::isFile).readText()
        val builds = fdroidBuildBlocks(metadata)

        assertTrue(metadata.contains("Binaries:"))
        assertTrue(metadata.contains("AutoUpdateMode: Version"))
        assertEquals(listOf("0.1.1"), builds.map(FdroidBuildBlock::versionName))
        assertTrue(builds.single().body.contains("    versionCode: 122\n"))
        assertTrue(
            builds.single().body.contains("    commit: v0.1.1\n"),
        )
        assertFalse(metadata.contains("\nSummary:"))
        assertFalse(metadata.contains("\nDescription:"))
        assertFalse(metadata.contains("\nMaintainerNotes:"))
        val binaryUrl = metadata.substringAfter("Binaries:\n").substringBefore("\nBuilds:").trim()
        val channel = if (binaryUrl.endsWith("-fdroid.apk")) "fdroid" else "github"
        val suffix = if (channel == "fdroid") "fdroid" else "release"
        assertEquals(
            "https://github.com/foxhole-team/foxhole-guard/releases/download/v%v/FoxHole-v%v-arm64-v8a-$suffix.apk",
            binaryUrl,
        )
        builds.forEach { build ->
            val commands = build.body.substringAfter("    build:").substringBefore("    ndk:")
            assertFalse(build.body.contains("    gradleprops:"))
            assertEquals(
                "${build.versionName}: Binaries build must use only the published APK channel",
                listOf(channel),
                Regex("-Pfoxhole.updateChannel=([a-z]+)").findAll(commands)
                    .map { it.groupValues[1] }.toList(),
            )
            assertTrue(commands.contains(":app:assembleRelease"))
        }
        val recipeCode = Regex("""(?m)^\s+versionCode: ([0-9]+)$""")
            .findAll(builds.single().body).single().groupValues[1].toInt()
        assertTrue("Historical recipe cannot be ahead of this candidate", recipeCode < BuildConfig.VERSION_CODE)
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

private data class FdroidBuildBlock(
    val versionName: String,
    val body: String,
)

private fun fdroidBuildBlocks(metadata: String): List<FdroidBuildBlock> {
    val buildsSection = metadata.substringAfter("Builds:\n").substringBefore("\nAllowedAPKSigningKeys:")
    val starts = Regex("""(?m)^  - versionName: ([^\n]+)$""").findAll(buildsSection).toList()
    return starts.mapIndexed { index, match ->
        val end = starts.getOrNull(index + 1)?.range?.first ?: buildsSection.length
        FdroidBuildBlock(
            versionName = match.groupValues[1].trim(),
            body = buildsSection.substring(match.range.first, end),
        )
    }
}
