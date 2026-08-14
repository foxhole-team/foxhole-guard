package com.foxhole.guard.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

internal class WidgetUiContractTest {
    @Test
    fun `content widgets span two through four columns and one through two rows`() {
        val status = source("main/res/xml/widget_status_info.xml")
        val webApps = source("main/res/xml/widget_webapps_info.xml")

        listOf(status, webApps).forEach { metadata ->
            assertTrue(metadata.contains("android:minWidth=\"110dp\""))
            assertTrue(metadata.contains("android:minResizeWidth=\"110dp\""))
        }
        assertTrue(status.contains("android:targetCellWidth=\"4\""))
        assertTrue(status.contains("android:maxResizeWidth=\"400dp\""))
        assertTrue(status.contains("android:minHeight=\"80dp\""))
        assertTrue(status.contains("android:minResizeHeight=\"80dp\""))
        assertTrue(status.contains("android:targetCellHeight=\"1\""))
        assertTrue(status.contains("android:maxResizeHeight=\"180dp\""))
        assertTrue(status.contains("android:resizeMode=\"horizontal|vertical\""))
        assertTrue(webApps.contains("android:maxResizeWidth=\"400dp\""))
        assertTrue(webApps.contains("android:resizeMode=\"horizontal|vertical\""))
    }

    @Test
    fun `connection widget adapts compact and table layouts without a redundant header label`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val metadata = source("main/res/xml/widget_status_info.xml")

