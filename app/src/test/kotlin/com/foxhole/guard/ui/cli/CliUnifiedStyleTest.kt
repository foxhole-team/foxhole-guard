package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.ui.cli.components.CLI_DISCLOSURE_GLYPH_OFFSET
import com.foxhole.guard.ui.cli.components.CLI_ICON_DRAW_SCALE
import com.foxhole.guard.ui.cli.components.CLI_ROW_LEADING_ICON_OFFSET
import com.foxhole.guard.ui.cli.components.CLI_ROW_LEADING_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CLI_SCREEN_HEADER_ICON_SIZE
import com.foxhole.guard.ui.cli.components.CliScreenHeaderTitleTopPadding
import com.foxhole.guard.ui.cli.components.cliShimmerHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliUnifiedStyleTest {
    @Test
    fun `ordinary text keeps the modern typography ladder`() {
        val type = cliTypography()
        val mono = cliTypography(pixelArtEnabled = false)

        assertEquals(referenceSp(15f), type.body.fontSize)
        assertEquals(referenceSp(13f), type.small.fontSize)
        assertEquals(referenceSp(14f), type.title.fontSize)
        assertEquals(referenceSp(16f), type.display.fontSize)
        assertEquals(pixelHeadingSp(15f), type.button.fontSize)
        assertEquals(referenceSp(15f), mono.button.fontSize)
        assertTrue(type.body.lineHeight > type.body.fontSize)
        assertTrue(type.button.lineHeight > type.button.fontSize)
        assertEquals(type.body.fontFamily, type.small.fontFamily)
        assertEquals(type.body.fontFamily, type.title.fontFamily)
        assertEquals(type.body.fontFamily, type.display.fontFamily)
        assertNotEquals(type.body.fontFamily, type.button.fontFamily)
        assertEquals(mono.body.fontFamily, mono.button.fontFamily)
        assertEquals(
            type.button.fontSize.value * CLI_PIXEL_FONT_CAP_HEIGHT_RATIO,
            mono.button.fontSize.value * CLI_MONO_FONT_CAP_HEIGHT_RATIO,
            0.001f,
        )
    }

    @Test
    fun `semantic headings use pixel faces without changing ordinary roles`() {
        val ordinary = cliTypography()
        val englishTitle = cliTitleStyleFor("Settings")
        val russianTitle = cliTitleStyleFor("Настройки")
        val englishDisplay = cliDisplayStyleFor("FoxHole Guard")
        val russianDisplay = cliDisplayStyleFor("Состояние")
        val monoTitle = cliTitleStyleFor("SETTINGS", pixelArtEnabled = false)
        val monoDisplay = cliDisplayStyleFor("SETTINGS", pixelArtEnabled = false)

        assertEquals(pixelHeadingSp(14f), englishTitle.fontSize)
        assertEquals(pixelHeadingSp(14f), russianTitle.fontSize)
        assertEquals(pixelHeadingSp(16f), englishDisplay.fontSize)
        assertEquals(pixelHeadingSp(16f), russianDisplay.fontSize)
        assertNotEquals(ordinary.body.fontFamily, englishTitle.fontFamily)
        assertNotEquals(ordinary.body.fontFamily, russianTitle.fontFamily)
        assertEquals(englishTitle.fontFamily, russianTitle.fontFamily)
        assertEquals(englishDisplay.fontFamily, russianDisplay.fontFamily)
        assertEquals(englishTitle.fontFamily, ordinary.button.fontFamily)
        assertEquals(ordinary.body.fontFamily, cliTypography().body.fontFamily)
        assertEquals(ordinary.button.fontFamily, cliTypography().button.fontFamily)
        assertEquals(ordinary.body.fontFamily, cliTypography(pixelArtEnabled = false).button.fontFamily)
        assertEquals(ordinary.title.fontFamily, monoTitle.fontFamily)
        assertEquals(
            ordinary.display.fontFamily,
            monoDisplay.fontFamily,
        )
        assertEquals(referenceSp(14f), monoTitle.fontSize)
        assertEquals(referenceSp(16f), monoDisplay.fontSize)
        assertEquals(englishTitle.lineHeight, monoTitle.lineHeight)
        assertEquals(englishDisplay.lineHeight, monoDisplay.lineHeight)
        assertEquals(
            englishTitle.fontSize.value * CLI_PIXEL_FONT_CAP_HEIGHT_RATIO,
            monoTitle.fontSize.value * CLI_MONO_FONT_CAP_HEIGHT_RATIO,
            0.001f,
        )
        assertEquals(
            englishDisplay.fontSize.value * CLI_PIXEL_FONT_CAP_HEIGHT_RATIO,
            monoDisplay.fontSize.value * CLI_MONO_FONT_CAP_HEIGHT_RATIO,
            0.001f,
        )
    }

    @Test
    fun `one bilingual pixel face covers mixed localized headings in uppercase`() {
        assertEquals(
            cliTitleStyleFor("Settings").fontFamily,
            cliTitleStyleFor("FoxHole — Настройки").fontFamily,
        )
        assertEquals("FOXHOLE GUARD", cliHeadingText("FoxHole Guard"))
        assertEquals("ТЕКУЩАЯ ИНФОРМАЦИЯ", cliHeadingText("Текущая информация"))
    }

    @Test
    fun `single icon system has one scale and aligned metric slots`() {
        assertEquals(0.92f, CLI_ICON_DRAW_SCALE)
        assertEquals(18.dp, CLI_SCREEN_HEADER_ICON_SIZE)
        assertEquals(16.dp, CLI_ROW_LEADING_ICON_SIZE)
        assertEquals(0.dp, CLI_ROW_LEADING_ICON_OFFSET)
        assertEquals(0.dp, CLI_DISCLOSURE_GLYPH_OFFSET)
        assertEquals(10.dp, CliScreenHeaderTitleTopPadding)
    }

    @Test
    fun `shimmer sweep always darkens in light and dark palettes`() {
        listOf(Color(0xFFFF8A20), Color(0xFF586A78)).forEach { base ->
            val highlight = cliShimmerHighlight(base)
            assertTrue(highlight.red <= base.red)
            assertTrue(highlight.green <= base.green)
            assertTrue(highlight.blue <= base.blue)
            assertNotEquals(base, highlight)
        }
    }

    @Test
    fun `only outline icon resources remain bundled`() {
        val resourceRoot = projectFile("src/main/res")
        val pixelAssets = resourceRoot.walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.name.startsWith("pix_") || file.name == "widget_refresh_pixel.xml" }
            .toList()
        val outlineAssets = resourceRoot.resolve("drawable")
            .listFiles { file -> file.name.startsWith("lin_") && file.extension == "xml" }
            .orEmpty()

        assertTrue(pixelAssets.toString(), pixelAssets.isEmpty())
        assertEquals(43, outlineAssets.size)
    }

    @Test
    fun `dock labels are uppercase and follow the global button font role`() {
        val source = projectFile(
            "src/main/kotlin/com/foxhole/guard/ui/cli/components/CliHintBar.kt",
        ).readText()

        assertTrue(source.contains("semanticLabel.uppercase()"))
        assertTrue(source.contains("CliType.button.copy("))
        assertTrue(source.contains("TextAutoSize.StepBased("))
        assertTrue(source.contains("fontSize = cliFontSizeForMode("))
        assertTrue(source.contains("cliScaledSp(if (compact) 10f else 11f)"))
        assertTrue(source.contains("lineHeight = cliScaledSp(if (compact) 12f else 13f)"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path")).first(File::exists)

    private fun referenceSp(base: Float) = (base * 1.1f * CLI_UNIFIED_METRIC_SCALE).sp

    private fun pixelHeadingSp(base: Float) =
        cliPixelFontSizeForMonoSp(base * 1.1f * CLI_UNIFIED_METRIC_SCALE)
}
