package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeChildProcessReaperTest {
    private val executablePath = "/data/app/com.foxhole.guard/lib/arm64/liblyrebird.so"
    private val dataDirectoryRoot = "/data/user/0/com.foxhole.guard/files/tor-data"

    @Test
    fun `reaps embedded tor child launched from our executable`() {
        val killed = mutableListOf<Int>()
        val reaper =
            RuntimeChildProcessReaper(
                selfPid = 1000,
                killProcess = { pid -> killed += pid },
                listProcesses = {
                    listOf(
                        RunningProcessInfo(1000, listOf("com.foxhole.guard")),
                        RunningProcessInfo(1234, listOf(executablePath, "-f", "-")),
                        RunningProcessInfo(5678, listOf("/system/bin/app_process64")),
                    )
                },
            )

        val count = reaper.reapOrphans(executablePath, dataDirectoryRoot)

        assertEquals(1, count)
        assertEquals(listOf(1234), killed)
    }

    @Test
    fun `reaps pluggable-transport child rooted in our tor data dir`() {
        val killed = mutableListOf<Int>()
        val reaper =
            RuntimeChildProcessReaper(
                selfPid = 1000,
                killProcess = { pid -> killed += pid },
                listProcesses = {
                    listOf(
                        RunningProcessInfo(
                            2222,
                            listOf("/data/app/lib/arm64/liblyrebird.so", "-state", "$dataDirectoryRoot/arm64/pt_state"),
                        ),
                    )
                },
            )

        assertEquals(1, reaper.reapOrphans(executablePath, dataDirectoryRoot))
        assertEquals(listOf(2222), killed)
    }

    @Test
    fun `reaps every bundled tor transport executable in one process scan`() {
        val conjureExecutablePath = "/data/app/com.foxhole.guard/lib/arm64/libconjure_client.so"
        val killed = mutableListOf<Int>()
        var scans = 0
        val reaper =
            RuntimeChildProcessReaper(
                selfPid = 1000,
                killProcess = { pid -> killed += pid },
                listProcesses = {
                    scans += 1
                    listOf(
                        RunningProcessInfo(1234, listOf(executablePath, "-f", "-")),
                        RunningProcessInfo(5678, listOf(conjureExecutablePath, "-registerURL", "https://example.test")),
                        RunningProcessInfo(9012, listOf("/system/bin/tor")),
                    )
                },
            )

        val count =
            reaper.reapOrphans(
                executablePaths = setOf(executablePath, conjureExecutablePath),
                dataDirectoryRoot = dataDirectoryRoot,
            )

        assertEquals(2, count)
        assertEquals(listOf(1234, 5678), killed)
        assertEquals(1, scans)
    }

    @Test
    fun `never reaps our own pid even when argv matches`() {
        val killed = mutableListOf<Int>()
        val reaper =
            RuntimeChildProcessReaper(
                selfPid = 1234,
                killProcess = { pid -> killed += pid },
                listProcesses = { listOf(RunningProcessInfo(1234, listOf(executablePath))) },
            )

        assertEquals(0, reaper.reapOrphans(executablePath, dataDirectoryRoot))
        assertTrue(killed.isEmpty())
    }

    @Test
    fun `matches only our own tor lineage, not a foreign tor binary`() {
        assertTrue(RuntimeChildProcessReaper.matches(listOf(executablePath), executablePath, dataDirectoryRoot))
        assertFalse(RuntimeChildProcessReaper.matches(listOf("/system/bin/tor"), executablePath, dataDirectoryRoot))
        assertFalse(RuntimeChildProcessReaper.matches(emptyList(), executablePath, dataDirectoryRoot))
    }

    @Test
    fun `a listing failure is swallowed and reaps nothing`() {
        val reaper =
            RuntimeChildProcessReaper(
                selfPid = 1000,
                killProcess = { error("must not be called") },
                listProcesses = { error("proc scan blew up") },
            )

        assertEquals(0, reaper.reapOrphans(executablePath, dataDirectoryRoot))
    }
}
