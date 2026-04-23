package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile

internal object HomeActiveProfileResolver {
    fun resolve(
        profiles: List<Profile>,
        activeProfile: Profile?,
        startupFallbackProfile: Profile? = null,
    ): Profile? {
        activeProfile?.let { explicit ->
            profiles.firstOrNull { it.id == explicit.id }?.let { return it }
            return explicit
        }
        startupFallbackProfile?.let { fallback ->
            profiles.firstOrNull { it.id == fallback.id }?.let { return it }
            if (profiles.isEmpty()) {
                return fallback
            }
        }
        return profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()
    }
}
