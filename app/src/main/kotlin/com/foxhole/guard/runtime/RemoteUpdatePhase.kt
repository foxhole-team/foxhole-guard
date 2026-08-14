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

/** Byte-accurate progress for the currently downloaded data-set payload. */
data class RemoteDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() =
            if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            }
}
