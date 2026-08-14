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

        assertTrue(service.contains("private val networkCallbacks = FoxholeVpnServiceNetworkCallbackOwner(this)"))
        assertTrue(service.contains("networkCallbacks.upstream"))
        assertTrue(service.contains("networkCallbacks.vpn"))
        assertTrue(service.contains("networkCallbacks.default"))
        assertFalse(service.contains("object : ConnectivityManager.NetworkCallback()"))

        assertTrue(owner.contains("val upstream: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("val vpn: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("val default: ConnectivityManager.NetworkCallback"))
        assertTrue(owner.contains("service.scope.launch(Dispatchers.Main.immediate)"))

        assertTrue(registration.contains("registerBestMatchingNetworkCallback("))
        assertTrue(registration.contains("trackedNetworkRequest,\n                        networkCallback,"))
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
