package com.foxhole.core.runtime

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.FoxholeApplication
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CoreAuditPixelAndroidTest {
    @Test
    fun activeProfileCarriesTrafficAcrossReloadsAndReconnects() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("foxhole.coreAudit") == "1")
        val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        val controller = app.appGraph.connectionController
        val profile = app.appGraph.profileRepository.getActiveProfile()
        assertNotNull("Select an existing VPN profile before this opt-in test", profile)
        checkNotNull(profile)
        assertEquals("0.0.5", BuildConfig.FOXCORE_SOURCE_VERSION)
        assertEquals(BuildConfig.FOXCORE_SOURCE_VERSION, FoxholeNativeEngine.nativeVersion())
        assertEquals("VPN consent must already be granted", null, VpnService.prepare(app))
        val manager = app.getSystemService(ConnectivityManager::class.java)
        try {
            controller.disconnect(suppressLocalGuard = true)
            awaitState(app, ConnectionState.IDLE)
            repeat(2) { cycle ->
                controller.connect(profile.id)
                awaitState(app, ConnectionState.CONNECTED)
                val network = awaitVpn(manager)
                assertVpnTraffic(network)
                if (cycle == 0) {
                    repeat(2) {
                        val previous = app.appGraph.diagnosticsLogger.entries.value
                            .count { entry -> entry.tag == "foxcore" && entry.message.contains("policy_revision=") }
                        assertTrue("Reload request was refused", controller.reload(profile.id))
                        withTimeout(30_000L) {
                            while (app.appGraph.diagnosticsLogger.entries.value
                                    .count { entry -> entry.tag == "foxcore" && entry.message.contains("policy_revision=") } <= previous) {
                                delay(200L)
                            }
                        }
                        awaitState(app, ConnectionState.CONNECTED)
                        assertVpnTraffic(awaitVpn(manager))
                    }
                }
                controller.disconnect(suppressLocalGuard = true)
                awaitState(app, ConnectionState.IDLE)
                withTimeout(15_000L) {
                    while (vpn(manager) != null) delay(200L)
                }
            }
        } finally {
            controller.disconnect(suppressLocalGuard = true)
        }
    }

    private suspend fun awaitState(app: FoxholeApplication, expected: ConnectionState) {
        withTimeout(90_000L) {
            while (app.appGraph.connectionController.snapshot.value.state != expected) {
                check(app.appGraph.connectionController.snapshot.value.state != ConnectionState.ERROR) {
                    "Runtime entered ERROR while waiting for $expected"
                }
                delay(200L)
            }
        }
    }

    private suspend fun awaitVpn(manager: ConnectivityManager): Network = withTimeout(15_000L) {
        var network = vpn(manager)
        while (network == null) {
            delay(200L)
            network = vpn(manager)
        }
        network
    }

    @Suppress("DEPRECATION")
    private fun vpn(manager: ConnectivityManager): Network? = manager.allNetworks.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private suspend fun assertVpnTraffic(network: Network) = withContext(Dispatchers.IO) {
        val connection = network.openConnection(URL("https://www.cloudflare.com/cdn-cgi/trace")) as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            assertEquals("VPN-bound HTTPS failed", 200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().use { it.readText().take(4096) }
            assertTrue("VPN-bound response has no IP evidence", response.lineSequence().any { it.startsWith("ip=") })
        } finally {
            connection.disconnect()
        }
    }
}
