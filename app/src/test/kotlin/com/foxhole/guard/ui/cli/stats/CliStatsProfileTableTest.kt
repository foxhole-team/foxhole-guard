package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.ProfileTrafficUiItem
import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Test

class CliStatsProfileTableTest {
    @Test
    fun `smart profile options aggregate into one profile row`() {
        val rows = cliStatsProfileRows(
            listOf(
                traffic(profileId = 7L, name = "Smart old", option = "vless", rx = 10L, tx = 20L, at = 1L),
                traffic(profileId = 7L, name = "Smart", option = "wg", rx = 30L, tx = 40L, at = 2L),
                traffic(profileId = 9L, name = "Single", option = null, rx = 1L, tx = 2L, at = 3L),
            ),
        )

        assertEquals(listOf(7L, 9L), rows.map(CliStatsProfileRow::profileId))
        assertEquals("Smart", rows.first().profileName)
        assertEquals(40L, rows.first().rxBytes)
        assertEquals(60L, rows.first().txBytes)
        assertEquals(100L, rows.first().totalBytes)
    }

    private fun traffic(
        profileId: Long,
        name: String,
        option: String?,
        rx: Long,
        tx: Long,
        at: Long,
    ): ProfileTrafficUiItem =
        ProfileTrafficUiItem(
            profileId = profileId,
            profileName = name,
            protocolHint = ProtocolHint.VLESS,
            protocolOptionId = option,
            rxBytes = rx,
            txBytes = tx,
            updatedAt = at,
        )
}
