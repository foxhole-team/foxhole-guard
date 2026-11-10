package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class RuntimeProcessCompatibilityTest {
    @Test
    fun legacyLivenessUsesOnlyTheOwnedProcessesExitValue() {
        val process = ApiRecordingProcess()
        assertTrue(process.isAliveCompat(24))
        process.alive = false
        assertFalse(process.isAliveCompat(25))
        assertEquals(0, process.modernAliveCalls)
    }

    @Test
    fun legacyWaitRetainsItsMonotonicBudgetWithoutModernApiCalls() {
        val process = ApiRecordingProcess()
        var now = 0L
        val sleeps = mutableListOf<Long>()
        assertFalse(process.waitForCompat(15, TimeUnit.MILLISECONDS, 24,
            nanoTime = { now }, sleepNanos = { sleeps += it; now += it }))
        assertEquals(listOf(10_000_000L, 5_000_000L), sleeps)
        assertEquals(15_000_000L, now)
        assertTrue(process.alive)
        assertEquals(0, process.modernWaitCalls)
    }

    @Test
    fun legacyWaitRecognizesExitAndNeverKillsAChildOnTimeout() {
        val process = ApiRecordingProcess()
        var now = 0L
        assertTrue(process.waitForCompat(50, TimeUnit.MILLISECONDS, 25,
            nanoTime = { now }, sleepNanos = { now += it; process.alive = false }))
        assertEquals(0, process.destroyCalls)
        process.alive = true
        assertFalse(process.waitForCompat(0, TimeUnit.MILLISECONDS, 24))
        assertEquals(0, process.destroyCalls)
    }

    @Test
    fun legacyWaitPropagatesInterruptionWithoutPretendingTheChildExited() {
        val process = ApiRecordingProcess()
        val interrupt = InterruptedException("cancel owned wait")
        val actual = runCatching {
            process.waitForCompat(1, TimeUnit.SECONDS, 24, nanoTime = { 0 }, sleepNanos = { throw interrupt })
        }.exceptionOrNull()
        assertSame(interrupt, actual)
        assertTrue(process.alive)
        assertEquals(0, process.destroyCalls)
    }

    @Test
    fun modernProcessMethodsAndTimeoutArgumentsArePreserved() {
        val process = ApiRecordingProcess()
        assertTrue(process.isAliveCompat(26))
        assertTrue(process.waitForCompat(17, TimeUnit.SECONDS, 37))
        assertEquals(1, process.modernAliveCalls)
        assertEquals(1, process.modernWaitCalls)
        assertEquals(17L to TimeUnit.SECONDS, process.lastWait)
    }

    @Test
    fun modernForceDelegatesToTheRealForceMethod() {
        val process = ApiRecordingProcess()
        assertSame(process, process.destroyForciblyCompat(26))
        assertEquals(1, process.modernForceCalls)
        assertEquals(0, process.destroyCalls)
        assertFalse(process.alive)
    }

    private class ApiRecordingProcess : Process() {
        var alive = true
        var modernAliveCalls = 0
        var modernWaitCalls = 0
        var destroyCalls = 0
        var modernForceCalls = 0
        var lastWait: Pair<Long, TimeUnit>? = null
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("unbounded wait must not be used")
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException("still running") else 0
        override fun destroy() { destroyCalls++; alive = false }
        override fun destroyForcibly(): Process { modernForceCalls++; alive = false; return this }
        override fun isAlive(): Boolean { modernAliveCalls++; return alive }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            modernWaitCalls++
            lastWait = timeout to unit
            return true
        }
    }
}
