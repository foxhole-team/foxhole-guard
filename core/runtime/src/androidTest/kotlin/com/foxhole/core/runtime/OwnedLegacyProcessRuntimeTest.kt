package com.foxhole.core.runtime

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OwnedLegacyProcessRuntimeTest {
    @Test
    fun verifiedOwnedForceTerminatesARealSigtermResistantChild() {
        var identity: OwnedProcessIdentity? = null
        val signals = mutableListOf<Int>()
        val platform = object : LegacyProcessPlatform by AndroidLegacyProcessPlatform {
            override fun identity(pid: Int): OwnedProcessIdentity? =
                AndroidLegacyProcessPlatform.identity(pid).also { identity = it }
            override fun kill(pid: Int) {
                signals += pid
                AndroidLegacyProcessPlatform.kill(pid)
            }
        }
        // The original target itself is a shell blocked in read; it creates no child processes.
        val target = listOf("/system/bin/sh", "-c", "trap '' TERM; printf 'TARGET_READY\\n'; IFS= read -r value")
        val owned = startRuntimeProcess(ProcessBuilder(target).redirectErrorStream(true),
            RuntimeProcessLaunchControl(RuntimeLaunchDeadline(1500), {}, { it() }, {}), 24, platform) as OwnedLegacyProcess
        var failure: Throwable? = null
        try {
            val marker = StringBuilder()
            val deadline = RuntimeLaunchDeadline(1500)
            while (!marker.endsWith("\n")) {
                deadline.requireRemaining()
                if (owned.inputStream.available() > 0) {
                    val value = owned.inputStream.read()
                    check(value >= 0 && marker.length < 32)
                    marker.append(value.toChar())
                } else {
                    assertTrue(owned.isAliveCompat(24))
                    Thread.sleep(2)
                }
            }
            assertEquals("TARGET_READY\n", marker.toString())
            val receipt = requireNotNull(identity)
            val current = requireNotNull(AndroidLegacyProcessPlatform.identity(receipt.pid))
            assertTrue(receipt.sameOwner(current))
            assertEquals(Process.myUid(), current.uid)
            assertEquals(Process.myPid(), current.parentPid)
            Process.sendSignal(current.pid, 15)
            Thread.sleep(20)
            val afterTerm = requireNotNull(AndroidLegacyProcessPlatform.identity(current.pid))
            assertTrue(current.sameOwner(afterTerm))
            assertFalse(afterTerm.exited)
            assertTrue(owned.isAliveCompat(24))
            owned.destroyForciblyCompat(24)
            assertTrue(owned.waitForCompat(1500, TimeUnit.MILLISECONDS, 24))
            assertEquals(listOf(current.pid), signals)
            assertFalse(owned.isAliveCompat(24))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (owned.isAliveCompat(24)) owned.destroyForciblyCompat(24)
                check(owned.waitForCompat(1500, TimeUnit.MILLISECONDS, 24)) { "owned runtime child cleanup is pending" }
                owned.closeStreams()
            } catch (cleanup: Throwable) {
                LegacyProcessRecovery.retain(owned)
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }
}
