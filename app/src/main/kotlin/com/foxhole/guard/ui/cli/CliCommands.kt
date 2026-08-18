package com.foxhole.guard.ui.cli

internal object CliCommands {

    const val STOP = "stop"
    const val CANCEL = "cancel"
    const val RESTART = "restart"
    const val RECONNECT = "reconnect"
    const val STATUS = "status"

    const val STATUS_ALL = "status -a"
    const val START_TOR = "start TOR"
    const val SCAN_QR = "scan qr"

    const val MODE_VPN = "mode VPN"
    const val MODE_TOR = "mode TOR"
    const val MODE_VPN_TOR = "mode VPN+TOR"

    fun startVpn(profile: String?, transport: String? = null): String =
        start(target = "VPN", profile = profile, transport = transport)

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

    private fun quote(token: String): String =
        if (token.any(Char::isWhitespace)) "\"$token\"" else token
}
