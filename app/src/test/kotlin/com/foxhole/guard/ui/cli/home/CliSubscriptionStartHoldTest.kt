package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.ProfileSourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliSubscriptionStartHoldTest {
    @Test
    fun `hold refresh is available only for idle subscription start`() {
        listOf(CliConnectMode.VPN, CliConnectMode.VPN_TOR).forEach { mode ->
            assertTrue(
                canRefreshSubscriptionOnStartHold(
                    actionTone = CliMainActionTone.START,
                    connected = false,
                    busy = false,
                    subscriptionRefreshInProgress = false,
                    mode = mode,
                    profileSourceType = ProfileSourceType.SUBSCRIPTION_URL,
                ),
            )
        }
    }

    @Test
    fun `hold refresh is absent for tor plain profile live busy or repeated refresh`() {
        val valid =
            HoldCase(
                actionTone = CliMainActionTone.START,
                connected = false,
                busy = false,
                refreshing = false,
                mode = CliConnectMode.VPN,
                source = ProfileSourceType.SUBSCRIPTION_URL,
            )
        listOf(
            valid.copy(actionTone = CliMainActionTone.STOP),
            valid.copy(connected = true),
            valid.copy(busy = true),
            valid.copy(refreshing = true),
            valid.copy(mode = CliConnectMode.TOR),
            valid.copy(source = ProfileSourceType.RAW_CONFIG_JSON),
            valid.copy(source = null),
        ).forEach { case ->
            assertFalse(
                canRefreshSubscriptionOnStartHold(
                    actionTone = case.actionTone,
                    connected = case.connected,
                    busy = case.busy,
                    subscriptionRefreshInProgress = case.refreshing,
                    mode = case.mode,
                    profileSourceType = case.source,
                ),
            )
        }
    }

    private data class HoldCase(
        val actionTone: CliMainActionTone,
        val connected: Boolean,
        val busy: Boolean,
        val refreshing: Boolean,
        val mode: CliConnectMode,
        val source: ProfileSourceType?,
    )
}
