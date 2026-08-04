package com.foxhole.guard.core.settings

import com.foxhole.core.model.ClashApiSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.V2RayApiSettings
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.core.model.withBlockedSelection
import com.foxhole.core.model.withTunnelSelection
import com.foxhole.guard.BuildConfig

// Per-app routing, proxy surfaces, local auth and reset/clear operations. Extracted from
// SettingsRepository (class split by domain).

suspend fun SettingsRepository.updatePerAppRoutingMode(value: PerAppRoutingMode) =
    update { current ->
        val nextMode =
            value.takeUnless {
                it != PerAppRoutingMode.FULL_TUNNEL && current.expert.tunnelSelectedPackages().isEmpty()
            } ?: PerAppRoutingMode.FULL_TUNNEL
        current.copy(
            connection =
            current.connection.copy(
                safeModeEnabled = current.connection.safeModeEnabled && nextMode == PerAppRoutingMode.FULL_TUNNEL,
            ),
            traffic = current.traffic.copy(mode = splitTunnelTrafficMode(current.traffic.mode, nextMode)),
            expert =
            current.expert.copy(
                perAppRoutingMode = nextMode,
            ),
        )
    }

suspend fun SettingsRepository.updateSelectedPackages(value: List<String>) =
    update {
        val normalizedSelectedPackages =
            value
                .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                .distinct()
        val nextMode = selectedPackagesRoutingMode(it.expert.perAppRoutingMode, normalizedSelectedPackages)
        it.copy(
            // Selecting apps is an expert customization: safe mode must end, or normalized() (applied
            // by update() before persisting) drops the non-BLOCK lane assignments and resets
            // perAppRoutingMode — after which the write usually equals the previous state and is
            // skipped entirely, losing the selection on relaunch. An empty selection customizes
            // nothing, so it may stay in safe mode.
            connection =
            it.connection.copy(
                safeModeEnabled = it.connection.safeModeEnabled && normalizedSelectedPackages.isEmpty(),
            ),
            traffic = it.traffic.copy(mode = splitTunnelTrafficMode(it.traffic.mode, nextMode)),
            expert =
            it.expert
                .withTunnelSelection(normalizedSelectedPackages)
                .copy(perAppRoutingMode = nextMode),
        )
    }

suspend fun SettingsRepository.updateBlockedPackages(value: List<String>) =
    update {
        val normalizedBlockedPackages =
            value
                .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                .distinct()
        it.copy(
            connection = it.connection.copy(safeModeEnabled = false),
            expert =
            it.expert
                .withBlockedSelection(normalizedBlockedPackages)
                .copy(blockedPackagesEnabled = normalizedBlockedPackages.isNotEmpty()),
        )
    }

suspend fun SettingsRepository.updateBlockedPackagesEnabled(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
            expert =
            it.expert.copy(
                blockedPackagesEnabled = value,
                blockAppsAlways = it.expert.blockAppsAlways && value && it.expert.blockedLanePackages().isNotEmpty(),
            ),
        )
    }

suspend fun SettingsRepository.updateBlockAppsAlways(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
            expert =
            it.expert.copy(
                blockAppsAlways = value && it.expert.blockedPackagesEnabled && it.expert.blockedLanePackages().isNotEmpty(),
            ),
        )
    }

suspend fun SettingsRepository.updateSiteRoutingAction(value: RoutingRuleAction) =
    update {
        it.copy(
            connection = it.connection.copy(
                safeModeEnabled = it.connection.safeModeEnabled && value == RoutingRuleAction.PROXY
            ),
            expert = it.expert.copy(siteRoutingAction = value.coerceSiteRoutingAction()),
        )
    }

suspend fun SettingsRepository.updateProxySurfaceMode(value: ProxySurfaceMode) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = false),
            expert =
            it.expert.copy(
                localSurfaces =
                it.expert.localSurfaces.copy(proxyMode = value).withEnabledProxyMode(value),
            ),
        )
    }

suspend fun SettingsRepository.updateLanProxySurfaceMode(value: ProxySurfaceMode) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = false),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(lanProxyMode = value)),
        )
    }

suspend fun SettingsRepository.updateSocksSurface(value: ProxyInboundSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(socks = value.normalized())),
        )
    }

suspend fun SettingsRepository.updateHttpSurface(value: ProxyInboundSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(http = value.normalized())),
        )
    }

suspend fun SettingsRepository.updateMixedSurface(value: ProxyInboundSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(mixed = value.normalized())),
        )
    }

suspend fun SettingsRepository.updateLocalProxyAuthEnabled(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value),
            expert =
            it.expert.copy(
                localSurfaces =
                it.expert.localSurfaces.copy(
                    auth = it.expert.localSurfaces.auth.copy(enabled = value),
                ),
            ),
        )
    }

suspend fun SettingsRepository.updateLocalProxyAuth(value: LocalAuthSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value.enabled),
            expert =
            it.expert.copy(
                localSurfaces =
                it.expert.localSurfaces.copy(
                    auth = value.normalized(),
                ),
            ),
        )
    }

// NB: there is deliberately no `updateLanProxyAuthEnabled`. LAN proxy auth is mandatory — the
// runtime forces it on in `mandatoryLanProxyAuthOrNull` (RuntimeLocalSurface.kt) and normalization
// keeps it enabled, so a setter that could flip it off would only ever produce an unauthenticated
// LAN listener. The UI toggle was removed for the same reason.
suspend fun SettingsRepository.updateLanProxyAuth(value: LocalAuthSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value.enabled),
            expert =
            it.expert.copy(
                localSurfaces =
                it.expert.localSurfaces.copy(
                    lanAuth = value.normalized(),
                ),
            ),
        )
    }

suspend fun SettingsRepository.updateLocalProxyLanAccessEnabled(value: Boolean) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
            // Turning the LAN proxy on reveals its quick-access pill; turning it off leaves the pill
            // (its visibility is owned by the show flag, so the user can re-arm from it).
            ui = if (value) it.ui.copy(showLanProxyQuickAccess = true) else it.ui,
            expert =
            it.expert.copy(
                localSurfaces =
                it.expert.localSurfaces.copy(
                    allowLanAccess = value,
                ),
            ),
        )
    }

suspend fun SettingsRepository.updateClashApi(value: ClashApiSettings) =
    update {
        it.copy(
            connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(clashApi = value.normalized())),
        )
    }

suspend fun SettingsRepository.updateV2RayApi(value: V2RayApiSettings) =
    update {
        it.copy(
            connection =
            it.connection.copy(
                safeModeEnabled =
                it.connection.safeModeEnabled && !value.enabled,
            ),
            expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(v2RayApi = value.normalized())),
        )
    }

suspend fun SettingsRepository.resetExpertToSafeDefaults() =
    update { current -> current.resetExpertSettingsToSafeDefaults() }

suspend fun SettingsRepository.resetExperimentalSettingsToDefaults() =
    update { current -> current.resetExperimentalSettingsToDefaults() }

suspend fun SettingsRepository.resetApplicationSettingsToDefaults() =
    update { current -> current.resetApplicationSettingsToDefaults() }

suspend fun SettingsRepository.resetUsageTracking(timestamp: Long = System.currentTimeMillis()) =
    update {
        it.copy(
            profileTrafficTotals = emptyList(),
            usageTrackingStartedAt = timestamp,
        )
    }

suspend fun SettingsRepository.clearProfileLocalData() =
    update {
        it.copy(
            lastActiveProfile = null,
            profileTrafficTotals = emptyList(),
            smartProfilePreferences = emptyList(),
        )
    }

suspend fun SettingsRepository.resetAllLocalSettings() =
    update { storage.defaultSettings() }
