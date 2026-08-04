package com.foxhole.core.network

import com.foxhole.core.model.NetworkRulesSettings

fun NetworkFingerprint.isCellularOrMetered(): Boolean =
    transport == "cellular" || isMetered

fun NetworkFingerprint.scopedByNetworkRules(settings: NetworkRulesSettings): NetworkFingerprint? =
    when {
        transport == "wifi" && !settings.wifiRulesEnabled -> null
        isCellularOrMetered() && !settings.cellularRulesEnabled -> null
        else -> this
    }
