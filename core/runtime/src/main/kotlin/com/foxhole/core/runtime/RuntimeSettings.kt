package com.foxhole.core.runtime

import com.foxhole.core.model.Settings

interface RuntimeSettings {
    suspend fun current(): Settings

    suspend fun markDnsFiltersUpdated(timestamp: Long = System.currentTimeMillis())

    suspend fun markDnsFiltersChecked(timestamp: Long = System.currentTimeMillis())
}
