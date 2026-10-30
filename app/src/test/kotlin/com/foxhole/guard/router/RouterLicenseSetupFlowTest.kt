package com.foxhole.guard.router

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RouterLicenseSetupFlowTest {
    private class Setup : RouterSetupService {
        override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        var probes = 0
        var authenticated = 0
        override suspend fun inspectSshIdentity(address: String): ByteArray { probes++; return byteArrayOf(1, 2, 3) }
        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
            selection: RouterInstallProtocolSelection): RouterInstallProposal {
            authenticated++
            error("unexpected authenticated SSH inspection")
        }
        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("unexpected installation")
    }

    private fun wizard(setup: Setup) = RouterWizardCoordinator(object : RouterWizardTransport {
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = error("unexpected TLS probe")
        override fun api(identity: RouterTlsIdentity): RouterPanelApi = error("unexpected PIN request")
    }, MemoryRouterPairingStore(), "Synthetic phone", setup, object : RouterWizardProvisioner {
        override val supportedInboundProtocols = emptySet<String>()
        override val supportsHomeAutodetection = false
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?) = error("unexpected finish")
    }, { null }, { false })

    @Test fun readonlyHostKeyProbeDoesNotAuthorizeAuthenticatedSetup() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        wizard.confirmInstallProtocols()
        assertEquals(1, setup.probes)
        assertEquals(0, setup.authenticated)
        assertEquals(RouterWizardError.LICENSE, wizard.state.value.error)
        wizard.close()
    }

    @Test fun revokedConsentBeforeLoginStopsSshAndClearsThePassword() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
        wizard.confirmInstallProtocols()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        wizard.options(wizard.state.value.options.copy(licenseAccepted = false))
        val password = "synthetic".toCharArray(); wizard.login(password)
        assertEquals(0, setup.authenticated)
        assertEquals(RouterWizardError.LICENSE, wizard.state.value.error)
        assertArrayEquals(CharArray(password.size), password)
        wizard.close()
    }

    @Test fun aLateCheckboxFromAClosedWizardCannotAuthorizeItsSuccessor() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL")
        val staleChoice = wizard.state.value.options.copy(licenseAccepted = true)
        wizard.close()
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.options(staleChoice)
        assertEquals(false, wizard.state.value.options.licenseAccepted)
        wizard.close()
    }

    @Test fun directSetupServiceCannotConnectBeforeExplicitAcceptance() = runTest {
        var connected = 0
        val selectedCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        val release = object : RouterSetupRelease {
            override val catalog = selectedCatalog
            override val supportedPlatforms = emptySet<RouterPlatform>()
            override suspend fun initialize() = Unit
            override suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection): RouterSetupPayload =
                error("unexpected payload")
        }
        val service = WifiRouterSetupService(release, { _, _ -> connected++; error("unexpected SSH") }, {}, StandardTestDispatcher(testScheduler))
        val password = "synthetic".toCharArray()
        val result = runCatching { service.inspect("192.168.1.1", byteArrayOf(1), password, requireNotNull(selectedCatalog.defaultSelection)) }
        assertTrue(result.exceptionOrNull() is RouterLicenseRequired)
        assertEquals(0, connected)
        assertArrayEquals(CharArray(password.size), password)
    }
}
