package com.foxhole.guard.runtime

import androidx.annotation.StringRes
import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.runtime.resolveRuntimeConnectProfileId
import com.foxhole.core.runtime.torAllAppsCollidesWithVpnIncludeSplit
import com.foxhole.guard.R
import com.foxhole.guard.core.data.InsecureTlsProfileConsentRequiredException
import com.foxhole.guard.core.data.SubscriptionProtocolOptionUnavailableException
import com.foxhole.guard.core.data.prepareProfileForConnection
import com.foxhole.guard.diagnosticFailureLabel
import kotlinx.coroutines.CancellationException

internal enum class VpnTorStartBlockReason {
    TOR_PERMISSION_REQUIRED,
    PROFILE_REQUIRED,
    INCOMPATIBLE_VPN_PROTOCOL,

    TOR_ALL_APPS_NEEDS_FULL_TUNNEL,
}

internal data class ResolvedVpnStart(
    val settings: Settings,
    val profileId: Long,
    val protocolOptionId: String?,
    val torOnly: Boolean,
)

@Suppress("ReturnCount")
internal fun vpnTorStartBlockReason(
    settings: Settings,
    profile: Profile?,
    protocolOptionId: String?,
    torOnlyConnect: Boolean,
): VpnTorStartBlockReason? {
    if (torOnlyConnect && (!settings.privacyRoute.permitted || !settings.privacyRoute.enabled)) {
        return VpnTorStartBlockReason.TOR_PERMISSION_REQUIRED
    }
    if (!settings.privacyRoute.enabled) {
        return null
    }
    if (!settings.privacyRoute.permitted) {
        return VpnTorStartBlockReason.TOR_PERMISSION_REQUIRED
    }

    if (!torOnlyConnect && settings.torAllAppsCollidesWithVpnIncludeSplit()) {
        return VpnTorStartBlockReason.TOR_ALL_APPS_NEEDS_FULL_TUNNEL
    }
    if (settings.privacyRoute.bypassVpnTunnel) {
        return null
    }
    if (torOnlyConnect || profile == null) {
        return VpnTorStartBlockReason.PROFILE_REQUIRED
    }
    val protocolHint = profile.runtimeProtocolOption(protocolOptionId)?.protocolHint ?: profile.protocolHint
    return VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL.takeIf { protocolHint.isUdpTransport() }
}

@StringRes
internal fun VpnTorStartBlockReason.messageResource(): Int =
    when (this) {
        VpnTorStartBlockReason.TOR_PERMISSION_REQUIRED -> R.string.privacy_route_core_forbidden
        VpnTorStartBlockReason.PROFILE_REQUIRED -> R.string.error_vpn_tor_profile_required
        VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL -> R.string.error_vpn_tor_profile_incompatible
        VpnTorStartBlockReason.TOR_ALL_APPS_NEEDS_FULL_TUNNEL -> R.string.error_tor_all_apps_needs_full_tunnel
    }

@Suppress("ReturnCount")
internal suspend fun FoxholeVpnService.rejectBlockedVpnTorStart(
    settings: Settings,
    requestedProfileId: Long,
    resolvedProfileId: Long?,
    protocolOptionId: String?,
    commandStartId: Int,
): Boolean {
    val requestedTorOnly = requestedProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    if (resolvedProfileId == null) {
        val reason = vpnTorStartBlockReason(settings, null, protocolOptionId, requestedTorOnly)
        disconnect(
            message = getString(reason?.messageResource() ?: R.string.error_profile_missing),
            commandStartId = commandStartId,
        )
        return true
    }
    val resolvedTorOnly = resolvedProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    val profile =
        if (
            !resolvedTorOnly &&
            settings.privacyRoute.enabled &&
            !settings.privacyRoute.bypassVpnTunnel
        ) {
            container.profileRepository.getProfile(resolvedProfileId)
        } else {
            null
        }
    val reason = vpnTorStartBlockReason(settings, profile, protocolOptionId, resolvedTorOnly) ?: return false
    disconnect(message = getString(reason.messageResource()), commandStartId = commandStartId)
    return true
}

internal suspend fun FoxholeVpnService.resolveVpnStartOrReject(
    requestedProfileId: Long,
    protocolOptionId: String?,
    commandStartId: Int,
    subscriptionRefreshPrepared: Boolean = false,
    protocolTestTrafficFreeze: Boolean = false,
): ResolvedVpnStart? {
    val storedSettings = container.settingsRepository.current()
    val settings = storedSettings.forProtocolTestTrafficFreeze(protocolTestTrafficFreeze)
    val resolvedProfileId =
        resolveRuntimeConnectProfileId(
            requestedProfileId = requestedProfileId,
            torOnlyProfileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
        ) {
            container.profileRepository.getActiveProfile()?.id
        }
    if (resolvedProfileId == null) {
        disconnect(message = getString(R.string.error_profile_missing), commandStartId = commandStartId)
        return null
    }
    val torOnly = resolvedProfileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    val prepared =
        if (torOnly) {
            null
        } else {
            try {
                container.profileRepository.prepareProfileForConnection(
                    profileId = resolvedProfileId,
                    requestedProtocolOptionId = protocolOptionId,
                    refreshSubscription = !subscriptionRefreshPrepared,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.recordFailure(
                    "connection",
                    "subscription pre-connect failed: ${diagnosticFailureLabel(error)}",
                )
                val message =
                    when (error) {
                        is InsecureTlsProfileConsentRequiredException ->
                            getString(R.string.error_subscription_insecure_tls_consent)
                        is SubscriptionProtocolOptionUnavailableException ->
                            getString(R.string.error_subscription_protocol_unavailable)
                        else -> getString(R.string.error_subscription_refresh_before_connect)
                    }
                fail(message, commandStartId)
                return null
            }
        }
    val preparedProfileId = prepared?.profile?.id ?: resolvedProfileId
    val preparedProtocolOptionId = prepared?.protocolOptionId ?: protocolOptionId
    val rejected =
        rejectBlockedVpnTorStart(
            settings = settings,
            requestedProfileId = requestedProfileId,
            resolvedProfileId = preparedProfileId,
            protocolOptionId = preparedProtocolOptionId,
            commandStartId = commandStartId,
        )
    if (rejected) {
        return null
    }
    val profileId = preparedProfileId
    if (profileId != requestedProfileId) {
        container.diagnosticsLogger.record("profile", "connect recovered missing service profile id")
    }
    return ResolvedVpnStart(
        settings = settings,
        profileId = profileId,
        protocolOptionId = preparedProtocolOptionId,
        torOnly = torOnly,
    )
}
