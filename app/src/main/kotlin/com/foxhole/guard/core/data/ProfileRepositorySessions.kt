package com.foxhole.guard.core.data

import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.PrivateDnsMode
import com.foxhole.core.runtime.PrivateDnsState

// Runtime session builders, split out of ProfileRepository (class split by domain; thin
// delegations to the shared ProfileSessionFactory).

internal suspend fun ProfileRepository.getSession(
    profileId: Long,
    protocolOptionIdOverride: String? = null,
    privateDnsMode: PrivateDnsMode? = null,
    privateDnsState: PrivateDnsState? = null,
    deferTorRoute: Boolean = false,
): VpnSession =
    sessionFactory.getSession(
        profileId = profileId,
        protocolOptionIdOverride = protocolOptionIdOverride,
        privateDnsMode = privateDnsMode,
        privateDnsState = privateDnsState,
        deferTorRoute = deferTorRoute,
    )

internal suspend fun ProfileRepository.getTorOnlySession(
    privateDnsMode: PrivateDnsMode? = null,
    privateDnsState: PrivateDnsState? = null,
): VpnSession =
    sessionFactory.getTorOnlySession(
        privateDnsMode = privateDnsMode,
        privateDnsState = privateDnsState,
    )
