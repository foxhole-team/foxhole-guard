package com.foxhole.guard.ui

import com.foxhole.core.model.Profile

internal object HomeActiveProfileResolver {
    fun resolve(
        profiles: List<Profile>,
        activeProfile: Profile?,
        startupFallbackProfile: Profile? = null,
    ): Profile? {
        if (activeProfile != null) {
            return profiles.firstOrNull { it.id == activeProfile.id } ?: activeProfile
        }
        val fallback =
            startupFallbackProfile?.let { candidate ->
                profiles.firstOrNull { it.id == candidate.id }
                    ?: candidate.takeIf { profiles.isEmpty() }
            }
        return fallback
            ?: profiles.firstOrNull { it.isActive }
            ?: profiles.firstOrNull()
    }
}
