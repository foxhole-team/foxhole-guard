package com.foxhole.guard.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

internal class WidgetUiContractTest {
    @Test
    fun `status widget preserves its wide simple status while web apps can shrink to two columns`() {
        val status = source("main/res/xml/widget_status_info.xml")
        val webApps = source("main/res/xml/widget_webapps_info.xml")

        assertTrue(status.contains("android:minWidth=\"240dp\""))
        assertTrue(status.contains("android:minResizeWidth=\"240dp\""))
        assertTrue(webApps.contains("android:minWidth=\"110dp\""))
        assertTrue(webApps.contains("android:minResizeWidth=\"110dp\""))
        assertTrue(status.contains("android:targetCellWidth=\"4\""))
        assertTrue(status.contains("android:maxResizeWidth=\"400dp\""))
        assertTrue(status.contains("android:minHeight=\"80dp\""))
        assertTrue(status.contains("android:minResizeHeight=\"48dp\""))
        assertTrue(status.contains("android:targetCellHeight=\"1\""))
        assertTrue(status.contains("android:maxResizeHeight=\"180dp\""))
        assertTrue(status.contains("android:resizeMode=\"horizontal|vertical\""))
        assertTrue(webApps.contains("android:maxResizeWidth=\"400dp\""))
        assertTrue(webApps.contains("android:resizeMode=\"horizontal|vertical\""))
    }

    @Test
    fun `connection widget adapts compact and table layouts without a redundant header label`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val typography = source("main/kotlin/com/foxhole/guard/widget/WidgetTypography.kt")
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val metadata = source("main/res/xml/widget_status_info.xml")

