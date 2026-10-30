package com.foxhole.guard.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThreatIntelDiskStateTest {
    @Test
    fun `legacy bytes and generation survive a new atomic commit and reopen`() = runBlocking {
        withDirectory { directory ->
            val old = "{\n  \"schema\":4,\"packages\":[\"old\"]\n}\n".toByteArray()
            val fresh = "{\n \"schema\":4, \"packages\":[\"fresh\"]\n}\n".toByteArray()
            val legacy = File(directory, "threat-intel-remote.json").apply { writeBytes(old) }
            val stamp = File(directory, "threat-intel-remote.generated-at").apply { writeText(T0) }
            val disk = ThreatIntelDiskState(directory)
            assertArrayEquals(old, disk.read()!!.bytes)
            assertEquals(T0, disk.generation())
            disk.install(fresh, T1)
            val reopened = ThreatIntelDiskState(directory)
            assertArrayEquals(fresh, reopened.read()!!.bytes)
            assertEquals(T1, reopened.read()!!.generation)
            assertEquals(T1, reopened.generation())
            assertArrayEquals(old, legacy.readBytes())
            assertEquals(T0, stamp.readText())
        }
    }

    @Test
    fun `two writer instances always leave the newer exact document and generation`() = runBlocking {
        withDirectory { directory ->
            val left = ThreatIntelDiskState(directory)
            val right = ThreatIntelDiskState(directory)
            val older = async { runCatching { left.install("older".toByteArray(), T1) } }
            val newer = async { right.install("newer".toByteArray(), T2) }
            older.await()
            newer.await()
            val reopened = ThreatIntelDiskState(directory)
            assertEquals(T2, reopened.generation())
            assertArrayEquals("newer".toByteArray(), reopened.read()!!.bytes)
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun `failed publication keeps the complete legacy pair usable`() = runBlocking {
        withDirectory { directory ->
            File(directory, "threat-intel-remote.json").writeText("legacy")
            File(directory, "threat-intel-remote.generated-at").writeText(T0)
            File(directory, "threat-intel-committed.bin").mkdir()
            val disk = ThreatIntelDiskState(directory)
            assertTrue(runCatching { disk.install("candidate".toByteArray(), T1) }.isFailure)
            assertEquals(T0, disk.generation())
            assertArrayEquals("legacy".toByteArray(), disk.read()!!.bytes)
            assertTrue(directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun `corrupt committed record cannot downgrade to a stale legacy generation`() = runBlocking {
        withDirectory { directory ->
            File(directory, "threat-intel-remote.json").writeText("legacy")
            File(directory, "threat-intel-remote.generated-at").writeText(T0)
            val disk = ThreatIntelDiskState(directory)
            val committed = File(disk.install("fresh".toByteArray(), T2))
            committed.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(runCatching { disk.read() }.isFailure)
            assertTrue(runCatching { disk.install("older".toByteArray(), T1) }.isFailure)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), committed.readBytes())
        }
    }

    @Test
    fun `payload digest corruption is rejected on cold read`() = runBlocking {
        withDirectory { directory ->
            val disk = ThreatIntelDiskState(directory)
            val committed = File(disk.install("payload".toByteArray(), T1))
            val bytes = committed.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            committed.writeBytes(bytes)
            assertTrue(runCatching { ThreatIntelDiskState(directory).read() }.isFailure)
            assertEquals(T1, ThreatIntelDiskState(directory).generation())
        }
    }

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("sentinel-atomic-state").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private companion object {
        const val T0 = "2026-10-02T00:00:00Z"
        const val T1 = "2026-10-02T01:00:00Z"
        const val T2 = "2026-10-02T02:00:00Z"
    }
}
