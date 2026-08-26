package com.foxhole.guard.runtime

enum class RemoteUpdatePhase {
    CHECKING,
    DOWNLOADING,
    VERIFYING,
}

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
