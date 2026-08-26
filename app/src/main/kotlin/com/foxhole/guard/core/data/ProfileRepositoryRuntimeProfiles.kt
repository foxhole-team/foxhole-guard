package com.foxhole.guard.core.data

import com.foxhole.core.runtime.RuntimeProfiles

class ProfileRepositoryRuntimeProfiles(
    private val profileRepository: ProfileRepository,
) : RuntimeProfiles {
    override suspend fun getResolvedConfig(profileId: Long): String = profileRepository.getResolvedConfig(profileId)
}
