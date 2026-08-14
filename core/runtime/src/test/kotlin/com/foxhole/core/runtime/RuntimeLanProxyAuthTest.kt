package com.foxhole.core.runtime

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN proxy leg binds the phone's Wi-Fi address, so an inbound without credentials is an open
 * SOCKS5/HTTP relay into the owner's VPN/Tor for the whole network (stage2 §8). Two invariants,
 * defence in depth on top of settings normalization:
 *
 *  - whenever a LAN inbound is published, it carries a non-empty `users` block; and
 *  - a blank password fails closed — no LAN inbound at all, never an anonymous one.
 *
 * The stored `enabled` flag is deliberately ignored for the LAN leg (forced on), so a stale
 * `enabled = false` payload can no longer produce an anonymous surface.
 */
internal class RuntimeLanProxyAuthTest : RuntimeConfigAssemblerTestSupport() {
    private val lanAddress = "192.168.7.20"

    @Test
    fun `lan inbound is always published with a non-empty users block`() {
        val inbounds =
            buildLocalSurfaceInbounds(
                localSurfaces = lanSurfaces(LocalAuthSettings(username = "boxy", password = "hunter2")),
                includeLocalProxy = false,
                lanListenAddress = lanAddress,
            )

        val lanInbound = inbounds.single { it.tag().endsWith("-in-lan") }
        val users = lanInbound.users()
        assertTrue(users.isNotEmpty())
        assertEquals("boxy", users.single()["username"]!!.jsonPrimitive.content)
        assertEquals("hunter2", users.single()["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a stored disabled auth flag still yields an authenticated lan inbound`() {
        // Old behaviour raised the inbound with no `users` when enabled == false. The flag no longer
        // has that power for the LAN leg.
        val inbounds =
            buildLocalSurfaceInbounds(
                localSurfaces =
                lanSurfaces(LocalAuthSettings(enabled = false, username = "boxy", password = "hunter2")),
                includeLocalProxy = false,
                lanListenAddress = lanAddress,
            )

        val lanInbound = inbounds.single { it.tag().endsWith("-in-lan") }
        assertTrue(lanInbound.users().isNotEmpty())
    }

    @Test
    fun `a blank lan password keeps the lan inbound down`() {
        val inbounds =
            buildLocalSurfaceInbounds(
                localSurfaces = lanSurfaces(LocalAuthSettings(username = "boxy", password = "   ")),
                includeLocalProxy = false,
                lanListenAddress = lanAddress,
            )

        assertNull(inbounds.firstOrNull { it.tag().endsWith("-in-lan") })
    }

    @Test
    fun `a blank username falls back to the default login rather than an anonymous one`() {
        val inbounds =
            buildLocalSurfaceInbounds(
                localSurfaces = lanSurfaces(LocalAuthSettings(username = "  ", password = "hunter2")),
                includeLocalProxy = false,
                lanListenAddress = lanAddress,
            )

        val users = inbounds.single { it.tag().endsWith("-in-lan") }.users()
        assertEquals("foxhole", users.single()["username"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the assembled config carries no lan inbound at all, anonymous or not`() {
        val assembler = RuntimeConfigAssembler(json, FakeLanProxyAddressProvider(lanAddress))
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        allowLanAccess = true,
                        lanAuth = LocalAuthSettings(enabled = false, username = "boxy", password = "hunter2"),
                    ),
                ),
            )

        val config = parse(assembler.assemble(baseConfigWithRules("profile.example"), settings, null))
        val lanInbounds =
            config["inbounds"]!!.jsonArray.map { it.jsonObject }.filter { inbound ->
                inbound.tag().endsWith("-in-lan")
            }

        // Stronger than the property this test used to assert, and for a reason
        // the assertion could not see. The engine takes exactly one non-tun
        // inbound — the loopback control proxy — so emitting a LAN listener
        // beside it failed translation outright and the user was told the
        // profile was invalid, on Wi-Fi only, on a profile that was fine. And
        // there is no JNI entry point that could serve a LAN listener anyway:
        // `foxcore-component::lan` is implemented, nothing exports it. So the
        // config must not contain one, and "if present it must be
        // authenticated" is now a property of `buildLocalSurfaceInbounds`
        // rather than of the assembled config.
        assertTrue(lanInbounds.toString(), lanInbounds.isEmpty())
    }

    private fun lanSurfaces(lanAuth: LocalAuthSettings): LocalSurfaceSettings =
        LocalSurfaceSettings(allowLanAccess = true, lanAuth = lanAuth)

    private fun JsonObject.tag(): String = this["tag"]!!.jsonPrimitive.content

    private fun JsonObject.users(): List<JsonObject> = this["users"]?.jsonArray?.map { it.jsonObject }.orEmpty()
}
