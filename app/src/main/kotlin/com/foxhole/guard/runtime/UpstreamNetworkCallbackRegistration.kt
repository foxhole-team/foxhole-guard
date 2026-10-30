package com.foxhole.guard.runtime

import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler

internal fun registerUpstreamNetworkCallback(
    manager: ConnectivityManager,
    request: NetworkRequest,
    callback: ConnectivityManager.NetworkCallback,
    handler: Handler,
    sdkVersion: Int = Build.VERSION.SDK_INT,
) {
    when {
        sdkVersion >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            manager.registerBestMatchingNetworkCallback(request, callback, handler)
        sdkVersion >= Build.VERSION_CODES.O -> manager.registerNetworkCallback(request, callback, handler)
        else -> manager.registerNetworkCallback(request, callback)
    }
}
