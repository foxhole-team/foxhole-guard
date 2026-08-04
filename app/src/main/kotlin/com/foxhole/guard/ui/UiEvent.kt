package com.foxhole.guard.ui

/** Lightweight one-shot feedback consumed by the terminal event line. */
internal data class FoxholeBannerEvent(
    val message: String,
    val tone: FoxholeBannerTone,
    val actionLabel: String? = null,
    val action: FoxholeBannerAction? = null,
    val durationMillis: Long? = null,
    val expiresAtElapsedMs: Long? = null,
)

internal enum class FoxholeBannerTone {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
}

internal enum class FoxholeBannerAction {
    ACCEPT_PROTOCOL_RECOMMENDATION,
    ACCEPT_NETWORK_RULE_PROFILE,
}
