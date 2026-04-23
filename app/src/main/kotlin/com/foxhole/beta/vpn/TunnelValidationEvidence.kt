package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticEntry

internal data class TunnelValidationEvidence(
    val hasSuccessfulTunnelActivity: Boolean,
    val fatalRuntimeMessage: String? = null,
)

internal object TunnelValidationEvidenceClassifier {
    fun classify(
        entries: List<DiagnosticEntry>,
        sinceMs: Long,
    ): TunnelValidationEvidence {
        var hasSuccessfulTunnelActivity = false
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
            }

        return TunnelValidationEvidence(
            hasSuccessfulTunnelActivity = hasSuccessfulTunnelActivity,
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
        return message.contains("inbound/tun[") && message.contains("inbound connection to") ||
            message.contains("outbound/") && message.contains("outbound connection to")
    }
}
