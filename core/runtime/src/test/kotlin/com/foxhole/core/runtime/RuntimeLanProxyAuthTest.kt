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

        assertTrue(lanInbounds.toString(), lanInbounds.isEmpty())
    }

    private fun lanSurfaces(lanAuth: LocalAuthSettings): LocalSurfaceSettings =
        LocalSurfaceSettings(allowLanAccess = true, lanAuth = lanAuth)

    private fun JsonObject.tag(): String = this["tag"]!!.jsonPrimitive.content

    private fun JsonObject.users(): List<JsonObject> = this["users"]?.jsonArray?.map { it.jsonObject }.orEmpty()
}
