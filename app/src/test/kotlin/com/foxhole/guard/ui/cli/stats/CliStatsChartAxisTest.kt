package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.StatisticsWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal class CliStatsChartAxisTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `the day axis closes on the next whole hour so buckets are clock hours`() {
        val axis = cliStatsChartAxis(StatisticsWindow.DAY, at("2026-08-16T13:37:42Z"), utc)

        assertEquals(at("2026-08-16T14:00:00Z"), axis.endMs)
        assertEquals(HOUR_MS, axis.bucketMs)
        assertEquals(24, axis.bucketCount)
        assertEquals(at("2026-08-15T14:00:00Z"), axis.startMs)
    }

    @Test
    fun `an instant already on a boundary does not skip a whole bucket forward`() {
        val axis = cliStatsChartAxis(StatisticsWindow.DAY, at("2026-08-16T14:00:00Z"), utc)

        assertEquals(at("2026-08-16T14:00:00Z"), axis.endMs)
    }

    @Test
    fun `week and month axes close on local midnight and step whole days`() {
        val week = cliStatsChartAxis(StatisticsWindow.WEEK, at("2026-08-16T13:37:42Z"), utc)
        val month = cliStatsChartAxis(StatisticsWindow.MONTH, at("2026-08-16T13:37:42Z"), utc)

        assertEquals(at("2026-08-17T00:00:00Z"), week.endMs)
        assertEquals(DAY_MS, week.bucketMs)
        assertEquals(7, week.bucketCount)
        assertEquals(at("2026-08-17T00:00:00Z"), month.endMs)
        assertEquals(DAY_MS, month.bucketMs)
        assertEquals(30, month.bucketCount)
    }

    @Test
    fun `day ticks count back from 24 to 1 and print every four hours`() {
        val axis = cliStatsChartAxis(StatisticsWindow.DAY, at("2026-08-16T14:00:00Z"), utc)

        assertEquals(24, axis.ticks.size)
        assertEquals(listOf("24", "23", "22"), axis.ticks.take(3).map(CliStatsAxisTick::label))
        assertEquals("1", axis.ticks.last().label)
        assertEquals(setOf(0, 4, 8, 12, 16, 20, 23), axis.labelledBucketIndexes)
        assertEquals(
            listOf("24", "20", "16", "12", "8", "4", "1"),
            axis.ticks.filter { tick -> tick.bucketIndex in axis.labelledBucketIndexes }
                .map(CliStatsAxisTick::label),
        )
        assertTrue(axis.ticks.all { tick -> tick.bucketIndex in 0 until axis.bucketCount })
    }

    @Test
    fun `week stays 1 through 7 and month counts back from 30 to 1`() {
        val week = cliStatsChartAxis(StatisticsWindow.WEEK, at("2026-08-16T00:00:00Z"), utc)
        val axis = cliStatsChartAxis(StatisticsWindow.MONTH, at("2026-08-16T00:00:00Z"), utc)

        assertEquals((1..7).map(Int::toString), week.ticks.map(CliStatsAxisTick::label))
        assertEquals((0 until 7).toSet(), week.labelledBucketIndexes)
        assertEquals(30, axis.ticks.size)
        assertEquals(listOf("30", "29", "28"), axis.ticks.take(3).map(CliStatsAxisTick::label))
        assertEquals("1", axis.ticks.last().label)
        assertEquals(
            listOf("30", "25", "20", "15", "10", "5", "1"),
            axis.ticks.filter { tick -> tick.bucketIndex in axis.labelledBucketIndexes }
                .map(CliStatsAxisTick::label),
        )
    }

    @Test
    fun `labels are spaced by whole buckets and the newest boundary always prints`() {
        assertEquals(setOf(29, 26, 23, 20, 17, 14, 11, 8, 5, 2), cliStatsLabelledBucketIndexes(30, 3))
        assertEquals((0 until 7).toSet(), cliStatsLabelledBucketIndexes(7, 1))
        assertEquals(setOf(23), cliStatsLabelledBucketIndexes(24, 24))
        assertEquals(setOf(23), cliStatsLabelledBucketIndexes(24, 99))
        assertEquals(emptySet<Int>(), cliStatsLabelledBucketIndexes(0, 3))
    }

    @Test
    fun `every label is centred on the bars it names, on every range`() {
        val widthPx = 900f
        val gapPx = 3f
        StatisticsWindow.entries.forEach { window ->
            val axis = cliStatsChartAxis(window, at("2026-08-16T14:00:00Z"), utc)
            val slotWidth = cliStatsSlotWidthPx(widthPx, axis.bucketCount, gapPx)

            axis.ticks.forEach { tick ->
                val left = cliStatsSlotLeftPx(tick.bucketIndex, widthPx, axis.bucketCount, gapPx)
                val center = cliStatsSlotCenterPx(tick.bucketIndex, widthPx, axis.bucketCount, gapPx)
                assertEquals("$window bucket ${tick.bucketIndex}", left + slotWidth / 2f, center, 0.001f)
                assertTrue("$window label left of its own bars", center > left)
                assertTrue("$window label right of its own bars", center < left + slotWidth)
            }
            val lastRight = cliStatsSlotLeftPx(axis.bucketCount - 1, widthPx, axis.bucketCount, gapPx) + slotWidth
            assertEquals("$window slots must tile the width", widthPx, lastRight, 0.001f)
        }
    }

    @Test
    fun `unrecorded slots are gaps and recorded slots are not`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(sample(at("2026-08-16T13:30:00Z"))),
                windowMs = 2 * HOUR_MS,
                nowMs = at("2026-08-16T14:00:00Z"),
                bucketCount = 2,
            )

        assertEquals(2, buckets.size)
        assertFalse("12:00-13:00 recorded nothing at all", buckets[0].sampled)
        assertTrue(buckets[1].sampled)
    }

    @Test
    fun `a recorded slot carrying no bytes is a measured zero, not a gap`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(sample(at("2026-08-16T13:30:00Z"), bytes = 0L)),
                windowMs = HOUR_MS,
                nowMs = at("2026-08-16T14:00:00Z"),
                bucketCount = 1,
            )

        assertTrue(buckets[0].sampled)
        assertEquals(0L, buckets[0].totalBytes)
    }

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun sample(startedAtMs: Long, bytes: Long = 100L) =
        AppTrafficWindow(
            packageName = "app.test",
            startedAtMs = startedAtMs,
            durationSec = 60,
            rxBytes = bytes,
            txBytes = 0L,
            foreground = null,
            networkType = NetworkType.UNKNOWN,
        )

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
