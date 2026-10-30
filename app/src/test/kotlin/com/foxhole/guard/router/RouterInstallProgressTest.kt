package com.foxhole.guard.router

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterInstallProgressTest {
    @Test fun unknownLengthAndNonTransferStagesHaveNoInventedPercentage() {
        assertNull(RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD, 8192).percent)
        assertNull(RouterInstallProgress(RouterInstallProgressStage.INSTALL).percent)
        assertNull(RouterInstallProgress(RouterInstallProgressStage.CHECK).percent)
        assertEquals(40, RouterInstallProgress(RouterInstallProgressStage.UPLOAD, 4, 10).percent)
    }

    @Test fun realInputConsumptionReportsMonotonicCountsWithoutChangingBytes() {
        val bytes = ByteArray(17) { it.toByte() }
        val events = mutableListOf<RouterInstallProgress>()
        val stream = RouterInstallInputStream(ByteArrayInputStream(bytes), bytes.size.toLong(), events::add)
        val first = stream.read()
        val rest = stream.readBytes()
        assertArrayEquals(bytes, byteArrayOf(first.toByte()) + rest)
        val upload = events.filter { it.stage == RouterInstallProgressStage.UPLOAD }
        assertEquals(0L, upload.first().completedBytes)
        assertEquals(17L, upload.last().completedBytes)
        assertTrue(upload.zipWithNext().all { (a, b) -> a.completedBytes <= b.completedBytes })
        assertEquals(100, upload.last().percent)
        assertEquals(RouterInstallProgressStage.INSTALL, events.last().stage)
        assertNull(events.last().percent)
        stream.close()
    }

    @Test fun closeBeforeCompletionCannotReportFullUploadOrInstallerProgress() {
        val events = mutableListOf<RouterInstallProgress>()
        val stream = RouterInstallInputStream(ByteArrayInputStream(ByteArray(8)), 8, events::add)
        stream.read()
        stream.close()
        assertEquals(-1, stream.read())
        assertTrue(events.none { it.percent == 100 || it.stage == RouterInstallProgressStage.INSTALL })
    }

    @Test fun aReadReturningAfterCloseCannotPublishLateMeasuredProgress() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<RouterInstallProgress>()
        val source = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(2, TimeUnit.SECONDS); return 1 }
            override fun close() { release.countDown() }
        }
        val input = RouterInstallInputStream(source, 1, events::add)
        val reader = thread(isDaemon = true) { input.read() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        input.close()
        reader.join(1000)
        assertTrue(!reader.isAlive)
        assertTrue(events.none { it.completedBytes > 0 || it.stage == RouterInstallProgressStage.INSTALL })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCountBeyondTheMeasuredTotalIsRejected() {
        RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD, 11, 10)
    }
}
