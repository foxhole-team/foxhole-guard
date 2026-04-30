package com.foxhole.beta.ui

internal enum class SmartStartProtocolStatus {
    AVAILABLE,
    SLOW,
    RECENTLY_FAILED,
    UNAVAILABLE,
    NO_DATA,
    DISABLED,
}

internal enum class LatencyQuality {
    FAST,
    NORMAL,
    SLOW,
    VERY_SLOW,
    UNAVAILABLE,
    FAILED,
}

internal enum class SmartStartProtocolDisabledReason {
    MANUAL_OFF,
}

internal data class SmartStartProtocolPresentation(
    val status: SmartStartProtocolStatus,
    val disabledReason: SmartStartProtocolDisabledReason? = null,
)

internal data class SmartStartProtocolMenuLayout(
    val showHeader: Boolean,
    val showDetailedMetrics: Boolean,
    val showCompactStatusRows: Boolean,
)

internal fun resolveSmartStartProtocolPresentation(
    included: Boolean,
    latencyMs: Long?,
    latencyDown: Boolean,
    latencyUnavailable: Boolean,
): SmartStartProtocolPresentation =
    when {
        !included ->
            SmartStartProtocolPresentation(
                status = SmartStartProtocolStatus.DISABLED,
                disabledReason = SmartStartProtocolDisabledReason.MANUAL_OFF,
            )
        latencyDown ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.RECENTLY_FAILED)
        latencyUnavailable ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.UNAVAILABLE)
        latencyMs == null ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.NO_DATA)
        classifyVpnLatency(latencyMs = latencyMs, failed = false, unavailable = false) in
            setOf(LatencyQuality.SLOW, LatencyQuality.VERY_SLOW) ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.SLOW)
        else ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.AVAILABLE)
    }

internal fun resolveSmartStartProtocolMenuLayout(showMetricsTable: Boolean): SmartStartProtocolMenuLayout =
    SmartStartProtocolMenuLayout(
        showHeader = showMetricsTable,
        showDetailedMetrics = showMetricsTable,
        showCompactStatusRows = !showMetricsTable,
    )

internal fun classifyVpnLatency(
    latencyMs: Long?,
    failed: Boolean,
    unavailable: Boolean,
): LatencyQuality =
    when {
        failed -> LatencyQuality.FAILED
        unavailable || latencyMs == null -> LatencyQuality.UNAVAILABLE
        latencyMs <= VPN_FAST_MAX_MS -> LatencyQuality.FAST
        latencyMs <= VPN_NORMAL_MAX_MS -> LatencyQuality.NORMAL
        latencyMs <= VPN_SLOW_MAX_MS -> LatencyQuality.SLOW
        else -> LatencyQuality.VERY_SLOW
    }

internal fun boundedDisplayLatencyMs(latencyMs: Long): Long = latencyMs.coerceIn(1L, MAX_UI_LATENCY_MS)

private const val VPN_FAST_MAX_MS = 250L
private const val VPN_NORMAL_MAX_MS = 750L
private const val VPN_SLOW_MAX_MS = 1_500L
private const val MAX_UI_LATENCY_MS = 999L
