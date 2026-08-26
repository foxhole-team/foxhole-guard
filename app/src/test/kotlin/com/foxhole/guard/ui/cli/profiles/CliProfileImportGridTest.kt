package com.foxhole.guard.ui.cli.profiles

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProfileImportGridTest {
    @Test
    fun `v2raytun protocols adapt from three to five columns by width and label`() {
        val labels = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "wireguard")

        assertEquals(3, cliImportProtocolGridColumns(320.dp, labels))
        assertEquals(4, cliImportProtocolGridColumns(420.dp, labels))
        assertEquals(5, cliImportProtocolGridColumns(520.dp, labels))
        assertEquals(3, cliImportProtocolGridColumns(420.dp, labels + "exceptionally-long-protocol"))
    }

    @Test
    fun `import confirmation uses centered cells instead of a comma separated protocol line`() {
        val source = projectFile(
            "app/src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileDialogs.kt",
        ).readText()
        val panel = source
            .substringAfter("internal fun CliImportConfirmPanel(")
            .substringBefore("internal fun formatExpiryDate")

        assertTrue(panel.contains("CliImportProtocolGrid(protocols = protocols)"))
        assertTrue(panel.contains("contentAlignment = Alignment.Center"))
        assertTrue(panel.contains("textAlign = TextAlign.Center"))
        assertTrue(panel.contains("labels.chunked(columns)"))
        assertTrue(panel.contains("protocolHints\n            .map"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File(path.removePrefix("app/")), File("../$path"))
            .first(File::isFile)
}
