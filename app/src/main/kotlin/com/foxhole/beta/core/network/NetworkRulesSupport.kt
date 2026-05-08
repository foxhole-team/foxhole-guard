package com.foxhole.beta.core.network

import com.foxhole.beta.core.model.NetworkRulesSettings

internal fun NetworkFingerprint.isCellularOrMetered(): Boolean =
    transport == "cellular" || isMetered

internal fun NetworkFingerprint.scopedByNetworkRules(settings: NetworkRulesSettings): NetworkFingerprint? =
    when {
        transport == "wifi" && !settings.wifiRulesEnabled -> null
        isCellularOrMetered() && !settings.cellularRulesEnabled -> null
        else -> this
    }
