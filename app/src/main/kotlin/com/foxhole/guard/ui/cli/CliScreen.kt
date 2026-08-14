package com.foxhole.guard.ui.cli

/**
 * The flat screens of the CLI experiment - state-based navigation, no NavHost.
 * Declaration order is the dock order; the bottom dock renders one pixel icon per
 * screen (see CliHintBar), so tabs carry no text hints and fit any locale.
 * WEBAPPS is the only conditional tab: it renders only while the web apps module
 * and its dock toggle are both on (CliApp filters the dock list and falls back
 * to HOME when the tab disappears under the user).
 */
enum class CliScreen {
    HOME,

    // Operating mode before profiles: the mode decides whether a VPN profile is needed at all,
    // and the dock reads left to right in the same order the home screen states the route.
    APPS,
    PROFILES,
    MAP,
    WEBAPPS,
    STATS,
    SETTINGS,
}
