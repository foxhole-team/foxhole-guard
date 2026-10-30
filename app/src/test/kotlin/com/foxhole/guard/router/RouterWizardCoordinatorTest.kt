package com.foxhole.guard.router

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RouterWizardCoordinatorTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private class Store : RouterPairingStore {
        var saved: RouterPairing? = null
        override suspend fun read() = saved?.copy()
        override suspend fun save(pairing: RouterPairing) { saved?.clear(); saved = pairing.copy() }
        override suspend fun remove(expectedId: String) { if (saved?.id == expectedId) { saved?.clear(); saved = null } }
    }
    private class FixtureCall(private val request: Request, private val body: String?, private val failure: Boolean = false) : Call by OkHttpClient().newCall(request) {
        override fun enqueue(callback: Callback) {
            if (failure) callback.onFailure(this, IOException("synthetic lost acknowledgment"))
            else callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(requireNotNull(body).toResponseBody("application/json".toMediaType())).build())
        }
    }

    private fun transport(pairedIds: MutableList<String> = mutableListOf(), failFirstCreate: Boolean = false,
        initialPin: Boolean = false): RouterWizardTransport {
        var first = true
        val api = RouterPanelApi(identity, Call.Factory { request ->
            val body = Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
            val payload = requireNotNull(body["payload"]).jsonObject
            val result = when (body["action"]?.jsonPrimitive?.content) {
                "unlock" -> """{"token":"${"b".repeat(64)}","pinChangeRequired":$initialPin}"""
                "pin.change" -> """{"token":"${"b".repeat(64)}"}"""
                "pair.create" -> {
                    val id = requireNotNull(payload["id"]).jsonPrimitive.content
                    pairedIds += id
                    if (failFirstCreate && first) { first = false; return@Factory FixtureCall(request, null, true) }
                    """{"id":"$id"}"""
                }
                "pair.unlock" -> """{"token":"p1.${"c".repeat(64)}"}"""
                "lock" -> "{}"
                else -> error("unexpected fixture action")
            }
            FixtureCall(request, result)
        })
        return object : RouterWizardTransport {
            override fun gateway() = "10.1.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint) = identity.also { assertEquals(identity.endpoint, endpoint) }
            override fun api(identity: RouterTlsIdentity) = api
        }
    }

    private fun provisioner(country: String? = "NL", finish: suspend (RouterPairing) -> RouterWizardResult) = object : RouterWizardProvisioner {
        override val supportedInboundProtocols = setOf("hysteria2")
        override val supportsHomeAutodetection = false
        override suspend fun routerCountry(api: RouterPanelApi, session: RouterPanelSession) = country
        override suspend fun availableInboundProtocols(api: RouterPanelApi, session: RouterPanelSession) = supportedInboundProtocols
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?) = finish(pairing)
    }

    private suspend fun paired(coordinator: RouterWizardCoordinator) {
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        coordinator.login("1234".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
    }

    @Test fun unknownRouterCountryCanCompletePairingWithoutUsingThePhoneDefault() = runTest {
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", null,
            provisioner(country = null) { RouterWizardResult(it.id) }, { null }, { true })
        paired(coordinator)
        assertEquals("", coordinator.state.value.options.countryCode)
        assertTrue(coordinator.state.value.options.countryAuto)
        assertTrue(coordinator.state.value.options.valid)
        coordinator.finish()
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
    }

    @Test fun automaticSelectionRestoresObservedWanAfterAManualCountry() = runTest {
        var applied: RouterWizardOptions? = null
        val observed = provisioner { RouterWizardResult(it.id) }
        val recorder = object : RouterWizardProvisioner by observed {
            override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
                options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult {
                applied = options
                return RouterWizardResult(pairing.id)
            }
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", null, recorder, { null }, { true })
        paired(coordinator)
        coordinator.options(coordinator.state.value.options.copy(countryCode = "DE", countryAuto = false))
        assertEquals("DE", coordinator.state.value.options.countryCode)
        coordinator.options(coordinator.state.value.options.copy(countryAuto = true))
        assertEquals("NL", coordinator.state.value.options.countryCode)
        coordinator.finish()
        assertEquals("NL", applied?.countryCode)
        assertEquals(true, applied?.countryAuto)
        coordinator.close()
    }

    @Test fun automaticSelectionClearsManualCountryWhenWanIsUnknown() = runTest {
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", null,
            provisioner(country = null) { RouterWizardResult(it.id) }, { null }, { true })
        paired(coordinator)
        coordinator.options(coordinator.state.value.options.copy(countryCode = "DE", countryAuto = false))
        coordinator.options(coordinator.state.value.options.copy(countryAuto = true))
        assertEquals("", coordinator.state.value.options.countryCode)
        assertTrue(coordinator.state.value.options.valid)
        coordinator.finish()
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun closingBeforeALateFinishCannotImportTheRemoteProfile() = runTest {
        val reply = CompletableDeferred<Unit>()
        var imports = 0
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", null, provisioner {
            val id = it.id
            withContext(NonCancellable) { reply.await() }
            RouterWizardResult(id, "hysteria2://synthetic")
        }, { null }, { imports++; true })
        paired(coordinator)
        coordinator.options(coordinator.state.value.options.copy(publicIp = true, createRemoteProfile = true))
        val job = launch { coordinator.finish() }
        runCurrent()
        coordinator.close()
        reply.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(0, imports)
        assertFalse(coordinator.state.value.stage == RouterWizardStage.COMPLETE)
    }

    @Test fun anInspectionReturnedAfterCloseIsClosedAndCannotReplaceANewWizard() = runTest {
        val ready = CompletableDeferred<Unit>()
        var closed = 0
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1, 2, 3)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection): RouterInstallProposal {
                withContext(NonCancellable) { ready.await() }
                return proposal(installCatalog, selection, AutoCloseable { closed++ })
            }
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("install after close")
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        assertEquals("192.168.1.1", coordinator.state.value.address)
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        coordinator.options(coordinator.state.value.options.copy(licenseAccepted = true))
        coordinator.confirmInstallProtocols()
        val secret = "root-fixture".toCharArray()
        val job = launch { coordinator.login(secret) }
        runCurrent()
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        ready.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, closed)
        assertArrayEquals(CharArray(secret.size), secret)
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
        assertEquals(RouterWizardPath.PAIR, coordinator.state.value.path)
    }

    @Test fun ambiguousPairingRetriesUseTheSameSavedIndependentCredential() = runTest {
        val ids = mutableListOf<String>()
        val store = Store()
        val coordinator = RouterWizardCoordinator(transport(ids, failFirstCreate = true), store, "Phone", null,
            provisioner { RouterWizardResult(it.id) }, { null }, { true })
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        coordinator.login("1234".toCharArray())
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertFalse(requireNotNull(store.saved).active)
        val credential = requireNotNull(store.saved).credential.copyOf()
        coordinator.login("1234".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertEquals(1, ids.distinct().size)
        assertArrayEquals(credential, requireNotNull(store.saved).credential)
        assertTrue(requireNotNull(store.saved).active)
    }

    @Test fun setupDefaultsOnlyToHysteriaAndPassesAnExactSelectedCombinationBeforeInspection() = runTest {
        val hy2 = setOf(RouterInstallProtocol.HYSTERIA2)
        val combined = setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        var inspected: RouterInstallProtocolSelection? = null
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(hy2, combined)
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection): RouterInstallProposal {
                inspected = selection
                return proposal(installCatalog, selection, AutoCloseable {})
            }
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("unexpected install")
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        assertEquals(hy2, coordinator.state.value.installSelection?.protocols)
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        assertEquals(RouterWizardStage.PROTOCOLS, coordinator.state.value.stage)
        coordinator.installProtocols(hy2 + RouterInstallProtocol.VLESS)
        assertEquals(hy2, coordinator.state.value.installSelection?.protocols)
        coordinator.installProtocols(combined)
        coordinator.options(coordinator.state.value.options.copy(licenseAccepted = true))
        coordinator.confirmInstallProtocols()
        val secret = "fixture".toCharArray()
        coordinator.login(secret)
        assertEquals(combined, inspected?.protocols)
        assertEquals(setup.installCatalog.sha256, inspected?.catalogSha256)
        assertEquals(RouterWizardStage.INSTALL, coordinator.state.value.stage)
        assertArrayEquals(CharArray(secret.size), secret)
    }

    @Test fun aProposalForDifferentCompiledProtocolsIsClosedAndNeverInstalled() = runTest {
        val hy2 = setOf(RouterInstallProtocol.HYSTERIA2)
        val vless = setOf(RouterInstallProtocol.VLESS)
        var closed = 0
        var installed = 0
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(hy2, vless)
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection) = proposal(installCatalog,
                    requireNotNull(installCatalog.selection(vless)), AutoCloseable { closed++ })
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult { installed++; return RouterInstallResult(identity) }
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        coordinator.options(coordinator.state.value.options.copy(licenseAccepted = true))
        coordinator.confirmInstallProtocols()
        coordinator.login("fixture".toCharArray())
        assertEquals(RouterWizardError.UNSUPPORTED, coordinator.state.value.error)
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertEquals(1, closed)
        coordinator.install()
        assertEquals(0, installed)
    }

    @Test fun absentVerifiedHysteriaBuildDoesNotEnableSetupOrRequestRootPassword() = runTest {
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.VLESS))
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection): RouterInstallProposal = error("password should not be requested")
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("unexpected install")
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        assertFalse(coordinator.setupAvailable)
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        assertEquals(RouterWizardError.UNAVAILABLE, coordinator.state.value.error)
        assertEquals(RouterWizardStage.IDENTITY, coordinator.state.value.stage)
    }

    @Test fun revokedLocalNetworkPermissionStopsTheProbeWithASpecificVisibleError() = runTest {
        var apiOpened = false
        val transport = object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = throw RouterLocalNetworkPermissionRequired()
            override fun api(identity: RouterTlsIdentity): RouterPanelApi { apiOpened = true; error("permission denied") }
        }
        val coordinator = RouterWizardCoordinator(transport, Store(), "Phone", null, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        coordinator.inspectAddress("192.168.1.1")
        assertEquals(RouterWizardError.LOCAL_NETWORK_PERMISSION, coordinator.state.value.error)
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
        assertFalse(apiOpened)
    }

    @Test fun freshInstallInternallyUnlocksAndRequiresANewPinBeforePairing() = runTest {
        val secret = "9472".toCharArray()
        val result = RouterInstallResult(identity, secret)
        assertArrayEquals(CharArray(secret.size), secret)
        assertFalse(result.toString().contains("9472"))
        val coordinator = RouterWizardCoordinator(transport(initialPin = true), Store(), "Phone", installingSetup { result },
            provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        coordinator.install()
        assertEquals(RouterWizardStage.NEW_PIN, coordinator.state.value.stage)
        assertEquals(null, result.takeBootstrapCredential())
        val chosen = "5826".toCharArray()
        coordinator.changePin(chosen)
        assertArrayEquals(CharArray(chosen.size), chosen)
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
    }

    @Test fun anExistingInstalledPinIsNotReplacedOrAutomaticallyUnlocked() = runTest {
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", installingSetup { RouterInstallResult(identity) },
            provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        coordinator.install()
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertEquals(RouterWizardPath.PAIR, coordinator.state.value.path)
    }

    @Test fun aLateInstallEnrollmentResultIsWipedWithoutUnlockOrPairing() = runTest {
        val ready = CompletableDeferred<Unit>()
        val result = RouterInstallResult(identity, "9472".toCharArray())
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", installingSetup {
            withContext(NonCancellable) { ready.await() }; result
        }, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        val job = launch { coordinator.install() }
        runCurrent()
        coordinator.close()
        ready.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(null, result.takeBootstrapCredential())
        assertFalse(coordinator.state.value.stage == RouterWizardStage.NEW_PIN)
    }

    @Test fun anUnexpectedConfiguredSessionCannotPairUsingTheBootstrapCredential() = runTest {
        val ids = mutableListOf<String>()
        val coordinator = RouterWizardCoordinator(transport(ids, initialPin = false), Store(), "Phone",
            installingSetup { RouterInstallResult(identity, "9472".toCharArray()) }, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        coordinator.install()
        assertEquals(RouterWizardError.IDENTITY, coordinator.state.value.error)
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertEquals(RouterWizardPath.PAIR, coordinator.state.value.path)
        assertTrue(ids.isEmpty())
    }

    @Test fun aLostInstallReplyCannotReuseTheConsumedTransactionOrClaimPairing() = runTest {
        var installed = 0
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", installingSetup {
            installed++; throw RouterSetupFailure(RouterWizardError.INSTALL)
        }, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        coordinator.install()
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
        assertEquals(RouterWizardPath.SETUP, coordinator.state.value.path)
        assertEquals(RouterWizardError.INSTALL, coordinator.state.value.error)
        coordinator.install()
        assertEquals(1, installed)
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
    }

    @Test fun aConfiguredReleaseCanLoadItsVerifiedCatalogBeforeChoosingTheHysteriaDefault() = runTest {
        val catalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        var initialized = false
        val setup = object : RouterSetupService {
            override val installCatalog get() = catalog.takeIf { initialized }
            override suspend fun inspectSshIdentity(address: String): ByteArray { initialized = true; return byteArrayOf(1) }
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection): RouterInstallProposal = error("no root password yet")
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("unexpected install")
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup, provisioner { RouterWizardResult(it.id) }, { null }, { true })
        assertTrue(coordinator.setupAvailable)
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        assertEquals(RouterWizardStage.PROTOCOLS, coordinator.state.value.stage)
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), coordinator.state.value.installSelection?.protocols)
    }

    @Test fun measuredInstallProgressIsClearedOnCloseAndLateEventsCannotReachANewWizard() = runTest {
        var listener: ((RouterInstallProgress) -> Unit)? = null
        val pending = CompletableDeferred<RouterInstallResult>()
        val setup = object : RouterSetupService {
            override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
                selection: RouterInstallProtocolSelection) = proposal(installCatalog, selection, AutoCloseable {})
            override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("progress overload required")
            override suspend fun install(proposal: RouterInstallProposal, onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult {
                listener = onProgress
                onProgress(RouterInstallProgress(RouterInstallProgressStage.UPLOAD, 10, 100))
                return pending.await()
            }
        }
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", setup,
            provisioner { RouterWizardResult(it.id) }, { null }, { true })
        preparedSetup(coordinator)
        val action = launch { coordinator.install() }
        runCurrent()
        assertEquals(10, coordinator.state.value.installProgress?.percent)
        coordinator.close()
        runCurrent()
        assertTrue(action.isCompleted)
        assertEquals(null, coordinator.state.value.installProgress)
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        listener?.invoke(RouterInstallProgress(RouterInstallProgressStage.UPLOAD, 100, 100))
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
        assertEquals(null, coordinator.state.value.installProgress)
    }

    private class SetupUpdates : RouterWizardProvisioner {
        override val supportedInboundProtocols = emptySet<String>()
        override val supportsHomeAutodetection = false
        var enabled = false
        var revision = 9L
        var reads = 0
        var finishes = 0
        var loseAck = false
        var applyWrite = true
        var unsupported = false
        var statusFailures = 0
        var writeFailure: RouterUpdateFailureReason? = null
        var beforeReply: (suspend () -> Unit)? = null
        val writes = mutableListOf<Pair<Long, Boolean>>()
        private fun observedStatus() = parseRouterUpdateStatus(UpdateDocuments.status()).let {
            it.copy(revision = revision, supported = !unsupported,
                availability = if (unsupported) RouterUpdateAvailability.UNSUPPORTED else it.availability,
                failureReason = if (unsupported) RouterUpdateFailureReason.UNSUPPORTED_TARGET else null,
                autoUpdate = it.autoUpdate.copy(enabled = enabled))
        }
        override suspend fun updateStatus(api: RouterPanelApi, session: RouterPanelSession): RouterUpdateStatus {
            assertTrue(session.token().startsWith("p1."))
            reads++
            if (statusFailures > 0) {
                statusFailures--
                throw IOException("synthetic initial updater status failure")
            }
            return observedStatus()
        }
        override suspend fun setUpdateAuto(api: RouterPanelApi, session: RouterPanelSession,
            status: RouterUpdateStatus, enabled: Boolean): RouterUpdateStatus {
            assertEquals(revision, status.revision)
            writes += status.revision to enabled
            writeFailure?.let { throw RouterUpdateFailure(it) }
            if (applyWrite) { this.enabled = enabled; revision++ }
            beforeReply?.invoke()
            if (loseAck) throw IOException("synthetic lost settings acknowledgement")
            return observedStatus()
        }
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult {
            finishes++
            return RouterWizardResult(pairing.id)
        }
    }

    private suspend fun installedForAutoUpdate(updates: SetupUpdates, enabled: Boolean = true): RouterWizardCoordinator {
        val coordinator = RouterWizardCoordinator(transport(initialPin = true), Store(), "Phone",
            installingSetup { RouterInstallResult(identity, "9472".toCharArray()) }, updates, { null }, { true })
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        coordinator.inspectAddress("192.168.1.1"); coordinator.acceptIdentity()
        assertTrue(coordinator.state.value.options.autoUpdateEnabled)
        coordinator.options(coordinator.state.value.options.copy(autoUpdateEnabled = enabled))
        coordinator.options(coordinator.state.value.options.copy(licenseAccepted = true))
        coordinator.confirmInstallProtocols(); coordinator.login("fixture".toCharArray()); coordinator.install()
        assertTrue(coordinator.state.value.installedInThisWizard)
        assertEquals(0, updates.reads); assertTrue(updates.writes.isEmpty())
        coordinator.changePin("5826".toCharArray())
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        return coordinator
    }

    @Test fun newSetupDefaultsOnButWritesOnlyAfterInstalledAndPaired() = runTest {
        val updates = SetupUpdates(); val coordinator = installedForAutoUpdate(updates)
        assertEquals(0, updates.reads)
        coordinator.finish()
        assertEquals(listOf(9L to true), updates.writes)
        assertEquals(1, updates.finishes)
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun explicitSetupOptOutSurvivesInstallAndUsesObservedUpdaterRevision() = runTest {
        val updates = SetupUpdates(); val coordinator = installedForAutoUpdate(updates, enabled = false)
        assertFalse(coordinator.state.value.options.autoUpdateEnabled)
        coordinator.finish()
        assertEquals(listOf(9L to false), updates.writes)
        assertFalse(updates.enabled)
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun existingPairFlowNeverReadsOrWritesTheRouterUpdatePolicy() = runTest {
        val updates = SetupUpdates()
        val coordinator = RouterWizardCoordinator(transport(), Store(), "Phone", null, updates, { null }, { true })
        paired(coordinator); coordinator.finish()
        assertFalse(coordinator.state.value.installedInThisWizard)
        assertEquals(0, updates.reads); assertTrue(updates.writes.isEmpty())
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun changingOnlyAutoAfterInitialStatusFailureDoesNotRepeatCompletedSetup() = runTest {
        val updates = SetupUpdates().apply { statusFailures = 1 }
        val coordinator = installedForAutoUpdate(updates)
        coordinator.finish()
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertFalse(coordinator.state.value.autoUpdateUnconfirmed)
        assertEquals(1, updates.finishes)
        assertEquals(1, updates.reads)
        assertTrue(updates.writes.isEmpty())
        coordinator.options(coordinator.state.value.options.copy(autoUpdateEnabled = false))
        assertFalse(coordinator.state.value.options.autoUpdateEnabled)
        coordinator.finish()
        assertEquals(1, updates.finishes)
        assertEquals(2, updates.reads)
        assertEquals(listOf(9L to false), updates.writes)
        assertFalse(updates.enabled)
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun lostSettingsAckNeedsStatusReadbackAndNeverReplaysTheWrite() = runTest {
        val updates = SetupUpdates().apply { loseAck = true }
        val coordinator = installedForAutoUpdate(updates)
        coordinator.finish()
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertTrue(coordinator.state.value.autoUpdateUnconfirmed)
        assertEquals(1, updates.finishes)
        coordinator.finish()
        assertEquals(2, updates.reads); assertEquals(listOf(9L to true), updates.writes)
        assertEquals(1, updates.finishes)
        assertFalse(coordinator.state.value.autoUpdateUnconfirmed)
        assertEquals(RouterWizardStage.COMPLETE, coordinator.state.value.stage)
        coordinator.close()
    }

    @Test fun anUnobservedSettingRemainsUncertainWithoutASecondWriteOrRepeatedSetup() = runTest {
        val updates = SetupUpdates().apply { loseAck = true; applyWrite = false }
        val coordinator = installedForAutoUpdate(updates)
        coordinator.finish(); coordinator.finish()
        assertEquals(listOf(9L to true), updates.writes)
        assertEquals(1, updates.finishes)
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertTrue(coordinator.state.value.autoUpdateUnconfirmed)
        coordinator.options(coordinator.state.value.options.copy(autoUpdateEnabled = false))
        assertTrue(coordinator.state.value.options.autoUpdateEnabled)
        coordinator.close()
    }

    @Test fun explicitUpdaterErrorsRemainVisibleWithoutCompletingWizardOrReplaying() = runTest {
        val updates = SetupUpdates().apply { writeFailure = RouterUpdateFailureReason.STALE_SESSION }
        val coordinator = installedForAutoUpdate(updates)
        coordinator.finish()
        assertEquals(RouterUpdateFailureReason.STALE_SESSION, coordinator.state.value.autoUpdateFailure)
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertEquals(1, updates.finishes)
        coordinator.finish()
        assertEquals(1, updates.writes.size)
        coordinator.close()
    }

    @Test fun closingDuringSettingsWriteCannotCompleteOrOverwriteANewWizard() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updates = SetupUpdates().apply { beforeReply = { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        val coordinator = installedForAutoUpdate(updates)
        val request = launch { coordinator.finish() }
        entered.await()
        coordinator.begin(RouterWizardPath.PAIR, "NL")
        release.complete(Unit); runCurrent()
        assertTrue(request.isCancelled)
        assertEquals(1, updates.finishes)
        assertEquals(1, updates.writes.size)
        assertEquals(RouterWizardStage.ADDRESS, coordinator.state.value.stage)
        assertFalse(coordinator.state.value.installedInThisWizard)
        assertFalse(coordinator.state.value.autoUpdateUnconfirmed)
        coordinator.close()
    }

    @Test fun unsupportedNewPackageRetainsPairingButDoesNotPretendThePolicyWasSaved() = runTest {
        val updates = SetupUpdates().apply { unsupported = true }
        val coordinator = installedForAutoUpdate(updates)
        coordinator.finish()
        assertEquals(RouterWizardError.UNSUPPORTED, coordinator.state.value.error)
        assertEquals(RouterWizardStage.OPTIONS, coordinator.state.value.stage)
        assertTrue(updates.writes.isEmpty()); assertEquals(1, updates.finishes)
        assertTrue(coordinator.state.value.pairedId != null)
        coordinator.close()
    }

    private fun installingSetup(install: suspend () -> RouterInstallResult) = object : RouterSetupService {
        override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
            selection: RouterInstallProtocolSelection) = proposal(installCatalog, selection, AutoCloseable {})
        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = install()
    }

    private suspend fun preparedSetup(coordinator: RouterWizardCoordinator) {
        coordinator.begin(RouterWizardPath.SETUP, "NL")
        coordinator.inspectAddress("192.168.1.1")
        coordinator.acceptIdentity()
        coordinator.options(coordinator.state.value.options.copy(licenseAccepted = true))
        coordinator.confirmInstallProtocols()
        coordinator.login("fixture".toCharArray())
        assertEquals(RouterWizardStage.INSTALL, coordinator.state.value.stage)
    }

    private fun proposal(catalog: RouterInstallCatalog, selection: RouterInstallProtocolSelection,
        transaction: AutoCloseable): RouterInstallProposal {
        val variant = catalog.variants.single { it.protocols == selection.protocols }
        val binding = RouterInstallBinding(selection, variant.id, variant.architecture, RouterStorageMode.FLASH,
            variant.coreProducerSha256, variant.coreBinarySha256, variant.coreSourceSha256, variant.capabilitiesSha256,
            variant.modes.getValue(RouterStorageMode.FLASH).sha256, "x86/64")
        return RouterInstallProposal("25.12.5", variant.architecture, RouterStorageMode.FLASH, catalog.manifestSha256, transaction, binding)
    }
}
