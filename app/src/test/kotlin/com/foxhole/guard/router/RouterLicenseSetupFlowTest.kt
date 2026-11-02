package com.foxhole.guard.router

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouterLicenseSetupFlowTest {
    private class Setup : RouterSetupService {
        override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        var probes = 0
        var authenticated = 0
        var installs = 0
        var closed = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun inspectSshIdentity(address: String): ByteArray { probes++; return byteArrayOf(1, 2, 3) }
        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
            selection: RouterInstallProtocolSelection): RouterInstallProposal {
            authenticated++
            gate?.let { withContext(NonCancellable) { it.await() } }
            val variant = installCatalog.variants.single()
            val binding = RouterInstallBinding(selection, variant.id, variant.architecture, RouterStorageMode.FLASH,
                variant.coreProducerSha256, variant.coreBinarySha256, variant.coreSourceSha256, variant.capabilitiesSha256,
                variant.modes.getValue(RouterStorageMode.FLASH).sha256, "x86/64")
            return RouterInstallProposal("25.12.5", variant.architecture, RouterStorageMode.FLASH,
                installCatalog.manifestSha256, AutoCloseable { closed++ }, binding)
        }
        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult {
            installs++
            throw RouterSetupFailure(RouterWizardError.INSTALL)
        }
    }

    private fun wizard(setup: Setup?, readonly: RouterReadonlySshInspector? = null) = RouterWizardCoordinator(object : RouterWizardTransport {
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = error("unexpected TLS probe")
        override suspend fun inspectDiscoveryIdentity(endpoint: RouterEndpoint): RouterTlsIdentity =
            throw java.net.ConnectException("synthetic package API absent")
        override fun api(identity: RouterTlsIdentity): RouterPanelApi = error("unexpected PIN request")
    }, MemoryRouterPairingStore(), "Synthetic phone", setup, object : RouterWizardProvisioner {
        override val supportedInboundProtocols = emptySet<String>()
        override val supportsHomeAutodetection = false
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?) = error("unexpected finish")
    }, { null }, { false }, readonlyInspector = readonly)

    private suspend fun loginStage(wizard: RouterWizardCoordinator) {
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity(); wizard.confirmInstallProtocols()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
    }

    @Test fun discoveryAndAuthenticatedReadonlyInspectionNeedNoInstallConsent() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        loginStage(wizard)
        assertEquals(1, setup.probes)
        val password = "synthetic".toCharArray(); wizard.login(password)
        assertEquals(1, setup.authenticated)
        assertEquals(RouterWizardStage.INSTALL, wizard.state.value.stage)
        assertFalse(wizard.state.value.options.licenseAccepted)
        assertEquals(0, setup.installs); assertEquals(0, setup.closed)
        assertArrayEquals(CharArray(password.size), password)
        wizard.install()
        assertEquals(RouterWizardError.LICENSE, wizard.state.value.error)
        assertEquals(0, setup.installs); assertEquals(0, setup.closed)
        wizard.close(); assertEquals(1, setup.closed)
    }

    @Test fun failedApplyRetiresAuthorizationAndStaleCheckboxCannotAuthorizeNextTarget() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        loginStage(wizard); wizard.login("synthetic".toCharArray())
        val stale = wizard.state.value.options.copy(licenseAccepted = true)
        wizard.options(stale); wizard.install()
        assertEquals(1, setup.installs); assertEquals(1, setup.closed)
        assertEquals(RouterWizardStage.ADDRESS, wizard.state.value.stage)
        assertFalse(wizard.state.value.options.licenseAccepted)
        wizard.inspectAddress("192.168.1.2"); wizard.acceptIdentity(); wizard.confirmInstallProtocols()
        wizard.login("synthetic".toCharArray()); wizard.options(stale)
        assertFalse(wizard.state.value.options.licenseAccepted)
        wizard.install(); assertEquals(1, setup.installs)
        wizard.close()
    }

    @Test fun earlyAndBusyCheckboxCallbacksCannotGrantConsent() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
        assertFalse(wizard.state.value.options.licenseAccepted)
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity(); wizard.confirmInstallProtocols()
        val old = wizard.state.value.options.copy(licenseAccepted = true)
        setup.gate = CompletableDeferred()
        val request = async { wizard.login("synthetic".toCharArray()) }; runCurrent()
        wizard.options(old); assertFalse(wizard.state.value.options.licenseAccepted)
        requireNotNull(setup.gate).complete(Unit); request.await()
        wizard.options(old); assertFalse(wizard.state.value.options.licenseAccepted)
        wizard.close()
    }

    @Test fun lateReadonlyPlanAfterCloseIsClosedAndCannotPublishInstall() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        loginStage(wizard); setup.gate = CompletableDeferred()
        val password = "synthetic".toCharArray()
        val request = async { wizard.login(password) }; runCurrent(); wizard.close()
        requireNotNull(setup.gate).complete(Unit); request.join()
        assertEquals(1, setup.closed)
        assertFalse(wizard.state.value.options.licenseAccepted)
        assertFalse(wizard.state.value.busy)
        assertArrayEquals(CharArray(password.size), password)
    }

    @Test fun aLateCheckboxFromAClosedWizardCannotAuthorizeItsSuccessor() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        loginStage(wizard); wizard.login("synthetic".toCharArray())
        val stale = wizard.state.value.options.copy(licenseAccepted = true)
        wizard.close(); loginStage(wizard); wizard.login("synthetic".toCharArray()); wizard.options(stale)
        assertFalse(wizard.state.value.options.licenseAccepted)
        wizard.close()
    }

    @Test fun missingQualifiedInstallerStillAllowsReadonlySshWithoutCreatingAnApplyPlan() = runTest {
        var inspections = 0
        val inspector = object : RouterReadonlySshInspector {
            override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1)
            override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray): RouterReadonlyInspection {
                inspections++
                return RouterReadonlyInspection("25.12.5", "aarch64_cortex-a53")
            }
            override suspend fun inspectWithKey(address: String, hostKey: ByteArray, privateKey: ByteArray,
                passphrase: CharArray) = error("unexpected key authentication")
        }
        val wizard = wizard(null, inspector)
        assertTrue(wizard.setupAvailable)
        wizard.begin(RouterWizardPath.SETUP, null)
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        val password = "synthetic".toCharArray(); wizard.login(password)
        assertEquals(1, inspections)
        assertEquals(RouterWizardStage.INSPECTED, wizard.state.value.stage)
        assertEquals("25.12.5", wizard.state.value.openWrtVersion)
        assertEquals(null, wizard.state.value.installSelection)
        assertFalse(wizard.state.value.options.licenseAccepted)
        assertArrayEquals(CharArray(password.size), password)
        wizard.install()
        assertEquals(RouterWizardError.UNAVAILABLE, wizard.state.value.error)
        wizard.close()
    }

    @Test fun directReadonlyServiceCanOpenSshButUnacceptedApplyCannotConsumeIt() = runTest {
        var connected = 0
        val selectedCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        val release = object : RouterSetupRelease {
            override val catalog = selectedCatalog
            override val supportedPlatforms = emptySet<RouterPlatform>()
            override suspend fun initialize() = Unit
            override suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection): RouterSetupPayload =
                error("unexpected payload")
        }
        val service = WifiRouterSetupService(release, { _, _ -> connected++; throw IOException("synthetic SSH stop") }, {}, StandardTestDispatcher(testScheduler))
        val password = "synthetic".toCharArray()
        val failure = runCatching { service.inspect("192.168.1.1", byteArrayOf(1), password, requireNotNull(selectedCatalog.defaultSelection)) }.exceptionOrNull()
        assertTrue(failure is IOException && failure !is RouterLicenseRequired)
        assertEquals(1, connected); assertArrayEquals(CharArray(password.size), password)
        val setup = Setup(); val proposal = setup.inspect("192.168.1.1", byteArrayOf(1), charArrayOf(), requireNotNull(setup.installCatalog.defaultSelection))
        try { service.install(proposal, false); error("apply bypassed license") } catch (_: RouterLicenseRequired) { }
        assertEquals(1, connected); assertEquals(0, setup.closed)
        proposal.close()
    }
}
