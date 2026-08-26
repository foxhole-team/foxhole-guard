package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.ui.cli.components.cliSecretDisplay
import com.foxhole.guard.ui.cli.components.cliSecretDraftChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class CliProxyCredentialsUiTest {
    @Test
    fun `official update sources expose only the badge while custom sources keep their value`() {
        assertEquals(
            CliUpdateSourceRowPresentation(value = "", official = true),
            cliUpdateSourceRowPresentation(""),
        )
        assertEquals(
            CliUpdateSourceRowPresentation(value = "", official = true),
            cliUpdateSourceRowPresentation("   "),
        )
        assertEquals(
            CliUpdateSourceRowPresentation(
                value = "https://updates.example.test/releases",
                official = false,
            ),
            cliUpdateSourceRowPresentation("https://updates.example.test/releases"),
        )
    }

    @Test
    fun `proxy secrets are masked by default and blank values stay empty`() {
        assertEquals("••••••••", cliSecretDisplay("synthetic-secret", revealed = false))
        assertEquals("synthetic-secret", cliSecretDisplay("synthetic-secret", revealed = true))
        assertEquals("", cliSecretDisplay("", revealed = false))
        assertEquals("", cliSecretDisplay("   ", revealed = true))
    }

    @Test
    fun `proxy password visibility actions have paired English and Russian labels`() {
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")

        assertTrue(english.contains("name=\"cli_secret_action_show\">show password</string>"))
        assertTrue(english.contains("name=\"cli_secret_action_hide\">hide password</string>"))
        assertTrue(russian.contains("name=\"cli_secret_action_show\">показать пароль</string>"))
        assertTrue(russian.contains("name=\"cli_secret_action_hide\">скрыть пароль</string>"))
    }

    @Test
    fun `blank edit draft preserves the stored password until replacement text is entered`() {
        val untouched = cliSecretDraftChange(nextDraft = "", previouslyChanged = false)
        val typed = cliSecretDraftChange(nextDraft = "new-synthetic", previouslyChanged = false)
        val clearedAfterTyping = cliSecretDraftChange(nextDraft = "", previouslyChanged = true)

        assertFalse(untouched.changed)
        assertNull(untouched.replacement)
        assertTrue(typed.changed)
        assertEquals("new-synthetic", typed.replacement)
        assertTrue(clearedAfterTyping.changed)
        assertEquals("", clearedAfterTyping.replacement)
    }

    @Test
    fun `proxy password actions are ordered safe and shared by local and LAN screens`() {
        val secret = source("main/kotlin/com/foxhole/guard/ui/cli/components/CliSecretRow.kt")
        val lan = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliLanProxySubScreen.kt")
        val local = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliRoutingModeSection.kt")
        val eye = source("main/res/drawable/lin_eye.xml")
        val eyeOff = source("main/res/drawable/lin_eye_off.xml")
        val viewModel = source("main/kotlin/com/foxhole/guard/ui/HomeViewModelProxySettingsSupport.kt")
        val lanCopy = viewModel
            .substringAfter("internal fun HomeViewModel.onCopyLanProxyPassword()")
            .substringBefore("internal fun HomeViewModel.onLanProxySurfaceModeSelected")
        val reveal = secret.indexOf("R.drawable.lin_eye_off else R.drawable.lin_eye")
        val copy = secret.indexOf("icon = R.drawable.lin_copy")
        val change = secret.indexOf("icon = R.drawable.lin_edit")

        assertTrue(reveal in 0 until copy)
        assertTrue(copy in 0 until change)
        assertEquals(2, Regex("enabled = hasValue").findAll(secret).count())
        assertTrue(secret.contains("SECRET_ACTION_SIZE = 48.dp"))
        assertTrue(secret.contains("role = Role.Button"))
        assertTrue(secret.contains("contentDescription = description"))
        assertTrue(secret.contains("android.content.extra.IS_SENSITIVE"))
        assertTrue(secret.contains("rememberSaveable(prompt, value.isBlank())"))
        assertTrue(secret.contains("onClick = { revealed = !revealed }"))
        assertTrue(secret.contains("editDraft by remember(prompt) { mutableStateOf(\"\") }"))
        assertTrue(secret.contains("value = editDraft"))
        assertTrue(secret.contains("change.replacement?.let(onValueChange)"))
        assertFalse(secret.contains("collectIsPressedAsState"))
        assertFalse(secret.contains("onLongClick"))
        assertFalse(secret.contains("cli_secret_action_reveal"))
        assertTrue(eye.contains("M9 12a3 3"))
        assertTrue(eyeOff.contains("M3 3l18 18"))
        assertFalse(secret.contains("emitInfo"))
        assertFalse(secret.contains("recordStructured"))
        assertTrue(lan.contains("onCopy = viewModel::onCopyLanProxyPassword"))
        assertTrue(lanCopy.contains("password.isBlank()"))
        assertTrue(lanCopy.contains("android.content.extra.IS_SENSITIVE"))
        assertFalse(lanCopy.contains("recordStructured"))
        assertTrue(lan.contains("CliSecretRow("))
        assertTrue(local.contains("CliSecretRow("))
        assertTrue(lan.indexOf("if (lan.allowLanAccess)") < lan.indexOf("CliLanProxyAuthPanel("))
        assertTrue(local.indexOf("if (surfaces.auth.enabled)") < local.indexOf("CliSecretRow("))
    }

    private fun source(relative: String): String =
        listOf(
            File("src", relative),
            File("app/src", relative),
            File("../app/src", relative),
        ).first(File::isFile).readText()
}
