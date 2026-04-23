package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExportDocumentSupportTest {
    @Test
    fun `single selected protocol exports plain json file`() {
        val directory = Files.createTempDirectory("foxhole-profile-export-single").toFile()

        val export =
            createProfileExportArtifact(
                targetDir = directory,
                payloads =
                    listOf(
                        ProfileExportPayload(
                            sourceProfileName = "Main Profile",
                            displayName = "Hysteria2",
                            protocolOptionId = "hy2",
                            configJson = """{"type":"hysteria2"}""",
                        ),
                    ),
                nowProvider = { 1_000L },
            )

        assertEquals("Main-Profile-Hysteria2.json", export.fileName)
        assertEquals("application/json", export.mimeType)
        assertEquals("""{"type":"hysteria2"}""" + "\n", export.file.readText())
    }

    @Test
    fun `multiple selected protocols export zip with one json per protocol`() {
        val directory = Files.createTempDirectory("foxhole-profile-export-zip").toFile()

        val export =
            createProfileExportArtifact(
                targetDir = directory,
                payloads =
                    listOf(
                        ProfileExportPayload("Smart Config", "VLESS", "vless", """{"type":"vless"}"""),
                        ProfileExportPayload("Smart Config", "Shadowsocks", "ss", """{"type":"shadowsocks"}"""),
                    ),
                nowProvider = { 2_000L },
            )

        assertEquals("Smart-Config-export-2000.zip", export.fileName)
        assertEquals("application/zip", export.mimeType)
        ZipFile(export.file).use { zip ->
            assertEquals("""{"type":"vless"}""" + "\n", zip.getInputStream(zip.getEntry("Smart-Config-VLESS.json")).reader().readText())
            assertEquals("""{"type":"shadowsocks"}""" + "\n", zip.getInputStream(zip.getEntry("Smart-Config-Shadowsocks.json")).reader().readText())
        }
    }

    @Test
    fun `multiple profiles export zip with source profile names in entries`() {
        val directory = Files.createTempDirectory("foxhole-profile-export-multi-profile").toFile()

        val export =
            createProfileExportArtifact(
                targetDir = directory,
                payloads =
                    listOf(
                        ProfileExportPayload("Alpha Profile", "VLESS", "vless", """{"type":"vless"}"""),
                        ProfileExportPayload("Beta/Profile", "WireGuard", "wg", """{"type":"wireguard"}"""),
                    ),
                nowProvider = { 3_000L },
            )

        assertEquals("foxhole-profiles-export-3000.zip", export.fileName)
        assertEquals("application/zip", export.mimeType)
        ZipFile(export.file).use { zip ->
            assertEquals("""{"type":"vless"}""" + "\n", zip.getInputStream(zip.getEntry("Alpha-Profile-VLESS.json")).reader().readText())
            assertEquals("""{"type":"wireguard"}""" + "\n", zip.getInputStream(zip.getEntry("Beta-Profile-WireGuard.json")).reader().readText())
        }
    }

    @Test
    fun `export choices expose all supported smart profile protocols`() {
        val choices =
            exportableProfileChoices(
                Profile(
                    id = 1L,
                    name = "Smart",
                    sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                    secretRef = "secret",
                    protocolHint = ProtocolHint.SING_BOX,
                    lastUpdatedAt = null,
                    lastEtag = null,
                    protocolOptions =
                        listOf(
                            ProfileProtocolOption("vless", "VLESS", ProtocolHint.VLESS),
                            ProfileProtocolOption("carrier", "Carrier", ProtocolHint.SING_BOX),
                            ProfileProtocolOption("ss", "Shadowsocks", ProtocolHint.SHADOWSOCKS),
                        ),
                    selectedProtocolOptionId = "vless",
                    isActive = true,
                ),
            )

        assertEquals(listOf("vless", "ss"), choices.map(ProfileExportChoice::selectionKey))
        assertEquals(listOf("VLESS", "Shadowsocks"), choices.map(ProfileExportChoice::displayName))
        assertTrue(choices.all { it.protocolOptionId != null })
    }
}
