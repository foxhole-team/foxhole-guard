package com.foxhole.beta.vpn

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build

internal fun NetworkCapabilities.isFoxholeVpnNetwork(context: Context): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ownerUid == context.applicationInfo.uid)
