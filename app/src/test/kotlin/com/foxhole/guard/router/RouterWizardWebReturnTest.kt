package com.foxhole.guard.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RouterWizardWebReturnTest {
    private class Fixture : RouterWizardTransport {
        val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
        var configured = false
        var license: Boolean? = true
        var transportFailure: IOException? = null
        var hold = false
        var delayed: (() -> Unit)? = null
        var pending: Call? = null
        var inspections = 0
        var apiCreations = 0
        var reads = 0
        override fun gateway() = identity.endpoint.address
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            assertEquals(identity.endpoint, endpoint); inspections++; return identity
        }
        override fun api(identity: RouterTlsIdentity): RouterPanelApi {
            assertEquals(this.identity, identity); apiCreations++
            return RouterPanelApi(identity, Call.Factory { request ->
                transportFailure?.let { throw it }
                object : Call by OkHttpClient().newCall(request) {
                    override fun enqueue(callback: Callback) {
                        reads++
                        val reply = {
                            val licenseField = license?.let { ",\"licenseRequired\":$it" }.orEmpty()
                            val raw = """{"schema":1,"product":"foxhole","installed":true,"pinConfigured":$configured,"resetSupported":true,"version":"1.0.8-r2"$licenseField}"""
                            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                                .body(raw.toResponseBody("application/json".toMediaType())).build())
                        }
                        if (hold) { pending = this; delayed = reply } else reply()
                    }
                }
            })
        }
        fun coordinator() = RouterWizardCoordinator(this, object : RouterPairingStore {
            override suspend fun read(): RouterPairing? = null
            override suspend fun save(pairing: RouterPairing) = error("read-only return must not pair")
            override suspend fun remove(expectedId: String) = error("read-only return must not revoke")
        }, "Synthetic phone", null, object : RouterWizardProvisioner {
            override val supportedInboundProtocols = emptySet<String>()
            override val supportsHomeAutodetection = false
            override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
                options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult = error("no provisioning")
        }, { null }, { false })
    }
    private suspend fun selected(fixture: Fixture): RouterWizardCoordinator = fixture.coordinator().also {
        it.begin(RouterWizardPath.PAIR, null)
        // Discovery awaits production cleanup on real IO; keep its timeout on the same clock.
        withContext(Dispatchers.Default) { it.findRouter() }
        it.connectDiscovered()
        assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, it.state.value.stage)
        assertTrue(it.prepareWebSetupReturn())
        it.webSetupBackgrounded()
    }
    @Test fun returnOpensPairPinOnceAndRetainsExactSelectionWithoutRediscoveryOrCatalog() = runTest {
        val fixture = Fixture(); val coordinator = selected(fixture)
        val before = coordinator.state.value
        fixture.configured = true; fixture.license = false
        coordinator.refreshWebSetupOnForeground()
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertEquals(RouterWizardPath.PAIR, coordinator.state.value.path)
        assertEquals(before.address, coordinator.state.value.address)
        assertEquals(before.discovery?.discoveryId, coordinator.state.value.discovery?.discoveryId)
        assertEquals(1, fixture.inspections); assertEquals(2, fixture.apiCreations)
        val reads = fixture.reads
        coordinator.refreshWebSetupOnForeground(); coordinator.webSetupBackgrounded(); coordinator.refreshWebSetupOnForeground()
        assertEquals(reads, fixture.reads)
        coordinator.close()
    }
    @Test fun configuredPinDoesNotBypassRequiredOrUnknownLicense() = runTest {
        for (license in listOf(true, null)) {
            val fixture = Fixture(); val coordinator = selected(fixture)
            fixture.configured = true; fixture.license = license
            coordinator.refreshWebSetupOnForeground()
            assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, coordinator.state.value.stage)
            coordinator.close()
        }
    }
    @Test fun changedNetworkIdentityOrPermissionCannotRebindTheSelectedRouter() = runTest {
        for (failure in listOf(RouterWifiRequired(), RouterIdentityChanged(), RouterLocalNetworkPermissionRequired())) {
            val fixture = Fixture(); val coordinator = selected(fixture); val before = coordinator.state.value
            fixture.configured = true; fixture.license = false; fixture.transportFailure = failure
            coordinator.refreshWebSetupOnForeground()
            assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, coordinator.state.value.stage)
            assertEquals(before.discovery, coordinator.state.value.discovery)
            assertEquals(1, fixture.inspections); assertEquals(2, fixture.apiCreations)
            assertNotNull(coordinator.state.value.error)
            coordinator.close()
        }
    }
    @Test fun closingDuringRefreshCancelsTheReadAndLateReplyCannotRestorePinStage() = runTest {
        val fixture = Fixture(); val coordinator = selected(fixture)
        fixture.hold = true
        val refresh = launch { coordinator.refreshWebSetupOnForeground() }; runCurrent()
        assertNotNull(fixture.delayed)
        coordinator.close()
        fixture.configured = true; fixture.license = false; fixture.delayed?.invoke(); refresh.join()
        assertTrue(requireNotNull(fixture.pending).isCanceled())
        assertFalse(coordinator.state.value.stage == RouterWizardStage.LOGIN)
        assertFalse(coordinator.state.value.busy)
    }
    @Test fun aSecondBackgroundCancelsTheOldReadButNextForegroundCanRefreshSameSelection() = runTest {
        val fixture = Fixture(); val coordinator = selected(fixture); val discovery = coordinator.state.value.discovery
        fixture.hold = true
        val refresh = launch { coordinator.refreshWebSetupOnForeground() }; runCurrent()
        coordinator.webSetupBackgrounded(); fixture.delayed?.invoke(); refresh.join()
        assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, coordinator.state.value.stage)
        fixture.hold = false; fixture.configured = true; fixture.license = false
        coordinator.refreshWebSetupOnForeground()
        assertEquals(RouterWizardStage.LOGIN, coordinator.state.value.stage)
        assertEquals(discovery?.discoveryId, coordinator.state.value.discovery?.discoveryId)
        coordinator.close()
    }
    @Test fun aReadThatNeverRepliesHasABoundedTimeoutAndPreservesSelection() = runTest {
        val fixture = Fixture(); val coordinator = selected(fixture); val before = coordinator.state.value.discovery
        fixture.hold = true
        val refresh = launch { coordinator.refreshWebSetupOnForeground() }; runCurrent(); advanceTimeBy(5001); runCurrent(); refresh.join()
        assertEquals(RouterWizardStage.PIN_SETUP_REQUIRED, coordinator.state.value.stage)
        assertEquals(before, coordinator.state.value.discovery)
        assertEquals(RouterWizardError.NETWORK, coordinator.state.value.error)
        assertTrue(requireNotNull(fixture.pending).isCanceled())
        coordinator.close()
    }
}
