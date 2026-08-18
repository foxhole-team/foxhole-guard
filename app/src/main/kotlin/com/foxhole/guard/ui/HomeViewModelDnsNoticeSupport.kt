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

@Suppress("ReturnCount")
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
