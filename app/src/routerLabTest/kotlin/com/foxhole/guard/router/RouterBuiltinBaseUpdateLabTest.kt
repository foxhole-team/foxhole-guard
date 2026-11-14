package com.foxhole.guard.router

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** Uses the production updater and relay against a configured disposable older signed BASE. */
class RouterBuiltinBaseUpdateLabTest {
    @Test fun configuredManagementReadbackPreservesProfilesAndPairing() = runBlocking {
        withOldRouter(allowApply = false) { fixture, network, store, real, pairing ->
            network.api(pairing.identity).use { api ->
                api.unlockPairing(pairing).use { session ->
                    val before = real.updateStatus(api, session)
                    assertEquals(BASELINE, before.currentVersion)
                    assertFalse(before.autoUpdate.enabled)
                    val beforeFence = fence(api, session)
                    require(beforeFence.profiles.isNotEmpty()) { "Configured synthetic profiles required" }
                    assertEquals(beforeFence, fence(api, session))
                    assertEquals(before.installedModules, real.updateStatus(api, session).installedModules)
                    assertEquals(pairing.identity.certificateSha256, fixture.text("panelCertificateSha256"))
                    val control = RouterControlCoordinator(network, store, store, real, copyProfile = {})
                    try {
                        control.preparePairedOpen()
                        control.open()
                        assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
                        assertTrue(control.state.value.managementEnabled)
                        assertEquals(beforeFence.profiles.keys,
                            requireNotNull(control.state.value.snapshot).servers.map { it["id"]!!.jsonPrimitive.content }.toSet())
                    } finally { control.close() }
                    assertEquals(beforeFence, fence(api, session))
                }
            }
        }
    }

