package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppOption
import org.junit.Assert.assertEquals
import org.junit.Test

class SentinelInstallationsTest {
    @Test
    fun `full inventory retains older records and orders by install time with deterministic ties`() {
        val apps = (1..9).map { app("app.$it", it.toLong()) } + app("app.a", 9L)
        val expected = listOf("app.9", "app.a", "app.8", "app.7", "app.6", "app.5", "app.4", "app.3", "app.2", "app.1")
        assertEquals(expected, sentinelInstallations(apps.reversed()).map { it.packageName })
        assertEquals(expected, sentinelInstallations(apps).map { it.packageName })
        assertEquals(10, sentinelInstallations(apps).size)
    }

    @Test
    fun `statistics preview filters system apps before taking six and ignores update time for ordering`() {
        val users = (1..9).map { app("user.$it", it.toLong()) }
        val system = (1..9).map { app("system.$it", 100L + it, system = true) }
        val oldUpdated = app("old.updated", 1L).copy(lastUpdateTime = 10_000L)
        assertEquals(
            listOf("user.9", "user.8", "user.7", "user.6", "user.5", "user.4"),
            sentinelInstallationPreview(system + oldUpdated + users).map { it.packageName },
        )
    }

    @Test
    fun `unknown install dates remain present after known dates without fabricated timestamps`() {
        val apps = listOf(app("unknown.b", null), app("known", 3L), app("unknown.a", 0L))
        assertEquals(listOf("known", "unknown.a", "unknown.b"), sentinelInstallations(apps).map { it.packageName })
        assertEquals(listOf(3L, 0L, null), sentinelInstallations(apps).map { it.firstInstallTime })
    }

    @Test
    fun `recorded installation and removal history keeps all retained events and uses detection timestamps`() {
        val changes = (1..12).map {
            InstalledAppInventoryChange(
                packageName = "app.$it",
                label = "Owned $it",
                type = if (it % 2 == 0) InstalledAppChangeType.REMOVED else InstalledAppChangeType.INSTALLED,
                detectedAt = it.toLong(),
            )
        }
        assertEquals(12, sentinelInstallationHistory(changes.reversed(), 0L).size)
        assertEquals((12L downTo 4L).toList(), sentinelInstallationHistory(changes, 4L).map { it.detectedAt })
        assertEquals(InstalledAppChangeType.REMOVED, sentinelInstallationHistory(changes, 0L).first().type)
        assertEquals(12, changes.size)
    }

    private fun app(name: String, installed: Long?, system: Boolean = false) =
        InstalledAppOption(name, "Owned $name", system, firstInstallTime = installed)
}
