package com.foxhole.guard.runtime

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FoxholeVpnServiceNetworkRequestAndroidTest {
    @Test
    fun vpnLossRequestCanMatchAnActualVpnNetwork() {
        val request = FoxholeVpnService().trackedVpnNetworkRequest

        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test
    fun android8UpstreamRegistrationFindsPhysicalNetworkWhileVpnIsDefault() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("foxhole.coreAudit") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val observed = CompletableDeferred<NetworkCapabilities>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                observed.complete(capabilities)
            }
        }
        var registered = false
        try {
            launchTestVpnControl(CallbackTestVpnControlActivity.ACTION_START, context.packageName)
            withTimeout(15_000) {
                while (!manager.hasDefaultVpn()) delay(50)
            }
            registerUpstreamNetworkCallback(
                manager = manager,
                request = FoxholeVpnService().trackedNetworkRequest,
                callback = callback,
                handler = Handler(Looper.getMainLooper()),
                sdkVersion = 26,
            )
            registered = true
            val capabilities = withTimeout(5_000) { observed.await() }
            assertFalse("upstream registration must observe a physical network", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            assertTrue(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        } finally {
            if (registered) manager.unregisterNetworkCallback(callback)
            launchTestVpnControl(CallbackTestVpnControlActivity.ACTION_STOP)
            withTimeout(10_000) {
                while (manager.hasDefaultVpn()) delay(50)
            }
        }
    }

    private fun ConnectivityManager.hasDefaultVpn(): Boolean =
        activeNetwork?.let(::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun launchTestVpnControl(action: String, targetPackage: String? = null) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = Intent(action)
            .setClassName(context.packageName, CallbackTestVpnControlActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        targetPackage?.let { intent.putExtra(CallbackTestVpnControlActivity.EXTRA_TARGET_PACKAGE, it) }
        context.startActivity(intent)
    }
}
