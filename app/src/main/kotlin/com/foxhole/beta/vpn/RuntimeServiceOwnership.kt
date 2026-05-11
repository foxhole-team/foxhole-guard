package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.TrafficMode

internal fun ConnectionSnapshot.isActiveRuntimeFor(mode: TrafficMode): Boolean =
    state in ACTIVE_CONNECTION_STATES && trafficMode == mode

internal fun ConnectionSnapshot.isActiveRuntimeForAnotherMode(mode: TrafficMode): Boolean =
    state in ACTIVE_CONNECTION_STATES && trafficMode != mode
