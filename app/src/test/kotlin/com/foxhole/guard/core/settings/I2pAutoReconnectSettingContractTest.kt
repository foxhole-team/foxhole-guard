package com.foxhole.guard.core.settings

import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.shouldAutoReconnectAfterVpnDisconnect
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class I2pAutoReconnectSettingContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `legacy i2p settings keep carrier auto reconnect enabled`() {
        val decoded = json.decodeFromString<I2pSettings>("""{"enabled":true}""")

        assertTrue(decoded.autoReconnectAfterVpnDisconnect)
    }

    @Test
    fun `disabled carrier auto reconnect survives serialization`() {
        val encoded = json.encodeToString(I2pSettings(autoReconnectAfterVpnDisconnect = false))
        val decoded = json.decodeFromString<I2pSettings>(encoded)

        assertFalse(decoded.autoReconnectAfterVpnDisconnect)
    }

    @Test
    fun `carrier reconnect requires permission engagement and opt in`() {
        assertTrue(
            I2pSettings(
                enabled = true,
                engaged = true,
                autoReconnectAfterVpnDisconnect = true,
            ).shouldAutoReconnectAfterVpnDisconnect(),
        )
        assertFalse(
            I2pSettings(
                enabled = false,
                engaged = true,
                autoReconnectAfterVpnDisconnect = true,
            ).shouldAutoReconnectAfterVpnDisconnect(),
        )
        assertFalse(
            I2pSettings(
                enabled = true,
                engaged = false,
                autoReconnectAfterVpnDisconnect = true,
            ).shouldAutoReconnectAfterVpnDisconnect(),
        )
        assertFalse(
            I2pSettings(
                enabled = true,
                engaged = true,
                autoReconnectAfterVpnDisconnect = false,
            ).shouldAutoReconnectAfterVpnDisconnect(),
        )
    }

    @Test
    fun `repository view model and i2p screen wire the same preference`() {
        val repository = source("core/settings/SettingsRepositoryI2p.kt")
        val viewModel = source("ui/HomeViewModelI2pSupport.kt")
        val screen = source("ui/cli/settings/CliI2pSubScreen.kt")

        assertTrue(repository.contains("copy(autoReconnectAfterVpnDisconnect = value)"))
        assertTrue(viewModel.contains("updateI2pAutoReconnectAfterVpnDisconnect(value)"))
        assertTrue(screen.contains("checked = settings.autoReconnectAfterVpnDisconnect"))
        assertTrue(screen.contains("onToggle = viewModel::onI2pAutoReconnectChanged"))
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/guard/$relativePath"),
            File("../app/src/main/kotlin/com/foxhole/guard/$relativePath"),
        ).first(File::isFile).readText()
}
