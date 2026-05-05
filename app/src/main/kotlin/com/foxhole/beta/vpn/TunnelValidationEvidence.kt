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
    ): Boolean {
        if (tag != "libbox") {
            return false
        }
        return message.contains("inbound/tun[") && message.contains("connection to") ||
            isOutboundTunnelActivityMessage(message)
    }

    private fun isOutboundTunnelActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        tag == "libbox" && isOutboundTunnelActivityMessage(message)

    private fun isOutboundTunnelActivityMessage(message: String): Boolean =
        message.contains("outbound/") && message.contains("outbound connection to") ||
            message.contains("endpoint/wireguard[") && message.contains("received handshake response")
}
