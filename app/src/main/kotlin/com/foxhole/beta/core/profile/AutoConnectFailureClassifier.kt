package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState

internal fun classifyAutoConnectProbeFailure(
    snapshot: ConnectionSnapshot?,
    timedOut: Boolean,
    vpnNetworkAvailable: Boolean,
    dnsFailureMessage: String,
): AutoConnectReasonCode =
    when {
        timedOut && vpnNetworkAvailable -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        timedOut -> AutoConnectReasonCode.HANDSHAKE_TIMEOUT
        snapshot?.state == ConnectionState.ERROR && snapshot.message == dnsFailureMessage -> AutoConnectReasonCode.DNS_FAILURE
        else -> AutoConnectReasonCode.CONNECT_ERROR
    }
