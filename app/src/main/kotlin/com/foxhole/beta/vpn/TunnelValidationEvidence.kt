package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticEntry

internal data class TunnelValidationEvidence(
    val hasSuccessfulTunnelActivity: Boolean,
    val hasOutboundTunnelActivity: Boolean = hasSuccessfulTunnelActivity,
    val fatalRuntimeMessage: String? = null,
)

internal object TunnelValidationEvidenceClassifier {
    fun classify(
        entries: List<DiagnosticEntry>,
        sinceMs: Long,
    ): TunnelValidationEvidence {
        var hasSuccessfulTunnelActivity = false
        var hasOutboundTunnelActivity = false
        var fatalRuntimeMessage: String? = null

        entries.asSequence()
            .filter { entry -> entry.timestamp >= sinceMs }
            .forEach { entry ->
                val message = entry.message.lowercase()
                if (fatalRuntimeMessage == null && isFatalRuntimeEntry(tag = entry.tag, message = message)) {
                    fatalRuntimeMessage = entry.message
                }
                if (!hasSuccessfulTunnelActivity && isSuccessfulTunnelActivityEntry(tag = entry.tag, message = message)) {
                    hasSuccessfulTunnelActivity = true
                }
                if (!hasOutboundTunnelActivity && isOutboundTunnelActivityEntry(tag = entry.tag, message = message)) {
                    hasOutboundTunnelActivity = true
                }
            }

        return TunnelValidationEvidence(
            hasSuccessfulTunnelActivity = hasSuccessfulTunnelActivity,
            hasOutboundTunnelActivity = hasOutboundTunnelActivity,
            fatalRuntimeMessage = fatalRuntimeMessage,
        )
    }

    private fun isFatalRuntimeEntry(
        tag: String,
        message: String,
    ): Boolean =
        when (tag) {
            "runtime" ->
                message.contains("start failed:") ||
                    message.contains("reload failed:") ||
                    message.contains("fatal") ||
                    message.contains("panic")

            "libbox" ->
                message.contains("authentication failed") ||
                    message.contains("unknown field") ||
                    message.contains("decode config") ||
                    message.contains("fatal") ||
                    message.contains("panic")

            else -> false
        }

    private fun isSuccessfulTunnelActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        when {
            isNetworkActivityEntry(tag, message) -> true
            isVpnBoundValidationSuccessEntry(tag, message) -> true
            tag == "libbox" ->
                message.contains("inbound/tun[") && message.contains("connection to") ||
                    isOutboundTunnelActivityMessage(message)
            else -> false
        }

    private fun isOutboundTunnelActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        isNetworkActivityEntry(tag, message) ||
            isVpnBoundValidationSuccessEntry(tag, message) ||
            tag == "libbox" && isOutboundTunnelActivityMessage(message)

    private fun isVpnBoundValidationSuccessEntry(
        tag: String,
        message: String,
    ): Boolean =
        tag == "dns" &&
            (
                message.contains("vpn network passed in-process ip refresh") ||
                    message.contains("vpn network passed validation endpoint probe") ||
                    message.contains("validated tunnel grace retry passed")
                )

    private fun isOutboundTunnelActivityMessage(message: String): Boolean =
        message.contains("outbound/") && message.contains("outbound connection to") ||
            message.contains("endpoint/wireguard[") && message.contains("received handshake response")

    private fun isNetworkActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        tag == "activity" &&
            message.contains("app connection") &&
            message.contains("remote=")
}
