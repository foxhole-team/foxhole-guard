package com.foxhole.guard.router

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiRouterSetupServiceTest {
    private class Client : RouterSshClient {
        val closed = AtomicBoolean()
        var connectedWithoutPassword = false
        var boardVersion = "25.12.5"
        var kernelQueried = false
        var availableFlashKiB = 8000L
        var installed = false
        var connectFailure: Exception? = null
        var packageReceipt = ""
        var enrollmentRequested = false
        override fun connect(password: CharArray?) {
            connectFailure?.let { throw it }
            if (password == null) { connectedWithoutPassword = true; throw RouterSshTrustRequired(byteArrayOf(1, 2, 3)) }
        }
        override fun read(command: RouterReadCommand): ByteArray = when (command) {
            RouterReadCommand.BOARD -> """{"release":{"distribution":"OpenWrt","version":"$boardVersion","target":"x86/64"}}"""
            RouterReadCommand.ARCHITECTURE -> "x86_64\n"
            RouterReadCommand.FLASH -> "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 10000 2000 $availableFlashKiB 20% /overlay\n"
            RouterReadCommand.MEMORY -> "MemAvailable: 400000 kB\n"
            RouterReadCommand.CONFIGURATION -> ""
            RouterReadCommand.KERNEL_PACKAGE -> { kernelQueried = true; "kernel-6.12.75~fixture-r1\n" }
            RouterReadCommand.PACKAGE_MANIFEST -> packageReceipt
            RouterReadCommand.MOBILE_CERTIFICATE -> requireNotNull(javaClass.getResourceAsStream("/router/router-fixture.crt")).use { it.readBytes() }.toString(Charsets.US_ASCII)
            else -> error("unexpected read")
        }.toByteArray()
        override fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand) { installed = true }
        override fun bootstrapEnrollment(): ByteArray {
            enrollmentRequested = true
            return """{"schema":1,"status":"fresh","bootstrapPin":"1943","expiresInSeconds":300}""".toByteArray()
        }
        override fun close() { closed.set(true) }
    }

    private class Release : RouterSetupRelease {
        override val catalog = RouterInstallCatalogTest.catalog(setOf(RouterInstallProtocol.HYSTERIA2))
        override val supportedPlatforms = setOf(RouterPlatform("25.12", "x86/64", "x86_64"))
        var initialized = false
        var gate: CompletableDeferred<Unit>? = null
        var failInitialize = false
        override suspend fun initialize() { if (failInitialize) throw IllegalArgumentException("fixture"); initialized = true }
        override suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection): RouterSetupPayload {
            gate?.await()
            val bytes = "#!/bin/sh\nexit 0\n".toByteArray()
            return RouterSetupPayload(catalog.variants.single(), mapOf(RouterStorageMode.FLASH to RouterInstallBudget(1048576, 1048576, 1048576, 1048576)),
                RouterVerifiedInstallScript(bytes, routerReleaseSha256(bytes)),
                "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0",
                mapOf(RouterStorageMode.FLASH to listOf(RouterPackageIdentity("foxcore-cli", "1.0.0", "core.apk", "a".repeat(64)))))
        }
    }

    @Test fun initialSshProbeCannotCarryAPasswordAndLoadsOnlyVerifiedReleaseMetadata() = runTest {
        val client = Client()
        val release = Release()
        val service = WifiRouterSetupService(release, { _, key -> assertEquals(null, key); client }, {}, StandardTestDispatcher(testScheduler))
        val request = async { service.inspectSshIdentity("192.168.1.1") }
        runCurrent()
        assertArrayEquals(byteArrayOf(1, 2, 3), request.await())
        assertTrue(client.connectedWithoutPassword && client.closed.get() && release.initialized)
    }

    @Test fun successfulInspectionRetainsALiveTransactionAndClearsThePassword() = runTest {
        val client = Client()
        val release = Release()
        val password = "synthetic-password".toCharArray()
        val service = WifiRouterSetupService(release, { _, key -> assertArrayEquals(byteArrayOf(1, 2, 3), key); client }, {}, StandardTestDispatcher(testScheduler))
        val request = async { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), password, requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) }
        runCurrent()
        val proposal = request.await()
        assertFalse(client.closed.get())
        assertEquals(RouterStorageMode.FLASH, proposal.storageMode)
        assertArrayEquals(CharArray(password.size), password)
        proposal.close()
        assertTrue(client.closed.get())
    }

    @Test fun cancellationDuringReleasePreparationClosesTheInspectedSession() = runTest {
        val client = Client()
        val release = Release().apply { gate = CompletableDeferred() }
        val password = "synthetic-password".toCharArray()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val request = async { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), password, requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) }
        runCurrent()
        request.cancel()
        runCurrent()
        assertTrue(client.closed.get())
        assertArrayEquals(CharArray(password.size), password)
    }

    @Test fun insufficientStorageCannotProduceAnInstallProposal() = runTest {
        val client = Client().apply { availableFlashKiB = 1 }
        val release = Release()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val request = async { runCatching { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), "fixture".toCharArray(), requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) } }
        runCurrent()
        assertEquals(RouterWizardError.STORAGE, (request.await().exceptionOrNull() as RouterSetupFailure).kind)
        assertTrue(client.closed.get())
        assertFalse(client.installed)
    }

    @Test fun resourceChangesAreCheckedAgainBeforeAnyInstallerMutation() = runTest {
        val client = Client()
        val release = Release()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val inspection = async { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), "fixture".toCharArray(), requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) }
        runCurrent()
        val proposal = inspection.await()
        client.availableFlashKiB = 1
        val install = async { runCatching { service.install(proposal, licenseAccepted = true) } }
        runCurrent()
        assertEquals(RouterWizardError.STORAGE, (install.await().exceptionOrNull() as RouterSetupFailure).kind)
        assertFalse(client.installed)
        assertTrue(client.closed.get())
    }

    @Test fun missingLocalNetworkPermissionPreventsSshOrReleaseRequests() = runTest {
        val release = Release()
        var opened = false
        val service = WifiRouterSetupService(release, { _, _ -> opened = true; Client() }, { throw RouterLocalNetworkPermissionRequired() },
            StandardTestDispatcher(testScheduler))
        val failure = runCatching { service.inspectSshIdentity("192.168.1.1") }.exceptionOrNull()
        assertTrue(failure is RouterLocalNetworkPermissionRequired)
        assertFalse(opened || release.initialized)
    }

    @Test fun unsupportedFirmwareIsReportedBeforeRequestingItsAbsentApkTools() = runTest {
        val client = Client().apply { boardVersion = "24.10.6" }
        val release = Release()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val inspection = async { runCatching { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), "fixture".toCharArray(), requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) } }
        runCurrent()
        assertEquals(RouterWizardError.UNSUPPORTED, (inspection.await().exceptionOrNull() as RouterSetupFailure).kind)
        assertTrue(client.closed.get())
        assertFalse(client.kernelQueried)
    }

    @Test fun successfulInstallRequiresReadbackAndCertificateBeforeReturningAFreshBootstrap() = runTest {
        val client = Client()
        val release = Release()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val inspection = async { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), "fixture".toCharArray(), requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) }
        runCurrent()
        val proposal = inspection.await()
        client.packageReceipt = receipt(proposal.binding)
        val installation = async { service.install(proposal, licenseAccepted = true) }
        runCurrent()
        val result = installation.await()
        assertTrue(client.installed && client.closed.get() && client.enrollmentRequested)
        assertEquals("192.168.1.1", result.identity.endpoint.address)
        val pin = result.takeBootstrapCredential()
        assertArrayEquals("1943".toCharArray(), pin)
        pin?.fill('\u0000')
        result.close()
        proposal.close()
    }

    @Test fun mismatchedInstalledReceiptCannotLeadToBootstrapOrPairing() = runTest {
        val client = Client()
        val release = Release()
        val service = WifiRouterSetupService(release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val inspection = async { service.inspect("192.168.1.1", byteArrayOf(1, 2, 3), "fixture".toCharArray(), requireNotNull(release.catalog.defaultSelection), licenseAccepted = true) }
        runCurrent()
        val proposal = inspection.await()
        client.packageReceipt = receipt(proposal.binding.copy(coreBinarySha256 = "f".repeat(64)))
        val failure = async { runCatching { service.install(proposal, licenseAccepted = true) } }
        runCurrent()
        assertTrue(failure.await().isFailure && client.closed.get())
        assertFalse(client.enrollmentRequested)
    }

    private fun receipt(binding: RouterInstallBinding): String = buildJsonObject {
        put("schema", 1); put("variantId", binding.variantId); put("architecture", binding.architecture)
        put("catalogSha256", binding.selection.catalogSha256); put("mode", binding.storageMode.name.lowercase())
        put("coreProducerSha256", binding.coreProducerSha256); put("coreSourceSha256", binding.coreSourceSha256)
        put("coreBinarySha256", binding.coreBinarySha256); put("capabilitiesSha256", binding.capabilitiesSha256)
        put("supportedTargets", JsonArray(listOf(JsonPrimitive("x86/64"))))
        put("supportedOpenWrt", JsonArray(listOf(JsonPrimitive("25.12.5"))))
        put("protocols", JsonArray(listOf(JsonPrimitive("hysteria2"))))
        put("packages", JsonArray(listOf(buildJsonObject {
            put("name", "foxcore-cli"); put("version", "1.0.0"); put("file", "core.apk"); put("sha256", "a".repeat(64))
        })))
    }.toString()
}
