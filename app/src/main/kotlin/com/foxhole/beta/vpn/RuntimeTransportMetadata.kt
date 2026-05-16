package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.VpnSession

internal fun VpnSession.runtimeTransportProtocol(): TransportProtocol =
    when (VpnHealthProbeTargetSelector.select(configJson)?.transport) {
        VpnHealthProbeTransport.TCP -> TransportProtocol.TCP
        VpnHealthProbeTransport.UDP -> TransportProtocol.UDP
        null -> TransportProtocol.UNKNOWN
    }
