package com.foxhole.guard.router

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun routerVpnActive(manager: ConnectivityManager): Boolean =
    manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true ||
        manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
