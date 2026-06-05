package com.foxhole.beta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyLocalDataScreenContractTest {
    @Test
    fun `settings exposes dedicated privacy and local data route`() {
        val appSource = sourceFile("FoxholeApp.kt").readText()
        val settingsSource = sourceFile("SettingsScreens.kt").readText()

        assertTrue(appSource.contains("PRIVACY_LOCAL_DATA = \"settings/privacy-local-data\""))
        assertTrue(appSource.contains("PrivacyLocalDataSettingsScreen("))
        assertTrue(settingsSource.contains("settings_privacy_local_data_action"))
        assertTrue(settingsSource.contains("privacy_local_data_title"))
    }

    @Test
    fun `privacy local data screen keeps all required deletion actions`() {
        val source = sourceFile("SettingsPrivacyLocalDataScreen.kt").readText()

        assertTrue(source.contains("privacy_local_data_settings_screen"))
        assertTrue(source.contains("CLEAR_DIAGNOSTICS"))
        assertTrue(source.contains("CLEAR_NETWORK_ACTIVITY"))
        assertTrue(source.contains("CLEAR_APP_TRAFFIC"))
        assertTrue(source.contains("CLEAR_PROFILES"))
        assertTrue(source.contains("FACTORY_RESET"))
        assertTrue(source.contains("privacy_local_data_usage_access_status_title"))
        assertTrue(source.contains("privacy_local_data_factory_reset_action"))
    }

    @Test
    fun `usage access consent uses affirmative allow and not now labels`() {
        val enStrings = resourceFile("values/strings.xml").readText()
        val ruStrings = resourceFile("values-ru/strings.xml").readText()
        val usageAccessSource = sourceFile("UsageAccessUi.kt").readText()

        assertTrue(enStrings.contains("""name="usage_access_consent_confirm">Allow"""))
        assertTrue(enStrings.contains("""name="usage_access_consent_dismiss">Not now"""))
        assertTrue(ruStrings.contains("""name="usage_access_consent_confirm">Разрешить"""))
        assertTrue(ruStrings.contains("""name="usage_access_consent_dismiss">Не сейчас"""))
        assertTrue(usageAccessSource.contains("usage_access_consent_dismiss"))
        assertFalse(enStrings.contains("""name="usage_access_consent_confirm">Open Usage Access"""))
    }

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/beta/ui/$name"),
            File("app/src/main/kotlin/com/foxhole/beta/ui/$name"),
            File("../app/src/main/kotlin/com/foxhole/beta/ui/$name"),
        ).first { file -> file.isFile }

    private fun resourceFile(path: String): File =
        listOf(
            File("src/main/res/$path"),
            File("app/src/main/res/$path"),
            File("../app/src/main/res/$path"),
        ).first { file -> file.isFile }
}
