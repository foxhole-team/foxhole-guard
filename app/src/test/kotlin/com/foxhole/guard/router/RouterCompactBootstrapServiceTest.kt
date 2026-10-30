package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterCompactBootstrapServiceTest {
    private class Client : RouterSshClient {
        var backup = "4096\n"
        var memoryKiB = 1_000_000L
        var flashKiB = 1_000_000L
        var installCount = 0
        var backupReads = 0
        var command = ""
        var packageReceipt = byteArrayOf()
        var closed = false
        override fun connect(password: CharArray?) = Unit
        override fun read(command: RouterReadCommand): ByteArray = when (command) {
            RouterReadCommand.BOARD -> """{"release":{"distribution":"OpenWrt","version":"25.12.5","target":"x86/64"}}""".toByteArray()
            RouterReadCommand.ARCHITECTURE -> "x86_64\n".toByteArray()
            RouterReadCommand.FLASH -> "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 2000000 0 $flashKiB 0% /overlay\n".toByteArray()
            RouterReadCommand.MEMORY -> "MemAvailable: $memoryKiB kB\n".toByteArray()
            RouterReadCommand.CONFIGURATION -> byteArrayOf()
            RouterReadCommand.KERNEL_PACKAGE -> "kernel-${RouterCompactBootstrapFixtures.KERNEL}\n".toByteArray()
            RouterReadCommand.PACKAGE_MANIFEST -> packageReceipt
            RouterReadCommand.MOBILE_CERTIFICATE -> requireNotNull(javaClass.getResourceAsStream("/router/router-fixture.crt")).use { it.readBytes() }
            else -> error("unexpected read")
        }
        override fun readCompactBackupBytes(): ByteArray { backupReads++; return backup.toByteArray() }
        override fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand) { installCount++; this.command = command.command }
        override fun bootstrapEnrollment() = """{"schema":1,"status":"already_configured","bootstrapPin":null,"expiresInSeconds":0}""".toByteArray()
        override fun close() { closed = true }
    }

    @Test fun secondInspectionCannotSilentlySwitchAConfirmedStorageModeOrOmitTheNewBackup() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val client = Client()
        val service = WifiRouterSetupService(fixture.release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val password = "synthetic".toCharArray()
        val inspection = async { service.inspect("192.168.1.1", byteArrayOf(1), password, fixture.selection(RouterInstallProtocol.VLESS)) }
        runCurrent()
        val proposal = inspection.await()
        assertEquals(RouterStorageMode.FLASH, proposal.storageMode)
        assertArrayEquals(CharArray(password.size), password)
        // Original budget fits, but a larger backup no longer fits the current memory facts.
        client.memoryKiB = 70_000
        client.backup = "67108864\n"
        val install = async { runCatching { service.install(proposal) }.exceptionOrNull() }
        runCurrent()
        val failure = install.await()
        assertTrue(failure is RouterSetupFailure && failure.kind == RouterWizardError.STORAGE)
        assertEquals(0, client.installCount)
        assertEquals(2, client.backupReads)
        assertTrue(client.closed)
    }

    @Test fun unknownBackupRefusesInspectionClosesTheSessionAndWipesThePassword() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val client = Client().apply { backup = "not available" }
        val service = WifiRouterSetupService(fixture.release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val password = "synthetic".toCharArray()
        val inspection = async { runCatching { service.inspect("192.168.1.1", byteArrayOf(1), password,
            fixture.selection(RouterInstallProtocol.VLESS)) }.exceptionOrNull() }
        runCurrent()
        assertTrue(inspection.await() is RouterSetupFailure)
        assertEquals(0, client.installCount)
        assertTrue(client.closed)
        assertArrayEquals(CharArray(password.size), password)
    }

    @Test fun successfulClientOnlyInstallUsesExactConfirmedSlotsAndNoServerFlag() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val client = Client()
        val service = WifiRouterSetupService(fixture.release, { _, _ -> client }, {}, StandardTestDispatcher(testScheduler))
        val selection = fixture.selection(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        val inspection = async { service.inspect("192.168.1.1", byteArrayOf(1), "synthetic".toCharArray(), selection) }
        runCurrent()
        val proposal = inspection.await()
        val plan = requireNotNull(fixture.release.payload(fixture.system, selection).compact).modes.getValue(proposal.storageMode)
        client.packageReceipt = fixture.readback(proposal.binding, plan.packages)
        val installation = async { service.install(proposal) }
        runCurrent()
        installation.await()
        assertEquals(1, client.installCount)
        assertTrue("'--flash'" in client.command && "'--vless'" in client.command && "'--wg'" in client.command)
        assertFalse("'--server'" in client.command || "'--auto'" in client.command || "'--hy2'" in client.command)
        assertTrue(client.closed)
    }
}
