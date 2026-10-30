package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class RouterHttpProvisionerTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val session get() = RouterPanelSession("p1." + "b".repeat(64), false)
    private val profile = "hysteria2://synthetic-password@1.1.1.1:443?sni=vpn.example.com#Home"
    private fun pairing() = RouterPairing(identity, "pair-" + "a".repeat(32), ByteArray(32) { 7 }, "Phone")
    private fun selection(config: String = profile, protocol: String = "hysteria2", source: String? = null) =
        RouterProfileSelection("phone-profile", "Home", protocol, "Chosen node", "chosen-option", source, config)
    private class Store(pairing: RouterPairing) : RouterPairingStore {
        var value = pairing.copy()
        override suspend fun read() = value.copy()
        override suspend fun save(pairing: RouterPairing) { value.clear(); value = pairing.copy() }
        override suspend fun remove(expectedId: String) { error("unrelated delete") }
    }
    private class FixtureCall(private val request: Request, private val state: () -> JsonObject) : Call by OkHttpClient().newCall(request) {
        override fun enqueue(responseCallback: Callback) {
            responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(state().toString().toResponseBody("application/json".toMediaType())).build())
        }
    }

    private class Router {
        var revision = 5L
        var settings = Json.parseToJsonElement("""{"language":"ru","localRules":false,"localScope":"selected","killSwitch":true,"clientRules":true,"theme":"dark"}""").jsonObject
        var countries = Json.parseToJsonElement("""[{"code":"DE","route":"vpn"}]""") as JsonArray
        var devices = Json.parseToJsonElement("""[{"id":"device-000000000001","name":"Existing","address":"192.168.1.50","mac":"02:01:02:03:04:05","bindMac":true,"route":"vpn"}]""") as JsonArray
        var sites = Json.parseToJsonElement("""[{"id":"site-000000000001","pattern":"example.com","route":"direct"}]""") as JsonArray
        val configs = linkedMapOf<String, String>()
        val subscriptions = linkedMapOf<String, JsonObject>()
        var core = false
        var reportedCoreProtocols: Set<String>? = null
        private val coreConfig = """{"schema_version":1,"outbound":{"type":"vless","server":"1.1.1.1","port":443}}"""
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val calls = mutableListOf<Pair<RouterMobileAction, JsonObject>>()
        var failSavedAck = false
        var remoteUri: String? = null
        var ignoreMetadata = false
        fun state() = buildJsonObject {
            put("schema", 1); put("revision", revision); put("settings", settings); put("countries", countries); put("devices", devices); put("sites", sites)
            put("servers", JsonArray(configs.keys.map { buildJsonObject { put("id", it); put("name", "Home"); put("endpoint", "1.1.1.1:443") } }))
            put("clients", JsonArray(emptyList()))
            put("runtime", buildJsonObject { put("desiredServerId", JsonNull); put("connected", true) })
        }
        suspend fun request(api: RouterPanelApi, action: RouterMobileAction, payload: JsonObject): JsonObject {
            assertTrue(payload["token"]!!.jsonPrimitive.content.startsWith("p1."))
            assertEquals("192.168.1.1", api.identity.endpoint.address)
            calls += action to payload
            return when (action) {
                RouterMobileAction.PHONE_CAPABILITIES -> buildJsonObject {
                    put("schema", 1); put("inboundProtocols", JsonArray(if (remoteUri != null) listOf(JsonPrimitive("hysteria2")) else emptyList()))
                    put("phoneRemoteProvisioning", remoteUri != null)
                    put("coreProtocols", JsonArray((reportedCoreProtocols ?: if (core) setOf("vless", "shadowsocks") else emptySet()).map(::JsonPrimitive)))
                    put("legacyProtocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
                    put("subscriptionSelection", true); put("independentPhoneChoices", false); put("phoneRegistration", true); put("homeProof", false)
                }
                RouterMobileAction.PROFILE_TEST -> buildJsonObject {
                    put("valid", true); put("scope", "config"); put("code", "validated"); put("name", "Home"); put("endpoint", "1.1.1.1:443")
                    put("configSha256", digest(if (core) coreConfig else payload["config"]!!.jsonPrimitive.content))
                }
                RouterMobileAction.PROFILE_SAVE -> {
                    configs["server-000000000001"] = if (core) coreConfig else payload["config"]!!.jsonPrimitive.content
                    if (!ignoreMetadata) (payload["subscription"] as? JsonObject)?.let { subscriptions["server-000000000001"] = it }
                    revision++
                    if (failSavedAck) { failSavedAck = false; throw RouterApiFailure(RouterApiError.NETWORK) }
                    state()
                }
                RouterMobileAction.PROFILE_GET -> buildJsonObject {
                    val id = payload["id"]!!.jsonPrimitive.content
                    put("id", id); put("config", " \n" + configs[id]!! + "\n "); put("configSha256", digest(configs[id]!!))
                    if (core) put("engine", "core")
                    subscriptions[id]?.let { put("subscription", it) }
                }
                RouterMobileAction.SETTINGS -> {
                    assertEquals(revision.toString(), payload["revision"]!!.jsonPrimitive.content)
                    settings = payload["settings"]!!.jsonObject; countries = payload["countries"] as JsonArray
                    devices = payload["devices"] as JsonArray; sites = payload["sites"] as JsonArray; revision++
                    state()
                }
                RouterMobileAction.PHONE_PROVISION -> buildJsonObject {
                    put("revision", revision); put("lanDeviceId", "device-000000000002")
                    put("remoteClientId", if (remoteUri != null) JsonPrimitive("client-000000000001") else JsonNull)
                    put("profileUri", remoteUri?.let(::JsonPrimitive) ?: JsonNull); put("remoteReady", false)
                }
                RouterMobileAction.PHONE_STATUS -> buildJsonObject {
                    put("schema", 1); put("pairingId", "pair-" + "a".repeat(32)); put("lanDeviceId", "device-000000000002")
                    put("address", payload["address"]!!); put("serverId", JsonNull); put("generation", JsonNull)
                    put("validated", false); put("vpnBound", false); put("routed", false); put("failClosed", false); put("checkedAgeMs", JsonNull)
                }
                else -> error("unexpected fixture action $action")
            }
        }
    }

    private fun api(router: Router) = RouterPanelApi(identity, Call.Factory { FixtureCall(it, router::state) })

    @Test fun setupUpdateChoiceUsesPairedSettingsAndTheActualUpdaterRevision() = runTest {
        for (enabled in listOf(true, false)) {
            val requests = mutableListOf<Pair<RouterMobileAction, JsonObject>>()
            val client = RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
                mobileCall = { _, action, payload ->
                requests += action to payload
                val status = UpdateDocuments.status() plusField ("revision" to JsonPrimitive(if (action == RouterMobileAction.UPDATES_STATUS) 13 else 14))
                if (action == RouterMobileAction.UPDATES_SETTINGS) status plusField ("autoUpdate" to
                    (status["autoUpdate"]!!.jsonObject plusField ("enabled" to JsonPrimitive(enabled)))) else status
            })
            val api = api(Router())
            val status = client.updateStatus(api, session)
            val saved = client.setUpdateAuto(api, session, status, enabled)
            assertEquals(enabled, saved.autoUpdate.enabled)
            assertEquals(listOf(RouterMobileAction.UPDATES_STATUS, RouterMobileAction.UPDATES_SETTINGS), requests.map { it.first })
            val request = requests.last().second
            assertEquals(setOf("token", "expectedRevision", "autoUpdateEnabled"), request.keys)
            assertEquals(JsonPrimitive(13), request["expectedRevision"])
            assertEquals(JsonPrimitive(enabled), request["autoUpdateEnabled"])
            assertTrue(request["token"]!!.jsonPrimitive.content.startsWith("p1."))
        }
    }

    @Test fun countrySitesChangePreservesAdvancedRulesAndOtherCountries() = runTest {
        val router = Router()
        val oldDevices = router.devices
        val oldSites = router.sites
        val provisioner = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        val pairing = pairing()
        val result = provisioner.finish(api(router), session, pairing, RouterWizardOptions(countryCode = "NL", registerPhone = false), null)
        assertEquals(pairing.id, result.pairedId)
        assertEquals(JsonPrimitive(true), router.settings["localRules"])
        assertEquals(JsonPrimitive(true), router.settings["killSwitch"])
        assertEquals(JsonPrimitive("selected"), router.settings["localScope"])
        assertEquals(oldDevices, router.devices); assertEquals(oldSites, router.sites)
        assertEquals(2, router.countries.size)
        assertEquals("DE", router.countries[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("direct", router.countries[1].jsonObject["route"]!!.jsonPrimitive.content)
        assertEquals(listOf(RouterMobileAction.PHONE_CAPABILITIES, RouterMobileAction.SETTINGS), router.calls.map { it.first })
    }

    @Test fun aLostProfileSaveReplyCanBeRetriedWithoutDuplicatingOrOverwriting() = runTest {
        val router = Router().apply { failSavedAck = true }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        val pairing = pairing()
        val options = RouterWizardOptions(countryCode = "NL", countrySitesDirect = false, registerPhone = false)
        try { client.finish(api(router), session, pairing, options, selection()); error("lost acknowledgment ignored") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.NETWORK, failure.kind) }
        client.finish(api(router), session, pairing, options, selection())
        assertEquals(1, router.configs.size)
        assertEquals(1, router.calls.count { it.first == RouterMobileAction.PROFILE_SAVE })
        assertEquals(1, router.calls.count { it.first == RouterMobileAction.PROFILE_GET })
    }

    @Test fun subscriptionSourceAndChosenNodeArePreservedWithoutRefetchingOrChoosingAnother() = runTest {
        val router = Router()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), supportsSubscriptionSelection = true, pairingStore = Store(pairing()))
        client.finish(api(router), session, pairing(),
            RouterWizardOptions(countryCode = "NL", countrySitesDirect = false, registerPhone = false), selection(source = "https://subscription.example.com/private-token"))
        val metadata = router.subscriptions.values.single()
        assertEquals("https://subscription.example.com/private-token", metadata["sourceUrl"]!!.jsonPrimitive.content)
        assertEquals("chosen-option", metadata["selectedOptionId"]!!.jsonPrimitive.content)
        assertEquals("Chosen node", metadata["selectedNodeName"]!!.jsonPrimitive.content)
        assertTrue(router.configs.values.single().startsWith("hysteria2://"))
        assertFalse(router.configs.values.single().contains("subscription.example.com"))
    }

    @Test fun unsupportedProfilesAndUnimplementedSelectionsFailBeforeRouterMutation() = runTest {
        val router = Router()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        val pairing = pairing()
        try { client.finish(api(router), session, pairing, RouterWizardOptions(countryCode = "NL", countrySitesDirect = false, registerPhone = false),
            selection("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls#Home", "vless")); error("unsupported engine accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        try { client.finish(api(router), session, pairing,
            RouterWizardOptions(countryCode = "NL", countrySitesDirect = false, registerPhone = false, publicIp = true, createRemoteProfile = true), null); error("unsupported independent remote accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        assertTrue(router.calls.all { it.first == RouterMobileAction.PHONE_CAPABILITIES })
        assertFalse(client.supportsHomeAutodetection)
    }

    @Test fun unconsentedInsecureRemoteProfileDoesNotBecomeReady() = runTest {
        val router = Router().apply { remoteUri = profile.replace("?sni=", "?insecure=1&sni=") }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        try { client.finish(api(router), session, pairing(),
            RouterWizardOptions(countryCode = "NL", countrySitesDirect = false, publicIp = true, createRemoteProfile = true), null); error("unconsented profile accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INSECURE_TLS, failure.kind) }
        assertEquals(1, router.calls.count { it.first == RouterMobileAction.PHONE_PROVISION })
    }

    @Test fun connectedStatusCannotMasqueradeAsFreshBoundProof() {
        val paired = "pair-" + "a".repeat(32)
        fun status(age: JsonPrimitive? = null, checked: Boolean = false) = buildJsonObject {
            put("schema", 1); put("pairingId", paired); put("lanDeviceId", "device-000000000001"); put("address", "192.168.1.101")
            put("serverId", "server-000000000001"); put("generation", if (checked) JsonPrimitive("0123456789abcdef") else JsonNull)
            put("validated", checked); put("vpnBound", checked); put("routed", checked); put("failClosed", checked)
            put("checkedAgeMs", age ?: JsonNull); put("connected", true)
        }
        assertNull(parseRouterPhoneStatus(status(), paired, "192.168.1.101", 10000).proof)
        assertNull(parseRouterPhoneStatus(status(JsonPrimitive(5001), true), paired, "192.168.1.101", 10000).proof)
        assertEquals(9000L, parseRouterPhoneStatus(status(JsonPrimitive(1000), true), paired, "192.168.1.101", 10000).proof?.verifiedAtElapsedMs)
        try { parseRouterPhoneStatus(status(JsonPrimitive("1000"), true), paired, "192.168.1.101", 10000); error("string proof age accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        try { parseRouterPhoneStatus(status(), paired, "192.168.1.102", 10000); error("different phone accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }

    @Test fun unknownAutomaticCountryCanCompleteWithoutChangingExistingCountryRules() = runTest {
        val router = Router()
        val before = router.state()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler))
        val result = client.finish(api(router), session, pairing(),
            RouterWizardOptions(countryAuto = true, countryCode = "", registerPhone = false), null)
        assertEquals(pairing().id, result.pairedId)
        assertEquals(before, router.state())
        assertFalse(router.calls.any { it.first == RouterMobileAction.SETTINGS })
    }

    @Test fun disablingCountryBypassWithoutATargetCannotReportSuccessOrMutate() = runTest {
        val router = Router()
        val old = router.state()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        for (country in listOf("", "ZZ")) {
            try { client.finish(api(router), session, pairing(),
                RouterWizardOptions(countryCode = country, countryAuto = false, countrySitesDirect = false, registerPhone = false), selection()); error("unknown country accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        }
        assertTrue(router.calls.isEmpty())
        assertEquals(old, router.state())
    }

    @Test fun registeringThePhoneConfirmsItsPairOwnedRecordWithoutInventingVpnProof() = runTest {
        val router = Router()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
            elapsedMs = { 10_000L + testScheduler.currentTime }, pairingStore = Store(pairing()))
        val result = client.finish(api(router), session, pairing(), RouterWizardOptions(countryCode = "NL"), null)
        assertEquals(pairing().id, result.pairedId)
        assertFalse(result.remoteReady)
        assertEquals(listOf(RouterMobileAction.PHONE_CAPABILITIES, RouterMobileAction.SETTINGS, RouterMobileAction.PHONE_PROVISION, RouterMobileAction.PHONE_STATUS), router.calls.map { it.first })
    }

    @Test fun coreNormalizationAndSubscriptionMetadataDoNotCreateADuplicateAfterLostAck() = runTest {
        val router = Router().apply { core = true; failSavedAck = true }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
            coreProtocols = setOf("vless"), supportsSubscriptionSelection = true, pairingStore = Store(pairing()))
        val chosen = selection("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls#Home", "vless",
            "https://subscription.example.com/private-token")
        val options = RouterWizardOptions(countryCode = "NL", registerPhone = false)
        try { client.finish(api(router), session, pairing(), options, chosen); error("lost ack ignored") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.NETWORK, failure.kind) }
        client.finish(api(router), session, pairing(), options, chosen)
        assertEquals(1, router.calls.count { it.first == RouterMobileAction.PROFILE_SAVE })
        assertTrue(router.configs.values.single().startsWith("{"))
        assertEquals("core", router.calls.first { it.first == RouterMobileAction.PROFILE_TEST }.second["engine"]!!.jsonPrimitive.content)
        assertEquals("chosen-option", router.subscriptions.values.single()["selectedOptionId"]!!.jsonPrimitive.content)
    }

    @Test fun slowResponsesAndExplicitLossAreSeparateFromMissingProof() {
        val pair = "pair-" + "a".repeat(32)
        val success = buildJsonObject {
            put("schema", 1); put("pairingId", pair); put("address", "192.168.1.101"); put("lanDeviceId", "device-000000000001")
            put("serverId", "server-000000000001"); put("generation", "0123456789abcdef")
            put("validated", true); put("vpnBound", true); put("routed", true); put("failClosed", true)
            put("checkedAgeMs", 1000); put("lossConfirmed", false); put("observedUptimeMs", 3000)
        }
        assertNull(parseRouterPhoneStatus(success, pair, "192.168.1.101", 10000, 4500).proof)
        assertEquals(8500L, parseRouterPhoneStatus(success, pair, "192.168.1.101", 10000, 500).proof?.verifiedAtElapsedMs)
        val lost = JsonObject(success + mapOf("lossConfirmed" to JsonPrimitive(true), "validated" to JsonPrimitive(false)))
        val parsed = parseRouterPhoneStatus(lost, pair, "192.168.1.101", 10000, 100)
        assertNull(parsed.proof)
        assertEquals(10000L, parsed.confirmedOff?.verifiedAtElapsedMs)
        assertEquals(3000L, parsed.confirmedOff?.observedRouterUptimeMs)
        assertNull(parseRouterPhoneStatus(lost, pair, "192.168.1.101", 10000, 5001).confirmedOff)
    }

    @Test fun ignoredSubscriptionMetadataCannotReportSuccessOrCreateAnotherProfileOnRetry() = runTest {
        val router = Router().apply { ignoreMetadata = true }
        val store = Store(pairing())
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
            supportsSubscriptionSelection = true, pairingStore = store)
        val selected = selection(source = "https://subscription.example.com/private-token")
        val options = RouterWizardOptions(countryCode = "NL", registerPhone = false)
        repeat(2) {
            try { client.finish(api(router), session, pairing(), options, selected); error("missing source ignored") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        }
        assertEquals(1, router.configs.size)
        assertEquals("server-000000000001", store.value.pendingServerId)
        assertTrue(router.calls.filter { it.first == RouterMobileAction.PROFILE_SAVE }.last().second["id"]!!.jsonPrimitive.content.isNotEmpty())
        assertFalse(router.calls.any { it.first == RouterMobileAction.SETTINGS })
    }

    @Test fun anOutlineSubscriptionKeepsItsOriginalSsconfSource() = runTest {
        val router = Router().apply { core = true }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
            coreProtocols = setOf("shadowsocks"), supportsSubscriptionSelection = true, pairingStore = Store(pairing()))
        val credential = java.util.Base64.getEncoder().encodeToString("aes-128-gcm:synthetic-password".toByteArray())
        val source = "ssconf://subscription.example.com/private-token#Home"
        val selected = selection("ss://$credential@1.1.1.1:443#Home", "shadowsocks", source)
        client.finish(api(router), session, pairing(), RouterWizardOptions(countryCode = "NL", registerPhone = false), selected)
        assertEquals(source, router.subscriptions.values.single()["sourceUrl"]!!.jsonPrimitive.content)
    }

    @Test fun malformedOrPrivateSelectedProfilesHaveVisibleFailuresBeforeMutation() = runTest {
        val router = Router()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        for (config in listOf("hysteria2://broken", profile.replace("@1.1.1.1:", "@127.0.0.1:"))) {
            try { client.finish(api(router), session, pairing(), RouterWizardOptions(countryCode = "NL", registerPhone = false), selection(config)); error("invalid profile accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        }
        assertTrue(router.calls.all { it.first == RouterMobileAction.PHONE_CAPABILITIES })
    }

    @Test fun malformedRemoteProfilesCannotEscapeTheRouterErrorBoundary() = runTest {
        val router = Router().apply { remoteUri = "hysteria2://broken" }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        try { client.finish(api(router), session, pairing(),
            RouterWizardOptions(countryCode = "NL", publicIp = true, createRemoteProfile = true), null); error("malformed remote accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }

    @Test fun aPublicIpCannotCreateARemoteServerWithoutAuthenticatedInboundCapability() = runTest {
        val router = Router()
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        try { client.finish(api(router), session, pairing(),
            RouterWizardOptions(countryCode = "NL", publicIp = true, createRemoteProfile = true), null); error("missing inbound accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        assertEquals(listOf(RouterMobileAction.PHONE_CAPABILITIES), router.calls.map { it.first })
    }

    @Test fun productionDefaultsAdmitOnlyTheAuthenticatedInstalledCoreProtocol() = runTest {
        val router = Router().apply { core = true; reportedCoreProtocols = setOf("vless") }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler), pairingStore = Store(pairing()))
        client.finish(api(router), session, pairing(), RouterWizardOptions(countryCode = "NL", registerPhone = false),
            selection("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls#Home", "vless", "https://subscription.example.com/source"))
        assertEquals("core", router.calls.first { it.first == RouterMobileAction.PROFILE_SAVE }.second["engine"]!!.jsonPrimitive.content)
        assertEquals("https://subscription.example.com/source", router.subscriptions.values.single()["sourceUrl"]!!.jsonPrimitive.content)
    }

    @Test fun injectedProtocolCeilingsCannotGrantAProtocolAbsentFromTheAuthenticatedRouter() = runTest {
        val router = Router().apply { reportedCoreProtocols = setOf("hysteria2") }
        val client = RouterHttpProvisioner(router::request, { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler),
            coreProtocols = setOf("vless"), pairingStore = Store(pairing()))
        try { client.finish(api(router), session, pairing(), RouterWizardOptions(countryCode = "NL", registerPhone = false),
            selection("vless://00000000-0000-0000-0000-000000000001@1.1.1.1:443?security=tls#Home", "vless")); error("missing installed protocol accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.UNSUPPORTED, failure.kind) }
        assertEquals(listOf(RouterMobileAction.PHONE_CAPABILITIES), router.calls.map { it.first })
    }

    @Test fun unknownDuplicateOrContradictoryCapabilitiesAreRefused() {
        val base = buildJsonObject {
            put("schema", 1); put("coreProtocols", JsonArray(emptyList())); put("legacyProtocols", JsonArray(emptyList()))
            put("inboundProtocols", JsonArray(emptyList())); put("phoneRemoteProvisioning", false)
            put("subscriptionSelection", true); put("independentPhoneChoices", true); put("phoneRegistration", true); put("homeProof", false)
        }
        assertFalse(parseRouterPanelCapabilities(base).providerResolve)
        assertTrue(parseRouterPanelCapabilities(JsonObject(base + ("providerResolve" to JsonPrimitive(true)))).providerResolve)
        for (value in listOf(
            JsonObject(base + ("providerResolve" to JsonPrimitive("true"))),
            JsonObject(base + ("coreProtocols" to JsonArray(listOf(JsonPrimitive("unknown"))))),
            JsonObject(base + ("coreProtocols" to JsonArray(listOf(JsonPrimitive("vless"), JsonPrimitive("vless"))))),
            JsonObject(base + ("phoneRemoteProvisioning" to JsonPrimitive(true))),
        )) {
            try { parseRouterPanelCapabilities(value); error("invalid capability accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

    @Test fun activeHandoffStatusIsStrictAndUnknownHistoricalClaimsCannotAuthorizePause() {
        val paired = "pair-" + "a".repeat(32)
        val base = buildJsonObject {
            put("schema", 1); put("pairingId", paired); put("address", "192.168.1.101"); put("lanDeviceId", "device-000000000001")
            put("serverId", "server-000000000001"); put("generation", "0123456789abcdef")
            put("validated", true); put("vpnBound", true); put("routed", true); put("failClosed", true); put("checkedAgeMs", 100)
        }
        val entry = buildJsonObject { put("claimId", "b".repeat(32)); put("mode", "phone_vpn"); put("phase", "active"); put("remainingMs", 30000); put("policyRevision", 4) }
        val active = parseRouterPhoneStatus(JsonObject(base + ("handoff" to entry)), paired, "192.168.1.101", 10000, 500)
        assertEquals(39500L, active.handoff?.expiresAtElapsedMs)
        assertTrue(active.handoffKnown)
        assertFalse(routerHandoffCanRelease(active, null))
        assertFalse(routerHandoffCanRelease(active, "a".repeat(32)))
        assertTrue(routerHandoffCanRelease(active, "b".repeat(32)))
        val absent = parseRouterPhoneStatus(base, paired, "192.168.1.101", 10000)
        assertFalse(routerHandoffCanRelease(absent, null))
        val empty = parseRouterPhoneStatus(JsonObject(base + ("handoff" to JsonNull)), paired, "192.168.1.101", 10000)
        assertTrue(routerHandoffCanRelease(empty, null))
        for (bad in listOf(JsonObject(entry + ("remainingMs" to JsonPrimitive(60001))),
            JsonObject(entry + ("phase" to JsonPrimitive("released"))), JsonObject(entry + ("mode" to JsonPrimitive("router_vpn"))))) {
            try { parseRouterPhoneStatus(JsonObject(base + ("handoff" to bad)), paired, "192.168.1.101", 10000); error("malformed handoff accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

}
