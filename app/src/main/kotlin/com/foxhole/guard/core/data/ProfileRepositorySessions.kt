package com.foxhole.guard.core.data

import com.foxhole.core.model.Profile
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.PrivateDnsMode
import com.foxhole.core.runtime.PrivateDnsState

internal suspend fun ProfileRepository.getSession(
    profileId: Long,
    protocolOptionIdOverride: String? = null,
    privateDnsMode: PrivateDnsMode? = null,
    privateDnsState: PrivateDnsState? = null,
    deferTorRoute: Boolean = false,
    protocolTestTrafficFreeze: Boolean = false,
): VpnSession {
    val prepared = prepareSessionSnapshot(profileId, protocolOptionIdOverride)
    return sessionFactory.getSession(
        profileId = profileId,
        protocolOptionIdOverride = protocolOptionIdOverride,
        privateDnsMode = privateDnsMode,
        privateDnsState = privateDnsState,
        deferTorRoute = deferTorRoute,
        protocolTestTrafficFreeze = protocolTestTrafficFreeze,
        preparedSnapshot = prepared,
    )
}

internal suspend fun ProfileRepository.getTorOnlySession(
    privateDnsMode: PrivateDnsMode? = null,
    privateDnsState: PrivateDnsState? = null,
): VpnSession =
    sessionFactory.getTorOnlySession(
        privateDnsMode = privateDnsMode,
        privateDnsState = privateDnsState,
    )

internal data class PreparedProfileSessionSnapshot(
    val profile: Profile,
    val secret: StoredProfileSecret,
    val settings: Settings,
    val protocolOptionId: String?,
    val resolvedConfig: String,
)
