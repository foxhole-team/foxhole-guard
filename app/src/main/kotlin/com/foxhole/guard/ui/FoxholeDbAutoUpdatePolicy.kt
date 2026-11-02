package com.foxhole.guard.ui

import com.foxhole.core.model.Settings

internal fun foxholeDbAutoUpdateEnabled(settings: Settings): Boolean =
    settings.connection.componentUpdateCheckEnabled &&
        settings.connection.componentAutoUpdateEnabled &&
        settings.connection.geoIpAutoUpdate &&
        settings.connection.tlsFingerprintAutoUpdate &&
        settings.dns.autoUpdateFilters &&
        settings.privacyRoute.bridgesAutoUpdate
