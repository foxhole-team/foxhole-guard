package com.foxhole.guard.ui.cli

/**
 * The canon of terminal commands: the single source of every printed command. Grammar is verb +
 * target + flags (`start VPN -p home -t vless`). These strings will carry over verbatim into a
 * standalone FoxHole CLI client, so commands are *not* localised and never go through resources —
 * only the terminal's answers and statuses are. The `> ` prefix belongs to the prompt renderer,
 * not to the command.
 */
internal object CliCommands {

    const val STOP = "stop"
    const val CANCEL = "cancel"
    const val RESTART = "restart"
    const val RECONNECT = "reconnect"
    const val STATUS = "status"

    /** `-a`: the same report with the identity, encryption and journal sections attached. */
    const val STATUS_ALL = "status -a"
    const val START_TOR = "start TOR"
    const val SCAN_QR = "scan qr"

    const val MODE_VPN = "mode VPN"
    const val MODE_TOR = "mode TOR"
    const val MODE_VPN_TOR = "mode VPN+TOR"

    /**
     * `start VPN -p <profile> [-t <transport>]`. Both flags are optional: without a profile the
     * command honestly stays `start VPN`, and [transport] is printed only for smart profiles with
     * a selected option.
     */
    fun startVpn(profile: String?, transport: String? = null): String =
        start(target = "VPN", profile = profile, transport = transport)

    /** `start VPN+TOR -p <profile> [-t <transport>]` — the chained mode. */
    fun startVpnTor(profile: String?, transport: String? = null): String =
        start(target = "VPN+TOR", profile = profile, transport = transport)

    fun i2p(enable: Boolean): String = if (enable) "i2p on" else "i2p off"

    fun refreshSubscription(profile: String?): String =
        buildString {
            append("refresh subscription")
            profile?.takeIf(String::isNotBlank)?.let { append(" -p ").append(quote(it)) }
        }

    private fun start(target: String, profile: String?, transport: String?): String =
        buildString {
            append("start ").append(target)
            profile?.takeIf { it.isNotBlank() }?.let { append(" -p ").append(quote(it)) }
            transport?.takeIf { it.isNotBlank() }?.let { append(" -t ").append(quote(it)) }
        }

    // A token with spaces is quoted, as in a real shell: -p "my home".
    private fun quote(token: String): String =
        if (token.any(Char::isWhitespace)) "\"$token\"" else token
}
