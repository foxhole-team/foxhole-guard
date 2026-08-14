package com.foxhole.core.runtime
import com.foxhole.core.model.DiagnosticEntry

data class TunnelValidationEvidence(
    val hasSuccessfulTunnelActivity: Boolean,
    val hasOutboundTunnelActivity: Boolean = hasSuccessfulTunnelActivity,
    val fatalRuntimeMessage: String? = null,
)

object TunnelValidationEvidenceClassifier {
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

            "foxcore" ->
                message.contains("prepared config rejected") ||
                    message.contains("prepared config validation failed") ||
                    message.contains("native preflight failed") ||
                    message.contains("native start rejected") ||
                    message.contains("native start returned no handle") ||
                    message.contains("policy reload failed") ||
                    message.contains("policy reload rejected") ||
                    message.contains("tun establish failed") ||
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
            else -> false
        }

    private fun isOutboundTunnelActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        isNetworkActivityEntry(tag, message) ||
            isVpnBoundValidationSuccessEntry(tag, message)

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

    private fun isNetworkActivityEntry(
        tag: String,
        message: String,
    ): Boolean =
        tag == "activity" &&
            message.contains("app connection") &&
            message.contains("remote=")
}
