package com.foxhole.guard.core.settings

import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.DomainStrategy
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import com.foxhole.guard.BuildConfig

// Tunnel/traffic mode and DNS settings. Extracted from SettingsRepository (class split by
// domain).

// Tun stack / MTU / prefer-ipv6 / domain strategy carry NO safe-mode clause on purpose: safe mode
// governs only TrafficMode (see updateTrafficMode below), and Settings.normalized() resets only that
// one field. The transforms are extracted as pure `...In` functions (same idiom as updateAppLaneIn)
// so the regression suite can prove the value survives normalization plus a reread — the write path
// drops any update whose normalized result equals the current one.

suspend fun SettingsRepository.updateTunStack(value: TunStack) =
    update { current -> updateTunStackIn(current, value) }

internal fun updateTunStackIn(
    current: Settings,
    value: TunStack,
): Settings = current.copy(traffic = current.traffic.copy(tunStack = value))

suspend fun SettingsRepository.updateTrafficMode(
    @Suppress("UNUSED_PARAMETER") value: TrafficMode,
) =
    update { current ->
        // Compatibility API for callers compiled against schema <= 18. FoxCore intentionally has
        // no proxy-only runtime: even a stale PROXY request is normalized to the protected TUN.
        val protectedMode = TrafficMode.TUNNEL
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled = current.connection.safeModeEnabled,
            ),
            traffic = current.traffic.copy(mode = protectedMode),
        )
    }

suspend fun SettingsRepository.updateTrafficMtu(value: Int) =
    update { current -> updateTrafficMtuIn(current, value) }

internal fun updateTrafficMtuIn(
    current: Settings,
    value: Int,
): Settings =
    current.copy(traffic = current.traffic.copy(mtu = value.coerceIn(SETTINGS_MIN_MTU, SETTINGS_MAX_MTU)))

suspend fun SettingsRepository.updatePreferIpv6(value: Boolean) =
    update { current -> updatePreferIpv6In(current, value) }

internal fun updatePreferIpv6In(
    current: Settings,
    value: Boolean,
): Settings = current.copy(traffic = current.traffic.copy(preferIpv6 = value))

suspend fun SettingsRepository.updateDomainStrategy(value: DomainStrategy) =
    update { current -> updateDomainStrategyIn(current, value) }

internal fun updateDomainStrategyIn(
    current: Settings,
    value: DomainStrategy,
): Settings = current.copy(traffic = current.traffic.copy(domainStrategy = value))

suspend fun SettingsRepository.updateDnsSettings(value: DnsSettings) =
    update { current ->
        // Turning the DNS filter on auto-arms DNS interception: without intercept the filter is
        // inert at the DNS layer (only sniff-reject fires, the block counters stay empty). This only
        // forces intercept on the off->on filter transition — the user can turn intercept back off
        // afterward (the DNS screen then warns the filter is running degraded).
        val filterJustEnabled = value.filteringEnabled && !current.dns.filteringEnabled
        val effectiveDns =
            if (filterJustEnabled && !value.interceptDnsRequests) {
                value.copy(interceptDnsRequests = true)
            } else {
                value
            }
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled = current.connection.safeModeEnabled && !effectiveDns.runtimeRequiresExplicitTunnel(),
            ),
            dns = effectiveDns,
        )
    }

suspend fun SettingsRepository.updateDnsReplaceSystemDns(value: Boolean) =
    updateDnsSettings(current().dns.copy(replaceSystemDns = value))

suspend fun SettingsRepository.updateDnsBypassPackages(value: List<String>) =
    update { current ->
        current.copy(
            dns =
            current.dns.copy(
                appBypassPackages = value.filterNot { it == BuildConfig.APPLICATION_ID },
            ),
        )
    }

suspend fun SettingsRepository.updateDnsDomainBypassRules(value: List<String>) =
    update { current ->
        current.copy(
            dns = current.dns.copy(domainBypassRules = value),
        )
    }

suspend fun SettingsRepository.markDnsFiltersUpdated(timestamp: Long = System.currentTimeMillis()) =
    update { current ->
        current.copy(
            dns = current.dns.copy(filtersUpdatedAt = timestamp, filtersCheckedAt = timestamp),
        )
    }

suspend fun SettingsRepository.markDnsFiltersChecked(timestamp: Long = System.currentTimeMillis()) =
    update { current ->
        current.copy(
            dns = current.dns.copy(filtersCheckedAt = timestamp),
        )
    }

suspend fun SettingsRepository.updateNetworkRulesSettings(value: NetworkRulesSettings) =
    update { current ->
        current.copy(networkRules = value.normalized())
    }
