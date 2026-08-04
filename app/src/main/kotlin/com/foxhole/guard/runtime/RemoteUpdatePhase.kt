package com.foxhole.guard.runtime

/**
 * Coarse progress phases a remote asset update (GeoIP database, DNS rule set) passes through, so a
 * shared "Update" control can show the same live status text ("Checking / Downloading / Verifying")
 * regardless of which asset is refreshing. Terminal outcomes (up-to-date, done, failed) come from
 * the update result, not this callback.
 */
enum class RemoteUpdatePhase {
    CHECKING,
    DOWNLOADING,
    VERIFYING,
}
