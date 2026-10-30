package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterSshSystemInspectionTest {
    private val platforms = setOf(RouterPlatform("25.12", "x86/64", "x86_64"), RouterPlatform("24.10", "x86/64", "x86_64"))
    private val board = """{"release":{"distribution":"OpenWrt","version":"25.12.5","target":"x86/64"}}"""
    private val flash = "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/root 10000 2000 8000 20% /overlay\n"
    private val memory = "MemTotal: 500000 kB\nMemAvailable: 400000 kB\n"
    private fun inspect(board: String = this.board, arch: String = "x86_64\n", flash: String = this.flash, memory: String = this.memory) =
        parseRouterSystem(board.toByteArray(), arch.toByteArray(), flash.toByteArray(), memory.toByteArray(), platforms)

    @Test fun readsApkAndOpkgArchitecturesWithoutGuessingPackageSupport() {
        val facts = inspect()
        assertEquals("25.12.5", facts.openWrtVersion)
        assertEquals(RouterResourceFacts(8192000, 409600000), facts.resources)
        assertEquals(facts.resources, inspect(flash = flash.replace("/overlay", "/")).resources)
        val legacy = inspect(board.replace("25.12.5", "24.10.6"), "arch all 1\narch noarch 1\narch x86_64 10\n")
        assertEquals("x86_64", legacy.platform.architecture)
        assertThrows(RouterInspectionFailure::class.java) { inspect(arch = "aarch64_generic") }
        assertThrows(RouterInspectionFailure::class.java) { inspect(arch = "arch x86_64 1\narch unknown 10\n") }
    }

    @Test fun rejectsUnknownFirmwareSeriesPrereleasesAndMisleadingBoardFields() {
        for (changed in listOf(board.replace("25.12.5", "26.01.0"), board.replace("25.12.5", "25.12.5-rc1"),
            board.replace("OpenWrt", "Other"), board.replace("x86/64", "other/target"), board.replace("\"25.12.5\"", "25"))) {
            assertThrows(RouterInspectionFailure::class.java) { inspect(board = changed) }
        }
        assertThrows(RouterInspectionFailure::class.java) { inspect(board = "[".repeat(40) + "0" + "]".repeat(40)) }
        assertThrows(RouterInspectionFailure::class.java) { inspect(board = " ".repeat(16385) + board) }
    }

    @Test fun refusesAmbiguousMissingNegativeAndOverflowedResourceCounters() {
        for (changed in listOf("", memory + "MemAvailable: 1 kB\n", memory.replace("400000", "-1"),
            memory.replace("400000", Long.MAX_VALUE.toString()), memory.replace("kB", "bytes"))) {
            assertThrows(RouterInspectionFailure::class.java) { inspect(memory = changed) }
        }
        for (changed in listOf(flash.replace("8000", "10001"), flash.replace("8000", "-1"),
            flash.replace("/overlay", "/tmp"), flash + "/dev/other 1 0 1 0% /overlay\n")) {
            assertThrows(RouterInspectionFailure::class.java) { inspect(flash = changed) }
        }
    }

    @Test fun filogicArchitectureTokenRequiresVerifiedExplicitMatchingPlatformCoverage() {
        val targetBoard = board.replace("x86/64", "mediatek/filogic").toByteArray()
        val supported = setOf(RouterPlatform("25.12", "mediatek/filogic", "aarch64_cortex-a53"))
        val facts = parseRouterSystem(targetBoard, "aarch64_cortex-a53\n".toByteArray(), flash.toByteArray(), memory.toByteArray(), supported)
        assertEquals(supported.single(), facts.platform)
        assertThrows(RouterInspectionFailure::class.java) {
            parseRouterSystem(targetBoard, "aarch64_cortex-a53\n".toByteArray(), flash.toByteArray(), memory.toByteArray(), platforms)
        }
        assertThrows(RouterInspectionFailure::class.java) {
            parseRouterSystem(board.toByteArray(), "aarch64_cortex-a53\n".toByteArray(), flash.toByteArray(), memory.toByteArray(),
                setOf(RouterPlatform("25.12", "x86/64", "aarch64_cortex-a53")))
        }
    }

}
