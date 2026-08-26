package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

const val WEB_APPS_POLL_DEFAULT_MINUTES = 5

val WEB_APPS_POLL_OPTIONS: List<Int> = listOf(1, 5, 15, 30)

@Serializable
enum class WebAppRoute {
    DEFAULT,
    DIRECT,
    VPN,
    TOR,
    I2P,
    BLOCK,
}

fun storedWebAppRoute(value: String?): WebAppRoute =
    runCatching { WebAppRoute.valueOf(value.orEmpty().uppercase()) }.getOrDefault(WebAppRoute.DEFAULT)

@Serializable
@Immutable
data class WidgetKindAppearance(
    val blackBackground: Boolean = true,
    val alphaPercent: Int = 100,
    val outline: Boolean = false,
)

@Serializable
enum class StatusWidgetLayoutMode(
    val persistedValue: String,
) {
    SIMPLE("simple"),
    EXPANDED("expanded"),
}

@Serializable
@Immutable
data class WidgetDefaultsSettings(
    val blackBackground: Boolean = true,
    val alphaPercent: Int = 100,
    val status: WidgetKindAppearance? = null,
    val webApps: WidgetKindAppearance? = null,
    val foxAnimationEnabled: Boolean = true,
    val statusLayoutMode: StatusWidgetLayoutMode = StatusWidgetLayoutMode.SIMPLE,
) {
    fun statusAppearance(): WidgetKindAppearance =
        status ?: WidgetKindAppearance(blackBackground = blackBackground, alphaPercent = alphaPercent)

    fun webAppsAppearance(): WidgetKindAppearance =
        webApps ?: WidgetKindAppearance(blackBackground = blackBackground, alphaPercent = alphaPercent)
}

@Serializable
@Immutable
data class WebAppsSettings(
    val enabled: Boolean = false,
    val pushServiceEnabled: Boolean = false,

    val dockScreenEnabled: Boolean = false,
    val pollIntervalMinutes: Int = WEB_APPS_POLL_DEFAULT_MINUTES,

    val isolationEnabled: Boolean = false,
)
