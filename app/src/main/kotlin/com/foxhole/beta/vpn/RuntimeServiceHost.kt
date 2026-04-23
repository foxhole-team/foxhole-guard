package com.foxhole.beta.vpn

import android.content.Context
import android.net.VpnService

internal interface RuntimeServiceHost {
    val runtimeContext: Context

    fun stopRuntimeService()

    fun protectSocket(socket: Int): Boolean

    fun hasVpnPermission(): Boolean = true

    fun createTunBuilder(): VpnService.Builder? = null
}
