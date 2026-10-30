package com.foxhole.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoxholeVpnServiceNetworkCallbackOwnerContractTest {
    @Test
    fun `service delegates stable callback identities to one lifecycle owner`() {
        val service = projectFile("app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnService.kt").readText()
        val owner =
            projectFile(
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceNetworkCallbackOwner.kt",
            ).readText()
        val registration =
            projectFile(
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceNetworkCallbacksSupport.kt",
            ).readText()
        val upstreamRegistration =
            projectFile(
                "app/src/main/kotlin/com/foxhole/guard/runtime/UpstreamNetworkCallbackRegistration.kt",
            ).readText()
        val upstreamRequest =
            service.substringAfter("internal val trackedNetworkRequest by lazy")
                .substringBefore("internal val trackedVpnNetworkRequest by lazy")

        assertTrue(service.contains("private val networkCallbacks = FoxholeVpnServiceNetworkCallbackOwner(this)"))
        assertTrue(service.contains("networkCallbacks.upstream"))
        assertTrue(service.contains("networkCallbacks.vpn"))
        assertTrue(service.contains("networkCallbacks.default"))
        assertFalse(service.contains("object : ConnectivityManager.NetworkCallback()"))

        assertTrue(owner.contains("val upstream: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("val vpn: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("val default: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("service.scope.launch(Dispatchers.Main.immediate)"))

        assertTrue(upstreamRequest.contains(".addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)"))
        assertFalse(upstreamRequest.contains("TRANSPORT_VPN"))
        assertTrue(
            registration.contains(
                "registerUpstreamNetworkCallback(connectivityManager, trackedNetworkRequest, networkCallback, mainHandler)",
            ),
        )
        assertTrue(
            upstreamRegistration.contains(
                "manager.registerBestMatchingNetworkCallback(request, callback, handler)",
            ),
        )
        assertTrue(upstreamRegistration.contains("sdkVersion >= Build.VERSION_CODES.O"))
        assertTrue(upstreamRegistration.contains("manager.registerNetworkCallback(request, callback, handler)"))
        assertTrue(upstreamRegistration.contains("else -> manager.registerNetworkCallback(request, callback)"))
        assertFalse(upstreamRegistration.contains("registerDefaultNetworkCallback"))
        val fallback = registration.substringAfter("}.recoverCatching {").substringBefore("}")
        assertTrue(
            fallback.contains(
                "connectivityManager.registerNetworkCallback(trackedNetworkRequest, networkCallback)",
            ),
        )
        assertFalse(fallback.contains("registerDefaultNetworkCallback"))
        assertTrue(registration.contains("trackedVpnNetworkRequest, vpnNetworkCallback"))
        assertTrue(service.contains("unregisterNetworkCallback(networkCallback)"))
        assertTrue(service.contains("unregisterNetworkCallback(vpnNetworkCallback)"))
        assertTrue(service.contains("unregisterNetworkCallback(defaultNetworkCallback)"))
    }

    private fun projectFile(path: String): File =
        generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
            .map { directory -> directory.resolve(path) }
            .first(File::isFile)
}