    @Test fun builtinBaseApplyPreservesProfilesModulesPolicyAndExistingPairing() = runBlocking {
        withOldRouter(allowApply = true) { fixture, network, store, real, pairing ->
            val before = network.api(pairing.identity).use { api ->
                api.unlockPairing(pairing).use { session ->
                    val status = real.updateStatus(api, session)
                    assertEquals(BASELINE, status.currentVersion)
                    assertFalse(status.autoUpdate.enabled)
                    require(status.installedModules.isNotEmpty()) { "Managed module fixture required" }
                    Triple(status, fence(api, session), fixture.text("expectedDelivery"))
                }
            }
            require(before.second.profiles.isNotEmpty()) { "Configured synthetic profiles required" }
            val audit = mutableListOf<RouterMobileAction>()
            val audited = RouterHttpProvisioner(localAddress = { fixture.peerAddress }, elapsedMs = ::clock,
                pairingStore = store, mobileCall = { api, action, payload ->
                    audit += action
                    api.mobileRequest(action, payload)
                })
            val cache = Files.createTempDirectory("owned-router-base-update-").toFile()
            val control = RouterControlCoordinator(network, store, store, audited, copyProfile = {},
                deviceUpdateRelay = RouterDeviceUpdateRelay(RouterDeviceReleaseSource(cache)))
            var relayObserved = false
            var applySucceeded = false
            val observations = launch(start = CoroutineStart.UNDISPATCHED) {
                control.state.collect { state ->
                    if (state.updates.deviceDownloading) {
                        require(state.updates.status?.deviceDownloadKind == RouterUpdateCohortKind.BASE)
                        relayObserved = true
                    }
                    val job = state.updates.job
                    if (job?.progress?.operation == RouterUpdateOperation.APPLY && job.progress.state == RouterEngineJobState.SUCCEEDED)
                        applySucceeded = true
                }
            }
            try {
                control.preparePairedOpen()
                control.open()
                assertEquals(RouterControlStage.CONNECTED, control.state.value.stage)
                control.refreshUpdates()
                if (control.state.value.updates.plan == null && control.state.value.updates.canCheck)
                    control.checkUpdates()
                withTimeout(180_000) {
                    while (control.state.value.updates.plan == null) {
                        control.refreshUpdates()
                        val current = control.state.value.updates
                        require(current.job?.progress?.state !in setOf(RouterEngineJobState.FAILED, RouterEngineJobState.RECOVERY_REQUIRED)) {
                            "Actual updater CHECK failed"
                        }
                        require(current.status?.currentVersion in setOf(BASELINE, null)) { "Unexpected BASE before confirmation" }
                        delay(250)
                    }
                }
                val prepared = requireNotNull(control.state.value.updates.plan)
                assertEquals(BASELINE, prepared.fromVersion)
                assertEquals(TARGET, prepared.toVersion)
                assertEquals(before.first.installedModules.map { it.protocol }.toSet(), prepared.protocols)
                assertTrue(control.state.value.updates.canApply)
                when (before.third) {
                    "DEVICE_RELAY" -> assertTrue("Actual device relay path required", relayObserved)
                    "DIRECT" -> assertFalse("Direct-only fixture unexpectedly used relay", relayObserved)
                    else -> error("Unsupported fixture delivery mode")
                }
                control.applyUpdate(prepared.planId, prepared.planSha256)
                withTimeout(180_000) {
                    while (!applySucceeded || control.state.value.updates.status?.currentVersion != TARGET) {
                        // Recovery only reads/reopens; APPLY is deliberately never replayed.
                        control.refreshUpdates(statusOnly = true)
                        val observed = control.state.value.updates.job
                        if (observed?.progress?.operation == RouterUpdateOperation.APPLY &&
                            observed.progress.state == RouterEngineJobState.SUCCEEDED) applySucceeded = true
                        val current = control.state.value.updates
                        require(current.job?.progress?.state !in setOf(RouterEngineJobState.FAILED, RouterEngineJobState.RECOVERY_REQUIRED)) {
                            "Actual updater APPLY failed"
                        }
                        require(control.state.value.stage != RouterControlStage.PAIRING_REQUIRED) { "Pairing lost during update" }
                        delay(250)
                    }
                }
                assertEquals(1, audit.count { it == RouterMobileAction.UPDATES_APPLY })
                assertEquals(0, audit.count { it in setOf(RouterMobileAction.PROFILE_SAVE, RouterMobileAction.PROFILE_DELETE,
                    RouterMobileAction.SETTINGS, RouterMobileAction.UPDATES_SETTINGS) })
                network.api(pairing.identity).use { api ->
                    api.unlockPairing(pairing).use { session ->
                        val after = real.updateStatus(api, session)
                        assertEquals(TARGET, after.currentVersion)
                        assertEquals(before.first.routerId, after.routerId)
                        assertFalse(after.autoUpdate.enabled)
                        assertEquals(before.first.installedModules.toSet(), after.installedModules.toSet())
                        val completed = requireNotNull(after.lastOwnJob)
                        assertEquals(RouterUpdateOperation.APPLY, completed.operation)
                        assertEquals(RouterEngineJobState.SUCCEEDED, completed.state)
                        assertEquals(prepared.planId, completed.planId)
                        assertEquals(before.second, fence(api, session))
                    }
                }
                assertEquals(pairing.identity.certificateSha256,
                    network.inspectIdentity(pairing.identity.endpoint).certificateSha256)
                store.readById(pairing.id).let { saved ->
                    requireNotNull(saved)
                    try { assertTrue(pairing.credential.contentEquals(saved.credential)); assertEquals(pairing.identity, saved.identity) }
                    finally { saved.clear() }
                }
                println("LAB BASE104-to106 actual updater PASS; existing pairing/cert/profile/module/policy fences preserved")
            } finally {
                observations.cancel()
                control.close()
                cache.deleteRecursively()
            }
        }
    }

    private suspend fun withOldRouter(allowApply: Boolean,
        block: suspend (UpdateFixture, RouterDefaultSetupLabTest.FixedLabNetwork, MemoryRouterPairingStore,
            RouterHttpProvisioner, RouterPairing) -> Unit) {
        val fixture = UpdateFixture.load(allowApply)
        RouterDefaultSetupLabTest.FixedLabNetwork(fixture.base).use { network ->
            network.installationCompleted()
            val identity = network.inspectIdentity(RouterEndpoint(fixture.base.logicalAddress))
            require(identity.certificateSha256 == fixture.text("panelCertificateSha256")) { "Fixture TLS identity changed" }
            val raw = fixture.base.secret("existingPairingCredentialHex")
            val credential = try {
                require(raw.size == 64 && raw.all { it in "0123456789abcdef" })
                raw.concatToString().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } finally { raw.fill('\u0000') }
            val pairing = try { RouterPairing(identity, fixture.text("existingPairingId"), credential.copyOf(), "Owned update fixture", true).also(RouterPairing::validate) }
                finally { credential.fill(0) }
            try {
                MemoryRouterPairingStore().use { store ->
                    store.save(pairing)
                    store.save(RouterControlPreferences(pairing.id, enabled = true))
                    val real = RouterHttpProvisioner(localAddress = { fixture.peerAddress }, elapsedMs = ::clock, pairingStore = store)
                    block(fixture, network, store, real, pairing)
                }
            } finally { pairing.clear() }
        }
    }

