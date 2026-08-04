package com.foxhole.guard.core.data

import com.foxhole.core.model.StoredProfileSecret

internal data class StagedProfileSecretWrite(
    val secretRef: String,
    val value: StoredProfileSecret,
)
