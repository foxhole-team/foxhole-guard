package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile

internal data class NetworkProfileOverride(
    val profile: Profile,
    val protocolOptionId: String? = null,
)