    private class UpdateFixture(val base: RouterDefaultSetupLabTest.Fixture, private val value: JsonObject) {
        val peerAddress get() = base.peerAddress
        fun text(name: String) = value[name]!!.jsonPrimitive.content
        companion object {
            fun load(allowApply: Boolean): UpdateFixture {
                val base = RouterDefaultSetupLabTest.Fixture.load()
                require(!base.initialAutoUpdate) { "Configured auto-update OFF fixture required" }
                val raw = base.file.readBytes()
                val value = try { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject } finally { raw.fill(0) }
                require(value["baselineVersion"]!!.jsonPrimitive.content == BASELINE)
                require(value["baselineBaseSha256"]!!.jsonPrimitive.content == BASELINE_SHA256)
                require(value["targetVersion"]!!.jsonPrimitive.content == TARGET)
                require(value["targetBaseSha256"]!!.jsonPrimitive.content == TARGET_SHA256)
                require(value["existingPairingId"]!!.jsonPrimitive.content.matches(Regex("pair-[a-f0-9]{32}")))
                require(value["panelCertificateSha256"]!!.jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                require(value["expectedDelivery"]!!.jsonPrimitive.content in setOf("DIRECT", "DEVICE_RELAY"))
                require(value["deployment"]!!.jsonPrimitive.content in setOf("FLASH", "RAM_BASE"))
                require(value["expectedDelivery"]!!.jsonPrimitive.content != "DEVICE_RELAY" || value["deployment"]!!.jsonPrimitive.content == "RAM_BASE")
                if (allowApply) require(value["deployment"]!!.jsonPrimitive.content == "RAM_BASE") { "This suite qualifies the RAM BASE transaction" }
                if (allowApply) require(value["allowPackageApply"]!!.jsonPrimitive.boolean) { "Explicit disposable-guest APPLY permission required" }
                return UpdateFixture(base, value)
            }
        }
    }

    private data class Fence(val settings: String, val profiles: Map<String, String>, val pairCount: Int) {
        override fun toString() = "Fence(profiles=${profiles.size}, pairCount=$pairCount)"
    }
    private suspend fun fence(api: RouterPanelApi, session: RouterPanelSession): Fence {
        val state = api.state(session)
        val fields = setOf("settings", "countries", "devices", "sites", "clients")
        require(state.keys.containsAll(fields))
        require(state["runtime"]!!.jsonObject["desiredServerId"] == JsonNull) { "Inactive synthetic profiles required" }
        val pairs = state["pairedDeviceCount"]!!.jsonPrimitive.int
        require(pairs >= 1)
        val profiles = state["servers"]!!.jsonArray.map { it.jsonObject }.associate { profile ->
            val id = profile["id"]!!.jsonPrimitive.content
            val config = api.mobileRequest(RouterMobileAction.PROFILE_GET, buildJsonObject {
                put("token", session.token()); put("id", id)
            })
            require(config["id"]!!.jsonPrimitive.content == id)
            require(config.keys.containsAll(setOf("id", "name", "config", "configSha256")))
            id to sha(canonical(config).toString().toByteArray())
        }
        return Fence(sha(canonical(JsonObject(state.filterKeys { it in fields })).toString().toByteArray()), profiles, pairs)
    }

    companion object {
        private const val BASELINE = "1.0.4-r1"
        private const val TARGET = "1.0.6-r1"
        private const val BASELINE_SHA256 = "f5ad78213c3f019ea0de2579a8effc1cc625d559ebc6b55c3739bc708df5d71d"
        private const val TARGET_SHA256 = "9d3814c09ad38c6f88ef7d7eee52fb9c30f5a28e507f7606df5585f4744062c0"
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun clock() = System.nanoTime() / 1_000_000L
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
