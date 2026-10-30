package com.foxhole.guard.router

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException

internal const val ROUTER_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

internal class RouterLocalNetworkPermissionRequired : IOException("router_local_network_permission_required")

internal fun routerLocalNetworkPermissionNeeded(sdk: Int, target: Int, granted: Boolean): Boolean =
    sdk >= 37 && target >= 37 && !granted

internal fun routerLocalNetworkAccessGranted(context: Context): Boolean = !routerLocalNetworkPermissionNeeded(
    Build.VERSION.SDK_INT,
    context.applicationInfo.targetSdkVersion,
    ContextCompat.checkSelfPermission(context, ROUTER_LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED,
)

internal fun requireRouterLocalNetworkAccess(context: Context) {
    if (!routerLocalNetworkAccessGranted(context)) throw RouterLocalNetworkPermissionRequired()
}
