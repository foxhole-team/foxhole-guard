package com.foxhole.beta.ui

internal enum class SmartStartProtocolStatus {
    RECOMMENDED,
    SLOW,
    RECENTLY_FAILED,
    NO_DATA,
    DISABLED,
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
    recommended: Boolean,
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
        latencyDown || (latencyMs != null && latencyMs > SMART_START_SLOW_LATENCY_LIMIT_MS) ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.RECENTLY_FAILED)
        recommended ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.RECOMMENDED)
        latencyMs == null || latencyUnavailable ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.NO_DATA)
        else ->
            SmartStartProtocolPresentation(status = SmartStartProtocolStatus.SLOW)
    }

internal fun resolveSmartStartProtocolMenuLayout(showMetricsTable: Boolean): SmartStartProtocolMenuLayout =
    SmartStartProtocolMenuLayout(
        showHeader = showMetricsTable,
        showDetailedMetrics = showMetricsTable,
        showCompactStatusRows = !showMetricsTable,
    )

private const val SMART_START_SLOW_LATENCY_LIMIT_MS = 520L
