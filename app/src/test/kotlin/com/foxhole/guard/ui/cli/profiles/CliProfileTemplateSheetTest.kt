package com.foxhole.guard.ui.cli.profiles

import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProfileTemplateSheetTest {
    @Test
    fun `the grid ends every row full whenever the count allows it`() {
        assertEquals(3, cliTemplateGridColumns(9))
        assertEquals(3, cliTemplateGridColumns(6))
        assertEquals(2, cliTemplateGridColumns(8))
        assertEquals(2, cliTemplateGridColumns(4))
        assertEquals(2, cliTemplateGridColumns(2))
        assertEquals(3, cliTemplateGridColumns(7))
    }

    @Test
    fun `the offered templates fill their rows and stay within the supported set`() {
        val columns = cliTemplateGridColumns(CLI_NEW_OUTBOUND_TYPES.size)

        assertEquals(0, CLI_NEW_OUTBOUND_TYPES.size % columns)
        CLI_NEW_OUTBOUND_TYPES.forEach { type ->
            assertTrue(
                "the template list offers $type, which has no protocol of its own",
                cliProtocolHintForType(type) != ProtocolHint.CUSTOM_CONFIG,
            )
        }
    }

    @Test
    fun `amneziawg has a dedicated full width template above the regular grid`() {
        val importer =
            projectFile("core/importer/src/main/kotlin/com/foxhole/core/importer/ProfileImportNodeSupport.kt")
                .readText()
        val supportedSchemes =
            importer
                .substringAfter("private val SUPPORTED_SHARE_URI_SCHEMES =")
                .substringBefore(")")

        assertTrue(supportedSchemes.contains("\"amneziawg\""))
        assertTrue(supportedSchemes.contains("\"awg\""))
        assertFalse(CLI_NEW_OUTBOUND_TYPES.contains("amneziawg"))
        assertFalse(CLI_NEW_OUTBOUND_TYPES.contains("awg"))
        assertTrue(CLI_NEW_OUTBOUND_TYPES.contains("wireguard"))
        assertTrue(CLI_STRUCTURED_OUTBOUND_TYPES.contains(CLI_AMNEZIA_WIREGUARD_TYPE))
        assertEquals(ProtocolHint.WIREGUARD, cliProtocolHintForType(CLI_AMNEZIA_WIREGUARD_TYPE))

        val sheet =
            projectFile("app/src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileTemplateSheet.kt")
                .readText()
        assertTrue(sheet.contains("label = \"AmneziaWG\""))
        val amneziaButton = sheet
            .substringAfter("label = \"AmneziaWG\"")
            .substringBefore("Spacer(modifier = Modifier.height(CliSpacing.sm))")
        assertTrue(amneziaButton.contains("color = colors.accent"))
        assertFalse(amneziaButton.contains("icon ="))
        assertFalse(amneziaButton.contains("colors.info"))
        assertTrue(sheet.contains("centered = true"))
        assertTrue(sheet.contains("centeredIconLeading = true"))
        assertTrue(sheet.contains("selectedType = CLI_AMNEZIA_WIREGUARD_TYPE"))
    }

    @Test
    fun `amneziawg draft is a wireguard endpoint with editable obfuscation`() {
        val draft = cliBlankOutbound(CLI_AMNEZIA_WIREGUARD_TYPE)

        assertEquals(CLI_WIREGUARD_TYPE, draft.cliOutboundType())
        assertTrue("amnezia" in draft)
        val labels = cliProtoFields(CLI_AMNEZIA_WIREGUARD_TYPE, "").map(CliProtoField::label)
        assertTrue(labels.containsAll(listOf("amnezia.Jc", "amnezia.Jmin", "amnezia.Jmax", "amnezia.H4")))
    }

    @Test
    fun `long protocol names fall back to the share-uri scheme the importer speaks`() {
        assertEquals("ss", cliTemplateTileLabel("shadowsocks"))
        assertEquals("hy2", cliTemplateTileLabel("hysteria2"))
        assertEquals("wg", cliTemplateTileLabel("wireguard"))
        assertEquals("vless", cliTemplateTileLabel("vless"))
    }

    @Test
    fun `continue appears only after selection while close stays in the shared sheet footer`() {
        val sheet =
            projectFile("app/src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileTemplateSheet.kt")
                .readText()

        assertTrue(sheet.contains("updateTransition(targetState = selectedType"))
        assertTrue(sheet.contains("R.string.cli_wizard_continue"))
        val continueButton = sheet.substringAfter("R.string.cli_wizard_continue").substringBefore("}")
        assertTrue(continueButton.contains("color = colors.ok"))
        assertFalse(continueButton.contains("filled = true"))
        assertTrue(sheet.contains("R.string.cli_common_no_cancel"))
        assertTrue(sheet.contains("color = colors.ok"))
        assertTrue(sheet.contains("dismissAfter { onContinue(type) }"))
        assertTrue(sheet.contains("cliTemplateContinueWeight"))
        assertTrue(sheet.contains("translationX = slide.toPx()"))
        assertTrue(sheet.contains(".graphicsLayer {"))
        assertTrue(sheet.contains("alpha = reveal"))
        assertTrue(sheet.contains("translationY = (1f - reveal)"))
        assertFalse(sheet.contains("padding(top = reveal"))
        assertFalse(sheet.contains("offset(y = reveal"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File(path.removePrefix("app/")), File("../$path"))
            .first(File::exists)
}
