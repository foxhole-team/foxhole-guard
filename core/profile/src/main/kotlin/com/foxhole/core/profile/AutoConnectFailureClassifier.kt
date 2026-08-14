package com.foxhole.core.profile

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState

fun classifyAutoConnectProbeFailure(
    snapshot: ConnectionSnapshot?,
    timedOut: Boolean,
    vpnNetworkAvailable: Boolean,
    dnsFailureMessage: String,
): AutoConnectReasonCode =
    when {
        snapshot?.state == ConnectionState.ERROR && snapshot.reasonCode != null -> requireNotNull(snapshot.reasonCode)
        timedOut && vpnNetworkAvailable -> AutoConnectReasonCode.VALIDATION_TIMEOUT
        timedOut -> AutoConnectReasonCode.HANDSHAKE_TIMEOUT
        snapshot?.state == ConnectionState.ERROR && snapshot.message == dnsFailureMessage -> AutoConnectReasonCode.DNS_FAILURE
        else -> AutoConnectReasonCode.CONNECT_ERROR
    }
