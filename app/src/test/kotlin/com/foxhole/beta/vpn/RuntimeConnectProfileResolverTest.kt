package com.foxhole.beta.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeConnectProfileResolverTest {
    @Test
    fun `valid service profile id is used directly`() =
        runBlocking {
            val resolved =
                resolveRuntimeConnectProfileId(
                    requestedProfileId = 7L,
                    activeProfileIdProvider = { 9L },
                )

            assertEquals(7L, resolved)
        }

    @Test
    fun `missing service profile id recovers active profile`() =
        runBlocking {
            val resolved =
                resolveRuntimeConnectProfileId(
                    requestedProfileId = -1L,
                    activeProfileIdProvider = { 9L },
                )

            assertEquals(9L, resolved)
        }

    @Test
    fun `missing service profile id stays missing when repository has none`() =
        runBlocking {
            val resolved =
                resolveRuntimeConnectProfileId(
                    requestedProfileId = -1L,
                    activeProfileIdProvider = { null },
                )

            assertNull(resolved)
        }

    @Test
    fun `tor only profile id bypasses active profile recovery`() =
        runBlocking {
            val resolved =
                resolveRuntimeConnectProfileId(
                    requestedProfileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    torOnlyProfileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    activeProfileIdProvider = { 9L },
                )

            assertEquals(FoxholeVpnService.TOR_ONLY_PROFILE_ID, resolved)
        }

    @Test
    fun `vpn and proxy services recover missing connect profile id before surfacing missing profile`() {
        val vpnSource = testVpnSourceFile("FoxholeVpnService.kt").readText()
        val vpnConnectBlock =
            vpnSource.substringAfter("internal suspend fun connect(")
                .substringBefore("val privateDnsState")
        val proxySource = testVpnSourceFile("FoxholeProxyService.kt").readText()
        val proxyConnectBlock =
            proxySource.substringAfter("private suspend fun connect(")
                .substringBefore("runtimeSupervisor.beginTransition(\"proxy_connect\")")

        assertTrue(vpnConnectBlock.contains("resolveRuntimeConnectProfileId("))
        assertTrue(vpnConnectBlock.indexOf("resolveRuntimeConnectProfileId(") < vpnConnectBlock.indexOf("error_profile_missing"))
        assertTrue(proxyConnectBlock.contains("resolveRuntimeConnectProfileId("))
        assertTrue(proxyConnectBlock.indexOf("resolveRuntimeConnectProfileId(") < proxyConnectBlock.indexOf("error_profile_missing"))
    }

    private fun testVpnSourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/beta/vpn/$name"),
            File("app/src/main/kotlin/com/foxhole/beta/vpn/$name"),
            File("../app/src/main/kotlin/com/foxhole/beta/vpn/$name"),
        ).first { file -> file.isFile }
}
