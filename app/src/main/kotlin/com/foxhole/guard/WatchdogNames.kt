package com.foxhole.guard

/**
 * The names of the two background watchdogs the app runs.
 *
 * Until they had names there was no way to talk about them: not in the README, not in a bug
 * report, and not in the Android notification settings the user is looking at while asking which
 * of these entries is which.
 *
 * Each watchdog is spelled two ways here, and the two are not interchangeable:
 *  - [WEB] / [GUARD] are the names themselves — what a person reads. They go where a human sees
 *    the service: the notification channel name in Android settings and the host recorded in the
 *    sealed journal. Written with spaces, like a name, and identical in every language because a
 *    name is not translated. The notification titles and bodies around them stay localized.
 *  - [WEB_ID] / [GUARD_ID] are the same names as identifiers, for the places a space is not
 *    allowed or would not survive: notification channel ids, WorkManager unique work names,
 *    ticker task ids and diagnostics tags.
 *
 * Changing any of these strings is a migration rather than a rename: an already installed app
 * keeps the notification channel and the unique periodic work it registered under the old id.
 */
internal object WatchdogNames {
    /**
     * Web apps watchdog: walks the enabled web apps on a schedule with a hidden WebView and
     * raises badges and notifications from what it finds.
     */
    const val WEB = "foxhole watchdog web"

    /** [WEB] as an identifier. */
    const val WEB_ID = "foxhole_watchdog_web"

    /**
     * Sentinel watchdog: event monitoring, the heartbeat of the background host, and the record
     * of app installs, removals and anomalies in the sealed journal.
     */
    const val GUARD = "foxhole watchdog guard"

    /** [GUARD] as an identifier. */
    const val GUARD_ID = "foxhole_watchdog_guard"
}
