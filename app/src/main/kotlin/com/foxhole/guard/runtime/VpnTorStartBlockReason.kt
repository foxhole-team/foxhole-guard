package com.foxhole.guard.runtime

import androidx.annotation.StringRes
import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.isUdpTransport
import com.foxhole.core.runtime.resolveRuntimeConnectProfileId
import com.foxhole.guard.R

internal enum class VpnTorStartBlockReason {
    PROFILE_REQUIRED,
    INCOMPATIBLE_VPN_PROTOCOL,
}

internal data class ResolvedVpnStart(
    val settings: Settings,
    val profileId: Long,
    val torOnly: Boolean,
)

/**
 * Enforces the VPN + TOR contract before a TUN or native runtime starts. Android may already have
 * promoted the command receiver to an FGS to satisfy the platform deadline; rejection immediately
 * tears that shell down. Direct TOR is intentionally exempt: it is the supported profile-less route.
 */
internal fun vpnTorStartBlockReason(
    settings: Settings,
    profile: Profile?,
    protocolOptionId: String?,
    torOnlyConnect: Boolean,
): VpnTorStartBlockReason? {
    val vpnTorRequested = settings.privacyRoute.enabled && !settings.privacyRoute.bypassVpnTunnel
    if (!vpnTorRequested) {
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
        VpnTorStartBlockReason.PROFILE_REQUIRED -> R.string.error_vpn_tor_profile_required
        VpnTorStartBlockReason.INCOMPATIBLE_VPN_PROTOCOL -> R.string.error_vpn_tor_profile_incompatible
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
): ResolvedVpnStart? {
    val settings = container.settingsRepository.current()
    val resolvedProfileId =
        resolveRuntimeConnectProfileId(
            requestedProfileId = requestedProfileId,
            torOnlyProfileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
        ) {
            container.profileRepository.getActiveProfile()?.id
        }
    val rejected =
        rejectBlockedVpnTorStart(
            settings = settings,
            requestedProfileId = requestedProfileId,
            resolvedProfileId = resolvedProfileId,
            protocolOptionId = protocolOptionId,
            commandStartId = commandStartId,
        )
    if (rejected) {
        return null
    }
    val profileId = resolvedProfileId ?: return null
    if (profileId != requestedProfileId) {
        container.diagnosticsLogger.record("profile", "connect recovered missing service profile id")
    }
    return ResolvedVpnStart(
        settings = settings,
        profileId = profileId,
        torOnly = profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID,
    )
}
