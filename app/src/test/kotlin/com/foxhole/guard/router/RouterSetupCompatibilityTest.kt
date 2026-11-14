package com.foxhole.guard.router

import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouterSetupCompatibilityTest {
    private class Store : RouterPairingStore {
        override suspend fun read(): RouterPairing? = null
        override suspend fun save(pairing: RouterPairing) = error("no pairing during compatibility")
        override suspend fun remove(expectedId: String) = error("no removal during compatibility")
    }
    private open class Setup : RouterPrecheckedSetupFixture() {
        override val installCatalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2),
            setOf(RouterInstallProtocol.VLESS))
        var installed = 0
        var selected: RouterInstallProtocolSelection? = null
        override suspend fun inspectSshIdentity(address: String) = byteArrayOf(1, 2, 3)
        override suspend fun inspect(address: String, hostKey: ByteArray, password: CharArray,
            selection: RouterInstallProtocolSelection): RouterInstallProposal {
            selected = selection
            val variant = installCatalog.variants.single { it.protocols == selection.protocols }
            val binding = RouterInstallBinding(selection, variant.id, variant.architecture, RouterStorageMode.FLASH,
                variant.coreProducerSha256, variant.coreBinarySha256, variant.coreSourceSha256, variant.capabilitiesSha256,
                variant.modes.getValue(RouterStorageMode.FLASH).sha256, "x86/64")
            return RouterInstallProposal("25.12.5", variant.architecture, RouterStorageMode.FLASH,
                installCatalog.manifestSha256, AutoCloseable {}, binding)
        }
        override suspend fun install(proposal: RouterInstallProposal): RouterInstallResult {
            installed++; error("no automatic installation")
        }
    }
    private fun wizard(setup: Setup) = RouterWizardCoordinator(object : RouterWizardTransport {
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = throw ConnectException("synthetic API absent")
        override fun api(identity: RouterTlsIdentity): RouterPanelApi = error("no PIN API during setup precheck")
    }, Store(), "Synthetic phone", setup, object : RouterWizardProvisioner {
        override val supportedInboundProtocols = emptySet<String>()
        override val supportsHomeAutodetection = false
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult = error("no finish")
    }, { null }, { false })
    private suspend fun login(wizard: RouterWizardCoordinator) {
        wizard.begin(RouterWizardPath.SETUP, null)
        wizard.inspectAddress("192.168.1.1")
        wizard.acceptIdentity()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        wizard.login(charArrayOf())
    }

    @Test fun compatibilityPrecedesOptionsAndFinalPlanUsesOnlyTheExplicitSelection() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        login(wizard)
        assertEquals(RouterWizardStage.COMPATIBILITY, wizard.state.value.stage)
        assertNotNull(wizard.state.value.compatibility)
        assertEquals(1, setup.precheckCalls)
        assertEquals(0, setup.finalPlanCalls)
        wizard.installProtocols(setOf(RouterInstallProtocol.VLESS))
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), wizard.state.value.installSelection?.protocols)
        wizard.confirmCompatibility()
        assertEquals(RouterWizardStage.PROTOCOLS, wizard.state.value.stage)
        wizard.installProtocols(setOf(RouterInstallProtocol.VLESS))
        wizard.confirmInstallProtocols()
        assertEquals(RouterWizardStage.INSTALL, wizard.state.value.stage)
        assertEquals(setOf(RouterInstallProtocol.VLESS), setup.selected?.protocols)
        assertEquals(1, setup.precheckCalls)
        assertEquals(1, setup.finalPlanCalls)
        assertEquals(0, setup.installed)
        wizard.close()
    }

    @Test fun cancelAfterCompatibilityClosesItsHandleBeforeAnyPlanOrInstall() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        login(wizard)
        wizard.close()
        assertEquals(1, setup.inspectionCloses)
        assertEquals(0, setup.finalPlanCalls)
        assertEquals(0, setup.installed)
        assertNull(wizard.state.value.compatibility)
    }

    @Test fun aNewWizardGenerationRetiresThePreviousAuthenticatedInspection() = runTest {
        val setup = Setup(); val wizard = wizard(setup)
        login(wizard)
        wizard.begin(RouterWizardPath.PAIR, null)
        assertEquals(1, setup.inspectionCloses)
        assertEquals(RouterWizardStage.ADDRESS, wizard.state.value.stage)
        assertNull(wizard.state.value.compatibility)
        assertEquals(0, setup.finalPlanCalls)
        wizard.close()
    }

    @Test fun anExpiredInspectionReturnsToAuthenticationWithoutRevealingProtocolOptions() = runTest {
        var valid = true
        val setup = object : Setup() {
            override suspend fun precheck(address: String, hostKey: ByteArray, password: CharArray): RouterSetupInspection {
                val result = super.precheck(address, hostKey, password)
                return object : RouterSetupInspection by result {
                    override fun requireCurrent() {
                        if (!valid) throw RouterSetupFailure(RouterWizardError.SSH_UNAVAILABLE)
                        result.requireCurrent()
                    }
                }
            }
        }
        val wizard = wizard(setup); login(wizard)
        valid = false
        wizard.confirmCompatibility()
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        assertEquals(RouterWizardError.SSH_UNAVAILABLE, wizard.state.value.error)
        assertEquals(1, setup.inspectionCloses)
        assertEquals(0, setup.finalPlanCalls)
        wizard.close()
    }

    @Test fun unsupportedPrecheckCannotProduceOptionsOrAProposal() = runTest {
        val setup = object : Setup() {
            override suspend fun precheck(address: String, hostKey: ByteArray, password: CharArray): RouterSetupInspection {
                password.fill('\u0000'); throw RouterSetupFailure(RouterWizardError.UNSUPPORTED)
            }
        }
        val wizard = wizard(setup); login(wizard)
        assertEquals(RouterWizardStage.LOGIN, wizard.state.value.stage)
        assertEquals(RouterWizardError.UNSUPPORTED, wizard.state.value.error)
        assertNull(wizard.state.value.compatibility)
        assertEquals(0, setup.finalPlanCalls)
        assertEquals(0, setup.installed)
        wizard.close()
    }

    @Test fun aQualifiedReleasePrecheckRequiresItsExactFirmwareTargetArchitectureAndKernel() = runTest {
        val fixture = RouterCompactBootstrapFixtures(); fixture.release.initialize()
        fixture.release.verifyCompatibility(fixture.system)
        for (changed in listOf(
            fixture.system.copy(openWrtVersion = "25.12.6"),
            fixture.system.copy(platform = fixture.system.platform.copy(target = "armsr/armv8")),
            fixture.system.copy(platform = fixture.system.platform.copy(architecture = "aarch64_generic")),
            fixture.system.copy(kernelVersion = "6.12.112~unqualified-r1"),
        )) assertTrue(runCatching { fixture.release.verifyCompatibility(changed) }.isFailure)
    }
}
