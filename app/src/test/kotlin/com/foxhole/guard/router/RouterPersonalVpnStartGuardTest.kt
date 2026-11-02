package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterPersonalVpnStartGuardTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pair = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)
    private val origin = RouterTrustedProfile("own", "d".repeat(64), identity.certificateSha256,
        RouterProfileMarker(1, "foxhole-openwrt-client", pair.id, "client-" + "e".repeat(12), "f".repeat(64)))
    private inner class Origins : RouterPersonalVpnOrigins {
        var revision = 0L
        override val startRevision get() = revision
        override suspend fun trustedProfileForStart(profileId: String, configSha256: String) =
            origin.takeIf { profileId == it.profileId && configSha256 == it.configSha256 }
        override suspend fun readById(id: String) = pair.copy().takeIf { id == pair.id }
    }
    private inner class Wifi : RouterWizardTransport {
        var present = true
        var observed = identity
        var revoked = false
        var offline = false
        var hold: CompletableDeferred<Unit>? = null
        var afterState: () -> Unit = {}
        val actions = mutableListOf<String>()
        override fun hasPhysicalWifi() = present
        override fun gateway() = identity.endpoint.address
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            hold?.await()
            if (offline) throw IOException("fixture_offline")
            return observed.copy(endpoint = endpoint)
        }
        override fun api(identity: RouterTlsIdentity) = RouterPanelApi(identity, Call.Factory { request ->
            val payload = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
            val action = payload.getValue("action").jsonPrimitive.content
            actions += action
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(responseCallback: Callback) {
                    val result = when (action) {
                        "pair.unlock" -> buildJsonObject {
                            if (revoked) put("_foxhole_error", "unauthorized") else put("token", "p1." + "c".repeat(64))
                        }
                        "state" -> buildJsonObject {
                            put("schema", 1); put("revision", 1)
                            put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
                            for (key in listOf("countries", "devices", "sites", "clients", "servers")) put(key, JsonArray(emptyList()))
                            put("runtime", buildJsonObject { put("desiredServerId", JsonNull); put("connected", false) })
                        }.also { afterState() }
                        else -> error("unexpected fixture action")
                    }
                    responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build())
                }
            }
        })
    }
    @Test fun unrelatedProviderOrChangedConfigNeverProbesOrBlocks() = runTest {
        val wifi = Wifi(); val guard = RouterPersonalVpnStartGuard(Origins(), wifi)
        guard.requireAway("provider", origin.configSha256) { true }
        guard.requireAway("own", "0".repeat(64)) { true }
        assertTrue(wifi.actions.isEmpty())
    }
    @Test fun onlyFreshAuthenticatedHomeProofBlocksTheExactTrustedProfile() = runTest {
        val wifi = Wifi(); var blocked = false
        try { RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true } }
        catch (_: RouterPersonalVpnAtHome) { blocked = true }
        assertTrue(blocked); assertEquals(listOf("pair.unlock", "state"), wifi.actions)
    }
    @Test fun noPhysicalWifiAllowsRemotePersonalVpn() = runTest {
        val wifi = Wifi().also { it.present = false }
        RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true }
        assertTrue(wifi.actions.isEmpty())
    }
    @Test fun differentRouterCertificateNeverReceivesCredentialsOrBlocks() = runTest {
        val wifi = Wifi().also { it.observed = identity.copy(certificateSha256 = "0".repeat(64)) }
        RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true }
        assertTrue(wifi.actions.isEmpty())
    }
    @Test fun unreachableHomeStaysUnknownAndDoesNotBlockRemoteStart() = runTest {
        val wifi = Wifi().also { it.offline = true }
        RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true }
        assertTrue(wifi.actions.isEmpty())
    }
    @Test fun revokedPairingCannotBecomeAHomeProof() = runTest {
        val wifi = Wifi().also { it.revoked = true }
        RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true }
        assertEquals(listOf("pair.unlock"), wifi.actions)
    }
    @Test fun changedPairingDuringStateReadCannotPublishHome() = runTest {
        val origins = Origins(); val wifi = Wifi().also { it.afterState = { origins.revision++ } }
        RouterPersonalVpnStartGuard(origins, wifi).requireAway("own", origin.configSha256) { true }
        assertEquals(listOf("pair.unlock", "state"), wifi.actions)
    }
    @Test fun stopOrProfileChangeCancelsBeforeStartAndDoesNotBecomeHome() = runTest {
        var current = true; val wifi = Wifi().also { it.afterState = { current = false } };var cancelled = false
        try { RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { current } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun unavailableOriginVaultStaysUnknownWithoutBlockingProviderStart() = runTest {
        val wifi = Wifi()
        val origins = object : RouterPersonalVpnOrigins {
            override val startRevision = 0L
            override suspend fun trustedProfileForStart(profileId: String, configSha256: String): RouterTrustedProfile? = throw IOException("fixture_vault_unavailable")
            override suspend fun readById(id: String): RouterPairing? = error("no origin")
        }
        RouterPersonalVpnStartGuard(origins, wifi).requireAway("own", origin.configSha256) { true }
        assertTrue(wifi.actions.isEmpty())
    }
    @Test fun cancellationClosesDiscoveryWithoutAuthenticating() = runTest {
        val wifi = Wifi().also { it.hold = CompletableDeferred() }
        val pending = launch { RouterPersonalVpnStartGuard(Origins(), wifi).requireAway("own", origin.configSha256) { true } }
        runCurrent();pending.cancel();runCurrent();assertTrue(pending.isCancelled);assertTrue(wifi.actions.isEmpty())
    }
}