        assertTrue(status.contains("SizeMode.Exact"))
        assertTrue(status.contains("LocalSize.current.height >= STATUS_WIDGET_EXPANDED_HEIGHT"))
        assertTrue(status.contains("StatusWidgetSummary("))
        assertTrue(status.contains("StatusWidgetFacts("))
        assertTrue(status.contains("Column(modifier = GlanceModifier.fillMaxWidth())"))
        assertTrue(status.contains("STATUS_WIDGET_EXPANDED_HEIGHT = 162.dp"))
        assertTrue(status.contains("STATUS_WIDGET_CONTROL_HEIGHT = 32.dp"))
        assertTrue(status.contains(".padding(horizontal = 12.dp, vertical = 8.dp)"))
        assertTrue(status.contains("height(statusWidgetSimpleSurfaceHeight(metrics))"))
        val simple = status.substringAfter("private fun StatusWidgetSimpleContent(")
            .substringBefore("private fun StatusWidgetSimpleLogo(")
        assertTrue(simple.contains(".fillMaxSize()"))
        assertTrue(simple.contains(".cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)"))
        assertTrue(simple.contains(".clickable(actionStartActivity<CliMainActivity>())"))
        assertTrue(typography.contains("max(pixelPaint.measureText(fittedText), monoPaint.measureText(fittedText))"))
        assertTrue(status.contains("countryName?.trim()?.takeIf(String::isNotBlank)"))
        assertFalse(status.contains("sectionLabel = context.getString(R.string.widget_status_section)"))
        assertTrue(config.contains("WidgetPreviewKind.STATUS -> WidgetStatusConfigPreview"))
        assertTrue(config.contains("Slider("))
        assertTrue(config.contains("steps = WIDGET_OPACITY_SLIDER_STEPS"))
        assertTrue(config.contains("value.roundToInt()"))
        assertTrue(config.contains("contentDescription = opacityLabel"))
        assertTrue(config.contains("stateDescription = \"\$normalizedPercent%\""))
        assertFalse(config.contains("WIDGET_ALPHA_SEGMENTS"))
        assertFalse(config.contains("label = \"-\""))
        assertTrue(metadata.contains("android:previewLayout=\"@layout/widget_status_preview\""))
        assertFalse(metadata.contains("android:previewImage=\"@drawable/fox_hero_1\""))
    }

    @Test
    fun `connection widget keeps expanded implementation but exposes only simplified layout`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val commands = source("main/kotlin/com/foxhole/guard/widget/StatusWidgetCommands.kt")
        val mode = source("main/kotlin/com/foxhole/guard/widget/StatusWidgetLayoutMode.kt")
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val settings = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliWidgetsSubScreen.kt")
        val connectButton = source("main/res/drawable/widget_simple_action_connect.xml")
        val stopButton = source("main/res/drawable/widget_simple_action_stop.xml")
        val splitDevice = source("main/res/drawable/widget_simple_device_vpn_tor.xml")
        val logoCircle = source("main/res/drawable/widget_simple_logo_circle.xml")
        val launcherPreview = source("main/res/layout/widget_status_preview.xml")
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")
        val typography = source("main/kotlin/com/foxhole/guard/widget/WidgetTypography.kt")
        val simpleContent =
            slice(
                status,
                "private fun StatusWidgetSimpleContent(",
                "private fun StatusWidgetSimpleLogo(",
            )
        val simpleLogo =
            slice(
                status,
                "private fun StatusWidgetSimpleLogo(",
                "private fun StatusWidgetSimpleStatusBlock(",
            )
        val simpleStatusBlock =
            slice(
                status,
                "private fun StatusWidgetSimpleStatusBlock(",
                "private fun StatusWidgetSimpleStatusText(",
            )
        val simpleLocation =
            slice(
                status,
                "private fun StatusWidgetSimpleLocation(",
                "private fun StatusWidgetSimplePowerButton(",
            )
        val configSimplePreview =
            slice(
                config,
                "private fun WidgetSimpleStatusConfigPreview(",
                "private fun WidgetConfigPreviewHeader(",
            )
        val settingsSimplePreview =
            slice(
                settings,
                "private fun CliSimpleStatusWidgetPreview(",
                "private fun CliWebAppsWidgetPreview(",
            )
        val simplePowerButton =
            slice(
                status,
                "private fun StatusWidgetSimplePowerButton(",
                "internal fun statusWidgetSimpleMetrics(",
            )
        val refreshCommand =
            slice(
                commands,
                "ACTION_REFRESH ->",
                "ACTION_TOGGLE ->",
            )
        val identityRefresh =
            slice(
                commands,
                "private suspend fun refreshIdentities(",
                "companion object {",
            )
        val configLayoutSelector =
            slice(
                config,
                "private fun WidgetStatusLayoutModeRow(",
                "private fun WidgetBackgroundRow(",
            )
        val settingsLayoutSelector =
            slice(
                settings,
                "private fun CliStatusWidgetLayoutRow(",
                "private fun CliFoxWidgetPanel(",
            )

        assertTrue(status.contains("initialStatusWidgetLayoutMode("))
        assertTrue(status.contains("durableDefault = settings.widgets.statusLayoutMode"))
        assertTrue(status.contains("StatusWidgetSimpleContent("))
        assertTrue(status.contains("StatusWidgetExpandedContent("))
        assertTrue(status.contains("R.string.widget_status_turn_on"))
        assertTrue(status.contains("R.string.widget_status_turn_off"))
        assertTrue(status.contains("statusWidgetCountryFlagCode(identity?.countryCode)"))
        assertTrue(status.contains("refreshPhase == StatusWidgetRefreshPhase.IDLE"))
        assertFalse(status.contains("simpleRouteTokens()"))
        assertFalse(status.contains("StatusWidgetSimpleRouteToken"))
        assertTrue(status.contains("simpleIdentityTokens()"))
        assertTrue(status.contains("simpleDeviceTone()"))
        assertTrue(status.contains("simplePowerAction()"))
        assertTrue(simpleContent.contains("val status = presentation.simpleStatus()"))
        assertTrue(simpleContent.contains("context.getString(status.labelRes)"))
        assertTrue(simpleContent.contains("color = status.color"))
        assertTrue(simpleContent.contains("StatusWidgetSimpleIdentityContent("))
        assertFalse(simpleContent.contains("copy(identity = null)"))
        assertTrue(status.contains("statusWidgetSimpleIdentityStatusRes("))
        assertTrue(status.contains("connection = presentation.connection"))
        assertTrue(status.contains("text = context.getString(refreshStatusRes)"))
        assertTrue(simpleStatusBlock.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(simpleStatusBlock.contains("StatusWidgetSimpleStatusText("))
        assertFalse(simpleStatusBlock.contains("Image("))
        assertTrue(
            simpleLocation.indexOf("ImageProvider(countryFlag.drawableId)") <
                simpleLocation.indexOf("text = code ?: WIDGET_DASH"),
        )
        assertTrue(status.contains("R.drawable.lin_device"))
        assertTrue(status.contains("statusFontSize = 11"))
        assertTrue(status.contains("statusFontSize = 14"))
        assertTrue(status.contains("STATUS_WIDGET_SIMPLE_WIDE_WIDTH = 240.dp"))
        assertTrue(status.contains("R.drawable.widget_simple_logo_circle"))
        assertEquals(2, Regex("\\.size\\(metrics\\.circleSize\\)").findAll(status).count())
        assertEquals(
            2,
            Regex("ColorFilter\\.tint\\(ColorProvider\\(background\\.icon\\)\\)")
                .findAll(simpleLogo)
                .count(),
        )
        assertEquals(
            1,
            Regex("StatusWidgetCommandReceiver\\.ACTION_REFRESH").findAll(simpleLogo).count(),
        )
        assertFalse(simpleLogo.contains("StatusWidgetCommandReceiver.ACTION_TOGGLE"))
        assertFalse(simpleLogo.contains("actionStartActivity<CliMainActivity>()"))
        assertEquals(
            1,
            Regex("actionStartActivity<CliMainActivity>\\(\\)").findAll(simpleContent).count(),
        )
        assertFalse(simpleContent.contains("StatusWidgetCommandReceiver.ACTION_TOGGLE"))
        assertEquals(
            1,
            Regex("StatusWidgetCommandReceiver\\.ACTION_TOGGLE")
                .findAll(simplePowerButton)
                .count(),
        )
        assertFalse(simplePowerButton.contains("StatusWidgetCommandReceiver.ACTION_REFRESH"))
        assertFalse(simplePowerButton.contains("actionStartActivity<CliMainActivity>()"))
        assertTrue(simplePowerButton.contains("R.drawable.widget_simple_action_connect"))
        assertTrue(simplePowerButton.contains("R.drawable.widget_simple_action_stop"))
        assertTrue(simplePowerButton.contains("StatusWidgetSimplePowerAction.CONNECT -> WIDGET_OK"))
        assertTrue(simplePowerButton.contains("StatusWidgetSimplePowerAction.STOP -> WIDGET_ERROR"))
        assertTrue(refreshCommand.contains("refreshIdentities(application, dependencies, snapshot)"))
        assertTrue(identityRefresh.contains("refreshDeviceIpInfo(WIDGET_IP_INFO_FETCH_MODE)"))
        assertTrue(identityRefresh.contains("refreshIpInfo(WIDGET_IP_INFO_FETCH_MODE)"))
        assertTrue(identityRefresh.contains("refreshTorRouteIpInfo(WIDGET_IP_INFO_FETCH_MODE)"))
        assertTrue(identityRefresh.contains("supervisorScope"))
        assertTrue(identityRefresh.contains("refreshWidgetIdentity"))
        assertTrue(identityRefresh.contains("withTimeoutOrNull"))
        assertTrue(identityRefresh.contains("publishManualIdentityRefresh("))
        assertTrue(identityRefresh.contains("StatusWidget().updateAll(application)"))
        assertTrue(mode.contains("else -> StatusWidgetLayoutMode.SIMPLE"))
        assertTrue(mode.contains("stringPreferencesKey(\"status_layout_mode\")"))
        assertTrue(mode.contains("listOf(StatusWidgetLayoutMode.SIMPLE)"))
        assertTrue(mode.contains("activeStatusWidgetLayoutMode("))
        assertTrue(mode.contains("StatusWidgetLayoutMode.EXPANDED.persistedValue"))
        assertTrue(config.contains("WidgetStatusLayoutModeRow("))
        assertTrue(config.contains("WidgetSimpleStatusConfigPreview("))
        assertTrue(config.contains("mutableStateOf(StatusWidgetLayoutMode.SIMPLE)"))
        assertTrue(config.contains("activeStatusWidgetLayoutMode(layoutMode).persistedValue"))
        assertTrue(configLayoutSelector.contains("SELECTABLE_STATUS_WIDGET_LAYOUT_MODES"))
        assertFalse(configLayoutSelector.contains("StatusWidgetLayoutMode.EXPANDED"))
        assertTrue(settingsLayoutSelector.contains("SELECTABLE_STATUS_WIDGET_LAYOUT_MODES"))
        assertFalse(settingsLayoutSelector.contains("StatusWidgetLayoutMode.EXPANDED"))
        assertTrue(connectButton.contains("android:shape=\"oval\""))
        assertTrue(connectButton.contains("@color/cli_ok"))
        assertTrue(stopButton.contains("@color/cli_err"))
        assertTrue(splitDevice.contains("#FF7FB34A"))
        assertTrue(splitDevice.contains("#FFFF7A1A"))
        assertEquals(2, Regex("<clip-path").findAll(splitDevice).count())
        assertTrue(logoCircle.contains("android:shape=\"oval\""))
        assertTrue(logoCircle.contains("@android:color/white"))
        assertFalse(logoCircle.contains("@color/cli_accent"))
        assertTrue(launcherPreview.contains("@drawable/widget_simple_logo_circle"))
        assertTrue(launcherPreview.contains("@drawable/widget_simple_action_connect"))
        assertTrue(launcherPreview.contains("@drawable/lin_device"))
        assertFalse(launcherPreview.contains("@string/widget_status_vpn_profile"))
        assertTrue(config.contains("pixelType.copy(fontSize = 14.sp, lineHeight = 15.sp)"))
        assertTrue(config.contains("smallType.copy(fontSize = 13.sp, lineHeight = 15.sp)"))
        assertTrue(typography.contains("renderStyledWidgetText("))
        assertTrue(typography.contains("R.font.tiny5_regular"))
        assertTrue(typography.contains("R.font.jetbrains_mono_bold"))
        assertTrue(status.contains("LocalWidgetPixelArtEnabled provides settings.ui.pixelArtEnabled"))
        assertTrue(configSimplePreview.contains("R.string.cli_home_status_connected_vpn"))
        assertFalse(configSimplePreview.contains("R.drawable.lin_shield"))
        assertTrue(
            configSimplePreview.indexOf("painterResource(R.drawable.flag_nl)") <
                configSimplePreview.indexOf("text = WIDGET_PREVIEW_COUNTRY_CODE"),
        )
        assertTrue(settingsSimplePreview.contains("R.string.cli_home_status_connected_vpn"))
        assertFalse(settingsSimplePreview.contains("R.drawable.lin_shield"))
        assertTrue(
            settingsSimplePreview.indexOf("painterResource(R.drawable.flag_nl)") <
                settingsSimplePreview.indexOf("text = SIMPLE_PREVIEW_COUNTRY_CODE"),
        )
        assertTrue(config.contains("CliType.small"))
        assertTrue(config.contains("painterResource(R.drawable.lin_power)"))
        assertTrue(config.contains(".border(2.dp, colors.err, CircleShape)"))
        assertTrue(config.contains("colorFilter = ColorFilter.tint(colors.err)"))
        assertTrue(config.contains(".size(34.dp)"))
        assertTrue(config.contains(".border(2.dp, textTone, CircleShape)"))
        assertTrue(english.contains(">Simplified</string>"))
        assertTrue(english.contains(">Expanded</string>"))
        assertTrue(english.contains(">updating status</string>"))
        assertTrue(english.contains("name=\"widget_status_unprotected\">Traffic is unprotected</string>"))
        assertTrue(russian.contains(">Упрощённый</string>"))
        assertTrue(russian.contains(">Расширенный</string>"))
        assertTrue(russian.contains(">обновление статуса</string>"))
        assertTrue(russian.contains("name=\"widget_status_unprotected\">Трафик не защищён</string>"))
    }

    @Test
    fun `both widgets share the inset dashed pixel frame and quick settings header`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")
        val frame = source("main/res/drawable/widget_frame.xml")

        assertTrue(status.contains("WidgetFrame(background = background, outlined = outlined)"))
        assertTrue(webApps.contains("WidgetFrame(background = background, outlined = outlined)"))
        assertTrue(status.contains("WidgetBrandHeader("))
        assertTrue(webApps.contains("WidgetBrandHeader("))
        assertTrue(chrome.contains("R.drawable.ic_qs_tile"))
        assertTrue(chrome.contains("WIDGET_FRAME_INSET = 4.dp"))
        assertTrue(chrome.contains("WIDGET_SURFACE_CORNER_RADIUS = 8.dp"))
        assertTrue(chrome.contains(".cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)"))
        assertTrue(status.contains(".cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)"))
        assertTrue(webApps.contains(".cornerRadius(WIDGET_SURFACE_CORNER_RADIUS)"))
        assertTrue(frame.contains("android:dashWidth=\"7dp\""))
        assertTrue(frame.contains("android:dashGap=\"4dp\""))
        assertTrue(frame.contains("android:radius=\"8dp\""))
        assertFalse(status.contains("R.drawable.ic_launcher_foreground"))
        assertFalse(webApps.contains("R.drawable.ic_launcher_foreground"))
    }

    @Test
    fun `modal keeps its dashed frame while default widget previews omit the outline`() {
        val modalFrame = source("main/kotlin/com/foxhole/guard/ui/cli/components/CliModalFrame.kt")
        val previewFrame = source("main/res/drawable/widget_preview_bg.xml")

        assertEquals(2, Regex("drawRoundRect\\(").findAll(modalFrame).count())
        assertTrue(modalFrame.contains("CLI_DASHED_FRAME_RADIUS = CliRadius.panel"))
        assertTrue(modalFrame.contains("val inset = cell / 2f"))
        assertTrue(previewFrame.contains("android:radius=\"8dp\""))
        assertFalse(previewFrame.contains("<stroke"))
        assertFalse(previewFrame.contains("android:dashWidth"))
    }

    @Test
    fun `widget drawable colors mirror the semantic fixed palette`() {
        val colors = source("main/res/values/colors.xml")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")

        assertTrue(colors.contains("<color name=\"cli_accent\">#7FB34A</color>"))
        assertTrue(colors.contains("<color name=\"cli_ok\">#7FB34A</color>"))
        assertTrue(colors.contains("<color name=\"cli_info\">#2FD9F2</color>"))
        assertTrue(colors.contains("<color name=\"cli_err\">#E0562A</color>"))
        assertTrue(webApps.contains("internal val WIDGET_ACCENT = CliColors().accent"))
        assertTrue(webApps.contains("internal val WIDGET_OK = CliColors().ok"))
        assertTrue(webApps.contains("CliLightColors.faint"))
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
        assertTrue(status.contains("R.drawable.lin_update"))
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
        assertTrue(preview.contains("@drawable/widget_simple_action_connect"))
        assertTrue(preview.contains("@string/widget_status_unprotected"))
        assertFalse(preview.contains("@drawable/widget_start_button"))
        assertFalse(preview.contains("@drawable/widget_stop_button"))
        assertFalse(preview.contains("@drawable/widget_restart_button"))
        assertFalse(preview.contains("<Space"))
        assertFalse(preview.contains("widget_refresh_button"))
        assertTrue(preview.contains("@drawable/ic_qs_tile"))
        assertTrue(preview.contains("@string/widget_status_refresh"))
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
    fun `launcher widget config uses dropdowns for layout and theme while opacity stays exact`() {
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val english = source("main/res/values/strings.xml")
        val russian = source("main/res/values-ru/strings.xml")

        assertEquals(2, Regex("CliDropdownRow\\(").findAll(config).count())
        assertFalse(config.contains("CliChip("))
        assertTrue(config.contains("selectedId = layoutMode.persistedValue"))
        assertTrue(config.contains("selectedId = isBlack.toString()"))
        assertTrue(config.contains("Slider("))
        assertTrue(config.contains("onAlphaChange(value.roundToInt())"))
        assertTrue(config.contains("mutableIntStateOf(WIDGET_DEFAULT_OPACITY_PERCENT)"))
        assertTrue(config.contains("var outlined by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(english.contains("name=\"cli_widget_config_bg_dark\">Dark</string>"))
        assertTrue(english.contains("name=\"cli_widget_config_bg_light\">Light</string>"))
        assertTrue(russian.contains("name=\"cli_widget_config_bg_dark\">Тёмный</string>"))
        assertTrue(russian.contains("name=\"cli_widget_config_bg_light\">Светлый</string>"))
    }

    @Test
    fun `ordinary widgets keep per-instance opacity and outline state`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")
        val config = source("main/kotlin/com/foxhole/guard/widget/WidgetConfigActivity.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")
        val defaults = source("main/kotlin/com/foxhole/guard/widget/WidgetAppearanceDefaults.kt")
        val pickerPreview = source("main/res/drawable/widget_preview_bg.xml")

        assertTrue(config.contains("getAppWidgetState(this, PreferencesGlanceStateDefinition, glanceId)"))
        assertTrue(config.contains("updateAppWidgetState(this@WidgetConfigActivity, glanceId)"))
        assertTrue(config.contains("prefs[WIDGET_ALPHA_KEY]"))
        assertTrue(config.contains("prefs[WIDGET_OUTLINE_KEY]"))
        assertTrue(config.contains("CliToggleRow("))
        assertTrue(config.contains("if (outlined) base.cliDashedBorder(colors.accent) else base"))
        assertTrue(status.contains("widgetOutlineEnabled(prefs, defaults)"))
        assertTrue(webApps.contains("widgetOutlineEnabled(prefs, defaults)"))
        assertTrue(chrome.contains("if (outlined)"))
        assertTrue(defaults.contains("outline = false"))
        assertFalse(pickerPreview.contains("<stroke"))
    }

    @Test
    fun `in app widget opacity offers half or a validated exact custom value`() {
        val settings = source("main/kotlin/com/foxhole/guard/ui/cli/settings/CliWidgetsSubScreen.kt")
        val opacity = source("main/kotlin/com/foxhole/guard/widget/WidgetOpacityPresentation.kt")

        assertEquals(4, Regex("CliDropdownRow\\(").findAll(settings).count())
        assertTrue(settings.contains("widgetOpacityPresentation(alphaPercent)"))
        assertTrue(settings.contains("CliInputModal("))
        assertTrue(settings.contains("widgetOpacityDraft(raw)"))
        assertTrue(settings.contains("it in WIDGET_OPACITY_MIN_PERCENT..WIDGET_OPACITY_MAX_PERCENT"))
        assertTrue(opacity.contains("WIDGET_DEFAULT_OPACITY_PERCENT = 50"))
        assertTrue(opacity.contains("choices = listOf("))
        assertTrue(opacity.contains("id = WIDGET_OPACITY_CUSTOM_OPTION_ID"))
        assertFalse(opacity.contains("WIDGET_OPACITY_STEP_PERCENT"))
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
        assertTrue(screen.contains("kind = HomeWidgetKind.CONNECTION,"))
        assertTrue(screen.contains("kind = HomeWidgetKind.WEB_APPS,"))
        assertEquals(1, Regex("onAddHomeWidget\\(kind\\)").findAll(kindPanel(screen)).count())
        assertEquals(1, Regex("onAddHomeWidget\\(HomeWidgetKind\\.STATUS\\)").findAll(foxPanel(screen)).count())
        assertEquals(2, Regex("viewModel\\.onAddHomeWidget\\(").findAll(screen).count())
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
                note.indexOf(".then(frame)"),
        )
        assertTrue(note.contains("Modifier.border(1.dp, noteColor.copy(alpha = 0.6f)"))
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
        assertTrue(kindPanel(settings).contains("infoText = description"))
        assertTrue(foxPanel(settings).contains("infoText = stringResource(R.string.fox_status_widget_description)"))
        assertEquals(0, Regex("CliElbowLine\\(").findAll(settings).count())
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
        assertFalse(widget.contains("WidgetFrame("))
        assertTrue(widget.contains("StatusWidgetCommandReceiver.ACTION_TOGGLE"))
        assertTrue(widget.contains("FOX_STATUS_ANIMATION_KEY] ?: settings.widgets.foxAnimationEnabled"))
        assertTrue(widget.contains("generation.incrementAndGet()"))
        assertTrue(widget.contains("while (generation.get() == ownedGeneration)"))
        assertTrue(widget.contains("animatedWidgetIds(context)"))
        assertTrue(widget.contains("getAppWidgetState(context, PreferencesGlanceStateDefinition, id)"))
        assertTrue(widget.contains("FOX_STATUS_FRAME_DURATION_MS = 320L"))
        assertTrue(manifest.contains(".widget.FoxStatusWidgetReceiver"))
        assertTrue(manifest.contains("@xml/widget_fox_status_info"))
        assertFalse(widget.contains("StyledWidgetText("))
        assertFalse(widget.contains("LocalWidgetPixelArtEnabled"))
    }

    @Test
    fun `text widgets follow the shared app pixel art setting without changing their surfaces`() {
        val status = source("main/kotlin/com/foxhole/guard/widget/StatusWidget.kt")
        val webApps = source("main/kotlin/com/foxhole/guard/widget/WebAppsWidget.kt")
        val chrome = source("main/kotlin/com/foxhole/guard/widget/WidgetChrome.kt")
        val typography = source("main/kotlin/com/foxhole/guard/widget/WidgetTypography.kt")

        assertTrue(status.contains("LocalWidgetPixelArtEnabled provides settings.ui.pixelArtEnabled"))
        assertTrue(webApps.contains("LocalWidgetPixelArtEnabled provides settings.ui.pixelArtEnabled"))
        assertTrue(status.contains("StyledWidgetText("))
        assertTrue(webApps.contains("StyledWidgetText("))
        assertTrue(chrome.contains("StyledWidgetText("))
        assertTrue(typography.contains("staticCompositionLocalOf { true }"))
        assertTrue(typography.contains("if (pixelArtEnabled) pixelPaint else monoPaint"))
        assertTrue(typography.contains("val measuredWidth = max(pixelPaint.measureText(fittedText)"))
        assertTrue(chrome.contains("modifier: GlanceModifier = GlanceModifier.fillMaxSize()"))
        assertTrue(webApps.contains("WidgetFrame(background = background, outlined = outlined)"))
    }

    @Test
    fun `image status widget ships six bounded png frames and no gif`() {
        (1..6).forEach { frame ->
            val image = ImageIO.read(file("main/res/drawable-nodpi/fhg_status_frame_$frame.png"))
            assertEquals(627, image.width)
            assertEquals(627, image.height)
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

    private fun kindPanel(screen: String): String =
        slice(screen, "private fun CliWidgetKindPanel(", "private fun CliFoxWidgetPanel(")

    private fun foxPanel(screen: String): String =
        slice(screen, "private fun CliFoxWidgetPanel(", "private fun CliStatusWidgetPreview(")

    private fun slice(source: String, from: String, until: String): String {
        val start = source.indexOf(from)
        assertTrue(start >= 0)
        val end = source.indexOf(until, startIndex = start)
        assertTrue(end > start)
        return source.substring(start, end)
    }

    private fun source(relative: String): String = file(relative).readText()

    private fun file(relative: String): File =
        sequenceOf(
            File("src/$relative"),
            File("app/src/$relative"),
            File("../app/src/$relative"),
        ).first(File::exists)
}
