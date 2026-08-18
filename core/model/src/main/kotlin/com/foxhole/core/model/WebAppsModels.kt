package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

const val WEB_APPS_POLL_DEFAULT_MINUTES = 5

/** Allowed watchdog poll intervals; normalisation snaps any value to the nearest. */
val WEB_APPS_POLL_OPTIONS: List<Int> = listOf(1, 5, 15, 30)

/** Per-WebView route. DEFAULT follows the global availability policy; every strict route fails closed. */
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

// The web apps module: HTML5 apps in a full-screen frame plus a notification watchdog. Android
// WebView has no real Web Push, so the "push service" polls sites with an invisible WebView, and it
// runs only behind a raised guard: pushServiceEnabled is valid only with the firewall on, an
// invariant held by Settings.normalized().
@Serializable
@Immutable
data class WidgetKindAppearance(
    val blackBackground: Boolean = true,
    val alphaPercent: Int = 100,
    val outline: Boolean = true,
)

@Serializable
@Immutable
data class WidgetDefaultsSettings(
    val blackBackground: Boolean = true,
    val alphaPercent: Int = 100,
    val status: WidgetKindAppearance? = null,
    val webApps: WidgetKindAppearance? = null,
    val foxAnimationEnabled: Boolean = true,
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
    // Show the webapps screen and its dock glyph, between map and stats.
    val dockScreenEnabled: Boolean = false,
    val pollIntervalMinutes: Int = WEB_APPS_POLL_DEFAULT_MINUTES,
    // Legacy storage name. In the UI this is expressed as "block access without a tunnel";
    // explicit DIRECT apps bypass it, strict VPN/TOR/I2P routes are always fail-closed.
    val isolationEnabled: Boolean = false,
)