        assertTrue(status.contains("SizeMode.Exact"))
        assertTrue(status.contains("LocalSize.current.height >= STATUS_WIDGET_EXPANDED_HEIGHT"))
        assertTrue(status.contains("StatusWidgetSummary("))
        assertTrue(status.contains("StatusWidgetFacts("))
        assertTrue(status.contains("Column(modifier = GlanceModifier.fillMaxWidth())"))
        assertTrue(status.contains("STATUS_WIDGET_EXPANDED_HEIGHT = 162.dp"))
        assertTrue(status.contains("STATUS_WIDGET_CONTROL_HEIGHT = 24.dp"))
        assertTrue(status.contains(".padding(horizontal = 12.dp, vertical = 8.dp)"))
        assertFalse(status.contains("sectionLabel = context.getString(R.string.widget_status_section)"))
        assertTrue(config.contains("WidgetPreviewKind.STATUS -> WidgetStatusConfigPreview"))
        assertTrue(config.contains("WIDGET_ALPHA_SEGMENTS = 11"))
        assertTrue(config.contains("index * WIDGET_ALPHA_STEP_PERCENT"))
        assertFalse(config.contains("label = \"-\""))
        assertFalse(metadata.contains("android:previewImage=\"@drawable/fox_hero_1\""))
    }

    @Test
    fun `both widgets share the inset dashed pixel frame and quick settings header`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")
        val frame = source("main/res/drawable/widget_frame.xml")

        assertTrue(status.contains("WidgetPixelFrame(background = background, outlined = outlined)"))
        assertTrue(webApps.contains("WidgetPixelFrame(background = background, outlined = outlined)"))
        assertTrue(status.contains("WidgetBrandHeader("))
        assertTrue(webApps.contains("WidgetBrandHeader("))
        assertTrue(chrome.contains("R.drawable.ic_qs_tile"))
        assertTrue(chrome.contains("WIDGET_FRAME_INSET = 4.dp"))
        assertTrue(frame.contains("android:dashWidth=\"7dp\""))
        assertTrue(frame.contains("android:dashGap=\"4dp\""))
        assertTrue(frame.contains("android:radius=\"8dp\""))
        assertFalse(status.contains("R.drawable.ic_launcher_foreground"))
        assertFalse(webApps.contains("R.drawable.ic_launcher_foreground"))
    }

    @Test
    fun `dashed frames keep the panel radius and stay inside their canvas`() {
        val modalFrame = source("main/kotlin/com/foxhole/guard/ui/cli/components/CliModalFrame.kt")
        val previewFrame = source("main/res/drawable/widget_preview_bg.xml")

        assertEquals(2, Regex("drawRoundRect\\(").findAll(modalFrame).count())
        assertTrue(modalFrame.contains("CLI_DASHED_FRAME_RADIUS = 8.dp"))
        assertTrue(modalFrame.contains("val inset = cell / 2f"))
        assertTrue(previewFrame.contains("android:radius=\"8dp\""))
    }

    @Test
    fun `connection widget exposes outlined start stop restart and refresh controls`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val receiver = source("main/kotlin/com/foxhole/guard/widget/StatusWidgetCommands.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")
        val preview = source("main/res/layout/widget_status_preview.xml")

        assertTrue(status.contains("R.drawable.widget_start_button"))
        assertTrue(status.contains("R.drawable.widget_stop_button"))
        assertTrue(status.contains("R.drawable.widget_restart_button"))
        assertTrue(status.contains("presentation.primaryActive"))
        assertTrue(status.contains("StatusWidgetCommandReceiver.ACTION_REFRESH"))
        assertTrue(status.contains("statusWidgetRefreshSpinnerFrame(refreshFrame)"))
        assertTrue(status.contains("R.drawable.widget_refresh_pixel"))
        assertTrue(status.contains("Row(modifier = GlanceModifier.fillMaxWidth())"))
        assertFalse(status.contains("WIDGET_REFRESH_SPINNER"))
        assertFalse(status.contains("widget_refresh_button"))
        assertTrue(status.contains("ColorProvider(background.icon)"))
        listOf("start", "stop", "restart", "refreshing").forEach { action ->
            assertTrue(
                source("main/res/drawable/widget_${action}_button.xml")
                    .contains("android:radius=\"6dp\""),
            )
        }
        assertTrue(chrome.contains("modifier = GlanceModifier.fillMaxWidth()"))
        assertTrue(preview.contains("@drawable/widget_stop_button"))
        assertTrue(preview.contains("@drawable/widget_restart_button"))
        assertFalse(preview.contains("<Space"))
        assertFalse(preview.contains("widget_refresh_button"))
        assertTrue(preview.contains("@drawable/widget_refresh_pixel"))
        assertTrue(receiver.contains("renderFrame = { StatusWidget().updateAll(application) }"))
        assertTrue(receiver.contains("FoxholeConnectionServiceContract.ACTION_DISCONNECT"))
        assertTrue(receiver.contains("suppressLocalGuard = false"))
        assertTrue(receiver.contains("FoxholeConnectionServiceContract.ACTION_RELOAD"))

        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")
        assertTrue(english.contains("name=\"widget_status_restart\">Restart</string>"))
        assertTrue(russian.contains("name=\"widget_status_restart\">Рестарт</string>"))
    }

    @Test
    fun `widget configuration succeeds only after a bound widget renders`() {
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val provider = source("main/kotlin/com/foxhole/guard/widget/ConfigurableWidgetProvider.kt")

        assertTrue(config.contains("if (glanceId == null)"))
        assertTrue(config.contains("if (rendered.isFailure)"))
        assertTrue(config.indexOf("if (rendered.isFailure)") < config.indexOf("RESULT_OK"))
        assertTrue(config.contains("if (configuredProvider(appWidgetId) != provider)"))
        assertTrue(config.contains("val providerClass = runCatching"))
        assertFalse(config.contains("else {\n                        WebAppsWidget()"))
        assertTrue(provider.contains("else -> null"))
    }

    @Test
    fun `ordinary widgets keep per-instance opacity and outline state`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")

        assertTrue(config.contains("getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)"))
        assertTrue(config.contains("updateAppWidgetState(this@WidgetConfigActivity, glanceId)"))
        assertTrue(config.contains("prefs[WIDGET_ALPHA_KEY]"))
        assertTrue(config.contains("prefs[WIDGET_OUTLINE_KEY]"))
        assertTrue(config.contains("CliToggleRow("))
        assertTrue(config.contains("if (outlined) base.cliDashedBorder(colors.accent) else base"))
        assertTrue(status.contains("widgetOutlineEnabled(prefs)"))
        assertTrue(webApps.contains("widgetOutlineEnabled(prefs)"))
        assertTrue(chrome.contains("if (outlined)"))
    }

    @Test
    fun `settings exposes the launcher configure flow for every widget provider`() {
        val settings = source("main/kotlin/com/foxhole/guard/ui/HomeViewModelWidgetsSupport.kt")
        val screen = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliWidgetsSubScreen.kt")
        val connection = source("main/res/xml/widget_status_info.xml")
        val webApps = source("main/res/xml/widget_webapps_info.xml")
        val status = source("main/res/xml/widget_fox_status_info.xml")

        assertTrue(settings.contains("HomeWidgetKind.CONNECTION -> StatusWidgetReceiver::class.java"))
        assertTrue(settings.contains("HomeWidgetKind.WEB_APPS -> WebAppsWidgetReceiver::class.java"))
        assertTrue(settings.contains("HomeWidgetKind.STATUS -> FoxStatusWidgetReceiver::class.java"))
        assertTrue(settings.contains("requestPinAppWidget(ComponentName(app, provider), null, null)"))
        assertTrue(settings.contains("runCatching"))
        assertTrue(settings.contains("return\n        }"))
        assertTrue(settings.contains("launcher widget request failed"))
        assertEquals(3, Regex("viewModel\\.onAddHomeWidget\\(").findAll(screen).count())
        assertTrue(connection.contains("android:configure=\"com.foxhole.guard.widget.WidgetConfigActivity\""))
        assertTrue(webApps.contains("android:configure=\"com.foxhole.guard.widget.WidgetConfigActivity\""))
        assertTrue(
            status.contains(
                "android:configure=\"com.foxhole.guard.widget.FoxStatusWidgetConfigActivity\"",
            ),
        )
    }

    @Test
    fun `dashed info notes own a small external vertical inset`() {
        val cliText = source("main/kotlin/com/foxhole/guard/ui/cli/components/CliText.kt")
        val noteStart = cliText.indexOf("internal fun CliDashedInfoNote(")
        val noteEnd = cliText.indexOf("private fun CliInfoLine(", startIndex = noteStart)
        val note = cliText.substring(noteStart, noteEnd)

        assertTrue(note.contains("outerVerticalPadding: Dp = CliSpacing.xs"))
        assertTrue(note.contains(".padding(vertical = outerVerticalPadding)"))
        assertTrue(
            note.indexOf(".padding(vertical = outerVerticalPadding)") <
                note.indexOf(".cliDashedBorder(colors.note)"),
        )
        assertFalse(note.contains("CliSpinner"))
        assertFalse(note.contains("Progress"))
    }

    @Test
    fun `widget picker previews and settings use the localized product copy`() {
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")
        val manifest = source("main/AndroidManifest.xml")
        val settings = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliWidgetsSubScreen.kt")
        val statusMetadata = source("main/res/xml/widget_status_info.xml")
        val webAppsMetadata = source("main/res/xml/widget_webapps_info.xml")
        val foxMetadata = source("main/res/xml/widget_fox_status_info.xml")
        val statusPreview = source("main/res/layout/widget_status_preview.xml")
        val webAppsPreview = source("main/res/layout/widget_webapps_preview.xml")
        val foxPreview = source("main/res/layout/widget_fox_status_preview.xml")

        assertTrue(english.contains(">Quick access with info panel</string>"))
        assertTrue(english.contains(">Displays and controls the current connection state</string>"))
        assertTrue(english.contains(">Web apps</string>"))
        assertTrue(english.contains(">Quick access to Web apps without opening the main app</string>"))
        assertTrue(english.contains(">Quick access</string>"))
        assertTrue(english.contains(">Tap the fox to start or stop the last active connection</string>"))

        assertTrue(russian.contains(">Быстрый доступ с инфо-панелью</string>"))
        assertFalse(russian.contains(">Быстрый доступ c инфо-панелью</string>"))
        assertTrue(russian.contains(">Отображает текущее состояние подключения и управляет им</string>"))
        assertTrue(russian.contains(">Web-приложения</string>"))
        assertTrue(
            russian.contains(
                ">Быстрый доступ к Web-приложениям без перехода в основное приложение</string>",
            ),
        )
        assertTrue(russian.contains(">Быстрый доступ</string>"))
        assertTrue(
            russian.contains(
                ">Тап по лисе запускает и останавливает последнее активное подключение</string>",
            ),
        )

        assertTrue(manifest.contains("android:label=\"@string/cli_status_widget_label\""))
        assertTrue(manifest.contains("android:label=\"@string/cli_webapps_widget_label\""))
        assertTrue(manifest.contains("android:label=\"@string/fox_status_widget_label\""))
        assertTrue(statusMetadata.contains("android:description=\"@string/cli_status_widget_description\""))
        assertTrue(webAppsMetadata.contains("android:description=\"@string/cli_webapps_widget_description\""))
        assertTrue(foxMetadata.contains("android:description=\"@string/fox_status_widget_description\""))

        assertTrue(settings.contains("R.string.cli_status_widget_description"))
        assertTrue(settings.contains("R.string.cli_webapps_widget_description"))
        assertTrue(settings.contains("R.string.fox_status_widget_description"))
        assertEquals(3, Regex("CliElbowLine\\(").findAll(settings).count())
        assertTrue(statusPreview.contains("@string/cli_status_widget_label"))
        assertTrue(webAppsPreview.contains("@string/cli_webapps_widget_label"))
        assertTrue(foxPreview.contains("@string/fox_status_widget_label"))
    }

    @Test
    fun `image status widget has fixed height horizontal resize and no decorative frame`() {
        val widget = source("main/kotlin/com/foxhole/guard/widget/FoxStatusWidget.kt")
        val metadata = source("main/res/xml/widget_fox_status_info.xml")
        val manifest = source("main/AndroidManifest.xml")

        assertTrue(metadata.contains("android:minResizeHeight=\"110dp\""))
        assertTrue(metadata.contains("android:maxResizeHeight=\"110dp\""))
        assertTrue(metadata.contains("android:resizeMode=\"horizontal\""))
        assertTrue(metadata.contains("android:minResizeWidth=\"110dp\""))
        assertTrue(metadata.contains("android:maxResizeWidth=\"400dp\""))
        assertFalse(widget.contains("WidgetPixelFrame("))
        assertTrue(widget.contains("StatusWidgetCommandReceiver.ACTION_TOGGLE"))
        assertTrue(widget.contains("FOX_STATUS_ANIMATION_KEY] ?: true"))
        assertTrue(widget.contains("generation.incrementAndGet()"))
        assertTrue(widget.contains("while (generation.get() == ownedGeneration)"))
        assertTrue(widget.contains("animatedWidgetIds(context)"))
        assertTrue(widget.contains("getAppWidgetState(context, PreferencesGlanceStateDefinition, id)"))
        assertTrue(widget.contains("FOX_STATUS_FRAME_DURATION_MS = 320L"))
        assertTrue(manifest.contains(".widget.FoxStatusWidgetReceiver"))
        assertTrue(manifest.contains("@xml/widget_fox_status_info"))
    }

    @Test
    fun `image status widget ships six png frames and no gif`() {
        (1..6).forEach { frame ->
            val image = ImageIO.read(file("main/res/drawable-nodpi/fhg_status_frame_$frame.png"))
            assertEquals(1254, image.width)
            assertEquals(1254, image.height)
        }
        val drawableDirectory = file("main/res/drawable-nodpi")
        assertFalse(drawableDirectory.listFiles().orEmpty().any { it.extension.equals("gif", ignoreCase = true) })
    }

    @Test
    fun `quick settings silhouette fills canonical optical bounds without clipping`() {
        val image = ImageIO.read(file("main/res/drawable-nodpi/ic_qs_tile.png"))
        assertEquals(120, image.width)
        assertEquals(120, image.height)

        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if ((image.getRGB(x, y) ushr 24) != 0) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        val fillWidth = (maxX - minX + 1).toFloat() / image.width
        val fillHeight = (maxY - minY + 1).toFloat() / image.height
        assertTrue(fillWidth in 0.72f..0.90f)
        assertTrue(fillHeight in 0.72f..0.90f)
        assertTrue(minX >= 8 && minY >= 8)
        assertTrue(maxX <= image.width - 9 && maxY <= image.height - 9)
    }

    private fun source(relative: String): String = file(relative).readText()

    private fun file(relative: String): File =
        sequenceOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File("../app/src/$relative"),
        ).first(File::exists)
}
