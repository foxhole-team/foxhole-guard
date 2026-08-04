package com.foxhole.core.runtime

import com.foxhole.core.model.Settings

/**
 * Runtime-facing slice of the app's settings store.
 *
 * The runtime engine reads only the current settings snapshot and reports when it has refreshed the
 * DNS filters; it does not need the full `SettingsRepository`. Depending on this narrow contract
 * (rather than the app's repository) is what lets the engine move into `:core:runtime`. The app
 * supplies an adapter over `SettingsRepository` at the boundary — same pattern as
 * [RuntimeDiagnosticsSink].
 */
interface RuntimeSettings {
    suspend fun current(): Settings

    suspend fun markDnsFiltersUpdated(timestamp: Long = System.currentTimeMillis())

    suspend fun markDnsFiltersChecked(timestamp: Long = System.currentTimeMillis())
}
