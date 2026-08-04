package com.foxhole.core.runtime

import com.foxhole.guard.runtime.FoxholeVpnService
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
    fun `vpn service recovers missing connect profile id before start preflight`() {
        val vpnSource = testVpnSourceFile("VpnTorStartBlockReason.kt").readText()
        val vpnConnectBlock =
            vpnSource.substringAfter("internal suspend fun FoxholeVpnService.resolveVpnStartOrReject(")

        assertTrue(vpnConnectBlock.contains("resolveRuntimeConnectProfileId("))
        assertTrue(
            vpnConnectBlock.indexOf("resolveRuntimeConnectProfileId(") <
                vpnConnectBlock.indexOf("rejectBlockedVpnTorStart(")
        )
    }

    private fun testVpnSourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../core/runtime/src/main/kotlin/com/foxhole/core/runtime/$name"),
            File("../app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
        ).first { file -> file.isFile }
}
