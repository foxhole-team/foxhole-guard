package com.foxhole.guard.runtime

import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.Settings

/** Runtime-only policy for a manual protocol test; persisted settings are never mutated. */
internal fun Settings.forProtocolTestTrafficFreeze(enabled: Boolean): Settings =
    if (!enabled) {
        this
    } else {
        copy(
            privacyRoute = privacyRoute.copy(mode = PrivacyRouteMode.OFF),
            i2p = i2p.copy(engaged = false),
        )
    }
