package com.foxhole.guard.ui

import com.foxhole.core.model.Profile

internal data class NetworkProfileOverride(
    val profile: Profile,
    val protocolOptionId: String? = null,
)
