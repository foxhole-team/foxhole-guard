package com.foxhole.beta.vpn

internal suspend fun resolveRuntimeConnectProfileId(
    requestedProfileId: Long,
    torOnlyProfileId: Long? = null,
    activeProfileIdProvider: suspend () -> Long?,
): Long? =
    when {
        requestedProfileId > 0L -> requestedProfileId
        torOnlyProfileId != null && requestedProfileId == torOnlyProfileId -> requestedProfileId
        else -> activeProfileIdProvider()?.takeIf { profileId -> profileId > 0L }
    }
