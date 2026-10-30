package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCompactBootstrapWizardTest {
    private class Store : RouterPairingStore {
        override suspend fun read(): RouterPairing? = null
        override suspend fun save(pairing: RouterPairing) = error("unexpected pairing")
        override suspend fun remove(expectedId: String) = Unit
    }
    private class Setup(val fixture: RouterCompactBootstrapFixtures) : RouterSetupService {
        override val installCatalog get() = fixture.catalog
        var inspected: RouterInstallProtocolSelection? = null
        override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1, 2, 3)
        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray, selection: RouterInstallProtocolSelection): RouterInstallProposal {
            inspected = selection
            val payload = fixture.release.payload(fixture.system, selection)
            val mode = RouterStorageMode.FLASH
            val plan = requireNotNull(payload.compact).modes.getValue(mode)
            val binding = RouterInstallBinding(selection, plan.binding.platform.id, "x86_64", mode,
                null, null, null, null, plan.binding.budgetSha256, "x86/64", plan.binding)
            return RouterInstallProposal("25.12.5", "x86_64", mode, fixture.manifestSha256, AutoCloseable {}, binding)
        }
        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult = error("unexpected installation")
    }
    private fun wizard(setup: Setup): RouterWizardCoordinator = RouterWizardCoordinator(
        object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = error("unexpected TLS probe")
            override fun api(identity: RouterTlsIdentity): RouterPanelApi = error("unexpected PIN API")
        }, Store(), "Synthetic phone", setup,
        object : RouterWizardProvisioner {
            override val supportedInboundProtocols = setOf("hysteria2")
            override val supportsHomeAutodetection = false
            override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
                options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult = error("unexpected finish")
        }, { null }, { false },
    )

    @Test fun defaultClientSelectionDoesNotAuthorizeAnIncomingServer() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val setup = Setup(fixture); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL")
        wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        assertEquals(RouterWizardStage.PROTOCOLS, wizard.state.value.stage)
        assertEquals(false, wizard.state.value.installSelection?.includeServer)
        assertNull(setup.inspected)
        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
        wizard.confirmInstallProtocols()
        val password = "synthetic".toCharArray(); wizard.login(password)
        assertEquals(false, setup.inspected?.includeServer)
        assertArrayEquals(CharArray(password.size), password)
        assertEquals(RouterWizardStage.INSTALL, wizard.state.value.stage)
        wizard.close()
    }

    @Test fun explicitServerIntentAndExactModulesAreCapturedBeforeLoginAndResourcePlanning() = runTest {
        val fixture = RouterCompactBootstrapFixtures(); fixture.release.initialize()
        val setup = Setup(fixture); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL"); wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        val selected = setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        wizard.installProtocols(selected, true)
        assertEquals(true, wizard.state.value.installSelection?.includeServer)
        assertNull(setup.inspected)
        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
        wizard.confirmInstallProtocols()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        wizard.login("synthetic".toCharArray())
        assertEquals(selected, setup.inspected?.protocols)
        assertEquals(true, setup.inspected?.includeServer)
        assertEquals(RouterWizardStage.INSTALL, wizard.state.value.stage)
        wizard.close()
    }

    @Test fun aVlessOnlyReleaseShowsSelectionInsteadOfInventingADefaultOrRequestingRootEarly() = runTest {
        val fixture = RouterCompactBootstrapFixtures(ids = listOf("vless"), withServer = false); fixture.release.initialize()
        val setup = Setup(fixture); val wizard = wizard(setup)
        assertTrue(wizard.setupAvailable)
        wizard.begin(RouterWizardPath.SETUP, "NL"); wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        assertEquals(RouterWizardStage.PROTOCOLS, wizard.state.value.stage)
        assertNull(wizard.state.value.installSelection)
        assertNull(setup.inspected)
        wizard.installProtocols(setOf(RouterInstallProtocol.VLESS))
        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
        wizard.confirmInstallProtocols(); wizard.login("synthetic".toCharArray())
        assertEquals(setOf(RouterInstallProtocol.VLESS), setup.inspected?.protocols)
        assertEquals(false, setup.inspected?.includeServer)
        wizard.close()
    }

    @Test fun anUnavailableServerOrProtocolNeverReplacesTheConfirmedSelection() = runTest {
        val fixture = RouterCompactBootstrapFixtures(withServer = false); fixture.release.initialize()
        val setup = Setup(fixture); val wizard = wizard(setup)
        wizard.begin(RouterWizardPath.SETUP, "NL"); wizard.inspectAddress("192.168.1.1"); wizard.acceptIdentity()
        val original = wizard.state.value.installSelection
        wizard.installProtocols(setOf(RouterInstallProtocol.VLESS), true)
        assertEquals(original, wizard.state.value.installSelection)
        wizard.installProtocols(setOf(RouterInstallProtocol.TUIC))
        assertEquals(original, wizard.state.value.installSelection)
        assertFalse(requireNotNull(original).includeServer)
        assertNull(setup.inspected)
        wizard.close()
    }
}
