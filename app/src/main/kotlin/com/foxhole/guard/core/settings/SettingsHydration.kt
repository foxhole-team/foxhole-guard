package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.guard.runtime.runCatchingUnlessCancelled

internal class SettingsHydration {
    @Volatile
    private var result: Result<Settings>? = null

    fun cached(): Settings? = result?.getOrThrow()

    suspend fun load(loader: suspend () -> Settings): Settings {
        cached()?.let { return it }
        val loaded = runCatchingUnlessCancelled { loader() }
        result = loaded
        return loaded.getOrThrow()
    }

    fun replace(settings: Settings) {
        result = Result.success(settings)
    }
}
