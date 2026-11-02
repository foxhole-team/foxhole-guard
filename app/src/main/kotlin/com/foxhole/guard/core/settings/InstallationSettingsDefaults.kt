package com.foxhole.guard.core.settings

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.UiSettings

internal fun newInstallationSettings(ipInfoEndpoint: String): Settings =
    Settings(
        ui = UiSettings(interfaceMode = InterfaceMode.USER),
        connection = ConnectionSettings(
            ipInfoEndpoint = ipInfoEndpoint,
            componentAutoUpdateEnabled = true,
            geoIpAutoUpdate = true,
            tlsFingerprintAutoUpdate = true,
        ),
        dns = DnsSettings(autoUpdateFilters = true),
        privacyRoute = PrivacyRouteSettings(bridgesAutoUpdate = true),
    )
