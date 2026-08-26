package com.foxhole.core.runtime

interface RuntimeProfiles {
    suspend fun getResolvedConfig(profileId: Long): String
}
