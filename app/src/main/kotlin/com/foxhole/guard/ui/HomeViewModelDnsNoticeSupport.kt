package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.RuntimeDnsNotice
import com.foxhole.guard.runtime.RuntimeDnsNoticeBus
import kotlinx.coroutines.launch

/**
 * In-app DNS notices:
 *  - the runtime bus (system-DNS substitution alerts from [com.foxhole.guard.runtime.SystemDnsChangeMonitor],
 *    which already posted the system notification) surfaces as a banner while the UI is open;
 *  - a fresh tunnel connect on a profile that advertises NO resolver of its own (only WireGuard
 *    endpoints can push one) tells the user that DNS runs through the configured provider —
 *    Cloudflare by default — instead of a provider resolver. The fallback itself has always been
 *    automatic in the assembled config; this makes it visible.
 */
internal fun HomeViewModel.observeDnsNoticesInternal() {
    viewModelScope.launch {
        RuntimeDnsNoticeBus.notices.collect { notice ->
            when (notice) {
                is RuntimeDnsNotice.SystemDnsChanged ->
                    emitError(
                        getApplication<Application>().getString(
                            R.string.dns_change_notification_body,
                            notice.previous.joinToString(", "),
                            notice.current.joinToString(", "),
                        ),
                    )

                is RuntimeDnsNotice.ProviderDnsFallback ->
                    emitInfo(
                        getApplication<Application>().getString(
                            R.string.dns_provider_fallback_banner,
                            notice.server,
                        ),
                    )
            }
        }
    }
    viewModelScope.launch {
        var previousState: ConnectionState? = null
        container.connectionController.snapshot.collect { snapshot ->
            val enteredConnected =
                snapshot.state == ConnectionState.CONNECTED && previousState != ConnectionState.CONNECTED
            previousState = snapshot.state
            if (!enteredConnected) {
                return@collect
            }
            maybeEmitProviderDnsFallbackNotice(snapshot)
        }
    }
}

@Suppress("ReturnCount") // Several distinct eligibility guards early-exit before the notice emits.
private fun HomeViewModel.maybeEmitProviderDnsFallbackNotice(snapshot: ConnectionSnapshot) {
    val settings = container.settingsRepository.settings.value
    if (!settings.dns.useVpnProviderDns) {
        return
    }
    if (snapshot.trafficMode != TrafficMode.TUNNEL) {
        return
    }
    val profileId = snapshot.profileId ?: return
    if (profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID ||
        profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    ) {
        return
    }
    // Only protocols that can never advertise a provider resolver: WireGuard configs may push one
    // (the assembler honours it), and a custom normalized config could embed a WireGuard endpoint,
    // so both stay silent rather than claim a fallback that did not happen.
    if (snapshot.protocolHint !in PROVIDER_DNS_INCAPABLE_PROTOCOLS) {
        return
    }
    val server = settings.dns.server.trim().ifBlank { return }
    val noticeKey = "$profileId|${snapshot.protocolOptionId}|$server"
    if (noticeKey == lastProviderDnsFallbackNoticeKey) {
        return
    }
    lastProviderDnsFallbackNoticeKey = noticeKey
    val providerLabel =
        dnsResolverPresetFor(server)
            ?.label
            ?.takeIf(String::isNotBlank)
            ?.let { label -> "$label ($server)" }
            ?: server
    RuntimeDnsNoticeBus.tryEmit(RuntimeDnsNotice.ProviderDnsFallback(server = providerLabel))
}

internal val PROVIDER_DNS_INCAPABLE_PROTOCOLS =
    setOf(
        ProtocolHint.VLESS,
        ProtocolHint.TROJAN,
        ProtocolHint.SHADOWSOCKS,
        ProtocolHint.HYSTERIA2,
        ProtocolHint.VMESS,
        ProtocolHint.OUTLINE,
    )
