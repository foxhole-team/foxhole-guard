package com.foxhole.guard.core.data

import com.foxhole.core.runtime.RuntimeProfiles

/**
 * App-side adapter exposing [ProfileRepository] to the runtime engine through the narrow
 * [RuntimeProfiles] contract. Lives in `:app` because it knows the concrete repository.
 */
class ProfileRepositoryRuntimeProfiles(
    private val profileRepository: ProfileRepository,
) : RuntimeProfiles {
    override suspend fun getResolvedConfig(profileId: Long): String = profileRepository.getResolvedConfig(profileId)
}
