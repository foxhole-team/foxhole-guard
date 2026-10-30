package com.foxhole.guard.router

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCompactReadbackBoundsTest {
    @Test fun complete512PackageReadbackAboveTheOldLimitIsAdmittedWithoutChangingItsBytes() = runTest {
        val fixture = RouterCompactBootstrapFixtures(ids = listOf("vless"), withServer = false)
        fixture.release.initialize()
        val selection = fixture.selection(RouterInstallProtocol.VLESS)
        val plan = requireNotNull(fixture.release.payload(fixture.system, selection).compact).modes.getValue(RouterStorageMode.FLASH)
        val binding = RouterInstallBinding(selection, plan.binding.platform.id, "x86_64", RouterStorageMode.FLASH,
            null, null, null, null, plan.binding.budgetSha256, "x86/64", plan.binding)
        // Dependencies stand for the previously authenticated full closure. This tests transport/readback,
        // not publisher signatures or a synthetic installation.
        val packages = plan.packages + List(512 - plan.packages.size) { index -> RouterPackageIdentity(
            "dependency-${index.toString().padStart(4, '0')}-" + "n".repeat(111),
            "1." + "0".repeat(158), "p${index.toString().padStart(4, '0')}" + "f".repeat(151) + ".apk", "a".repeat(64),
        ) }
        val bytes = fixture.readback(binding, packages)
        assertTrue(bytes.size in 262145..524288)
        val output = BoundedRouterOutput(RouterReadCommand.PACKAGE_MANIFEST.maximumOutputBytes)
        for (offset in bytes.indices step 4096) output.write(bytes, offset, minOf(4096, bytes.size - offset))
        assertTrue(bytes.contentEquals(output.bytes()))
        verifyRouterInstalledPackage(output.bytes(), binding, fixture.system, packages)
    }

    @Test fun packageReadbackRejectsTheFirstByteBeyondItsOwn524288ByteBudget() {
        val output = BoundedRouterOutput(RouterReadCommand.PACKAGE_MANIFEST.maximumOutputBytes)
        output.write(ByteArray(524288))
        assertEquals(524288, output.bytes().size)
        assertThrows(IOException::class.java) { output.write(1) }
        assertTrue(output.overflowed)
        assertEquals(524288, output.bytes().size)
    }

    @Test fun otherReadCommandsStillRejectA262145ByteResponse() {
        for (command in RouterReadCommand.entries.filter { it != RouterReadCommand.PACKAGE_MANIFEST }) {
            val output = BoundedRouterOutput(command.maximumOutputBytes)
            output.write(ByteArray(262144))
            assertThrows(IOException::class.java) { output.write(byteArrayOf(1), 0, 1) }
            assertTrue(output.overflowed)
            assertEquals(262144, output.bytes().size)
        }
    }
}
