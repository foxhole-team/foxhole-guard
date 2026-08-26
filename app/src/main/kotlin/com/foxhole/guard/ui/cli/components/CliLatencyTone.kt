package com.foxhole.guard.ui.cli.components

internal enum class CliLatencyTone {
    UNAVAILABLE,
    NORMAL,
    DEGRADED,
    ELEVATED,
    POOR,
}

internal enum class CliLatencyKind {
    CONNECT,
    PROTOCOL,
    HOME,
}

internal fun cliLatencyTone(valueMs: Long?, kind: CliLatencyKind): CliLatencyTone {
    if (valueMs == null || valueMs < 0L) return CliLatencyTone.UNAVAILABLE
    return when (kind) {
        CliLatencyKind.CONNECT -> cliConnectTone(valueMs)
        CliLatencyKind.PROTOCOL -> cliProtocolLatencyTone(valueMs)
        CliLatencyKind.HOME -> cliHomeLatencyTone(valueMs)
    }
}

private fun cliConnectTone(valueMs: Long): CliLatencyTone = when {
    valueMs <= CLI_CONNECT_NORMAL_MAX_MS -> CliLatencyTone.NORMAL
    valueMs <= CLI_CONNECT_DEGRADED_MAX_MS -> CliLatencyTone.DEGRADED
    else -> CliLatencyTone.POOR
}

private fun cliProtocolLatencyTone(valueMs: Long): CliLatencyTone = when {
    valueMs <= CLI_PROTOCOL_LATENCY_NORMAL_MAX_MS -> CliLatencyTone.NORMAL
    valueMs <= CLI_PROTOCOL_LATENCY_DEGRADED_MAX_MS -> CliLatencyTone.DEGRADED
    valueMs <= CLI_PROTOCOL_LATENCY_ELEVATED_MAX_MS -> CliLatencyTone.ELEVATED
    else -> CliLatencyTone.POOR
}

private fun cliHomeLatencyTone(valueMs: Long): CliLatencyTone = when {
    valueMs <= CLI_HOME_LATENCY_NORMAL_MAX_MS -> CliLatencyTone.NORMAL
    valueMs <= CLI_HOME_LATENCY_DEGRADED_MAX_MS -> CliLatencyTone.DEGRADED
    else -> CliLatencyTone.POOR
}

internal const val CLI_CONNECT_NORMAL_MAX_MS = 2_000L
internal const val CLI_CONNECT_DEGRADED_MAX_MS = 5_000L
private const val CLI_PROTOCOL_LATENCY_NORMAL_MAX_MS = 150L
private const val CLI_PROTOCOL_LATENCY_DEGRADED_MAX_MS = 300L
private const val CLI_PROTOCOL_LATENCY_ELEVATED_MAX_MS = 600L
private const val CLI_HOME_LATENCY_NORMAL_MAX_MS = 150L
private const val CLI_HOME_LATENCY_DEGRADED_MAX_MS = 400L
