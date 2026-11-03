package com.foxhole.guard.core.data

import com.foxhole.core.model.RoutingPreset

internal interface RoutingRuntimeCommit {
    suspend fun stage(preset: RoutingPreset?)
    suspend fun committed()
}
