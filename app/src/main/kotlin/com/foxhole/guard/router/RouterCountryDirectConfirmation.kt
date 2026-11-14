package com.foxhole.guard.router

internal class RouterCountryDirectConfirmation internal constructor(
    internal val generation: Long,
    internal val routerId: String,
    internal val revision: Long,
    internal val preferences: RouterControlPreferences,
    internal val detectedCountry: String?,
    internal val wasEnabled: Boolean,
    internal val countryCode: String,
    internal val manualCountry: Boolean,
    val turnOn: Boolean,
)
