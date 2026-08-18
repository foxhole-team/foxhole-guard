package com.foxhole.guard.ui

import com.foxhole.core.model.DnsSettings
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDnsSettingsPolicyTest {
    @Test
    fun `dns filter refresh only allows runtime after verified update`() {
        assertTrue(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.UPDATED))
        assertFalse(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.UP_TO_DATE))
        assertFalse(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.SKIPPED))
        assertFalse(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.FAILED))
    }

    @Test
    fun `missing or corrupt verified data fails closed after a successful response`() {
        assertEquals(
            FoxholeUpdatePhase.FAILED,
            dnsFilterVerifiedTerminalPhase(DnsFilterUpdateStatus.UPDATED, verifiedRuleSetReady = false),
        )
        assertEquals(
            FoxholeUpdatePhase.FAILED,
            dnsFilterVerifiedTerminalPhase(DnsFilterUpdateStatus.UP_TO_DATE, verifiedRuleSetReady = false),
        )
        assertEquals(
            FoxholeUpdatePhase.DONE,
            dnsFilterVerifiedTerminalPhase(DnsFilterUpdateStatus.UPDATED, verifiedRuleSetReady = true),
        )
    }

    @Test
    fun `enabling dns rule set filtering requires verified preflight`() {
        assertTrue(
            shouldPreflightDnsRuleSetEnable(
                current = DnsSettings(filteringEnabled = false),
                next = DnsSettings(filteringEnabled = true),
            ),
        )
    }

    @Test
    fun `dns preflight is skipped when rule set filtering remains disabled`() {
        val disabledRules =
            DnsSettings(
                filteringEnabled = true,
                blockAds = false,
                blockTrackers = false,
                blockAppTelemetry = false,
                blockMaliciousDomains = false,
            )

        assertFalse(
            shouldPreflightDnsRuleSetEnable(
                current = DnsSettings(filteringEnabled = false),
                next = disabledRules,
            ),
        )
        assertFalse(
            shouldPreflightDnsRuleSetEnable(
                current = DnsSettings(filteringEnabled = true),
                next = DnsSettings(filteringEnabled = false),
            ),
        )
    }

    @Test
    fun `enabling a dns rule category after disabled rules requires preflight`() {
        val disabledRules =
            DnsSettings(
                filteringEnabled = true,
                blockAds = false,
                blockTrackers = false,
                blockAppTelemetry = false,
                blockMaliciousDomains = false,
            )

        assertTrue(
            shouldPreflightDnsRuleSetEnable(
                current = disabledRules,
                next = disabledRules.copy(blockAds = true),
            ),
        )
    }

    @Test
    fun `enabling dns rule set filtering always refreshes the selected data set`() {
        assertTrue(
            shouldPreflightDnsRuleSetEnable(
                current = DnsSettings(filteringEnabled = false, filtersUpdatedAt = 1_000L),
                next = DnsSettings(filteringEnabled = true, filtersUpdatedAt = 1_000L),
            ),
        )
    }

    @Test
    fun `dns refresh status has one terminal phase for screen and terminal`() {
        assertEquals(FoxholeUpdatePhase.DONE, dnsFilterTerminalPhase(DnsFilterUpdateStatus.UPDATED))
        assertEquals(FoxholeUpdatePhase.NO_UPDATE, dnsFilterTerminalPhase(DnsFilterUpdateStatus.UP_TO_DATE))
        assertEquals(FoxholeUpdatePhase.FAILED, dnsFilterTerminalPhase(DnsFilterUpdateStatus.SKIPPED))
        assertEquals(FoxholeUpdatePhase.FAILED, dnsFilterTerminalPhase(DnsFilterUpdateStatus.FAILED))
    }

    @Test
    fun `enabling dns rule set filtering reloads only with an active rule set`() {
        assertTrue(
            shouldReloadRuntimeAfterDnsRuleSetEnable(
                dnsSettings = DnsSettings(filteringEnabled = true),
            ),
        )
        assertFalse(
            shouldReloadRuntimeAfterDnsRuleSetEnable(
                dnsSettings = DnsSettings(filteringEnabled = false),
            ),
        )
    }

    @Test
    fun `dns filter controls collapse into protection group while disabled`() {
        val source = cliSettingsSource("CliSettingsDnsSection.kt")
        val updates = cliSettingsSource("CliUpdatesSubScreen.kt")

        assertTrue(source.contains("if (!dns.filteringEnabled)"))
        assertTrue(source.contains("R.string.cli_cfg_dns_tracker_level"))
        assertTrue(source.contains("dnsTrackerLevelVisible(dns)"))
        assertFalse(source.contains("dns-filter-update"))
        assertTrue(updates.contains("onFoxholeDbRefreshAll"))
    }

    @Test
    fun `dns enable uses the canonical confirmation sheet and blue info marker`() {
        val source = cliSettingsSource("CliSettingsDnsSection.kt")

        assertTrue(source.contains("CliBottomSheet("))
        assertTrue(source.contains("CliSheetActionsRow("))
        assertTrue(source.contains("R.string.cli_dns_filter_enable_body"))
        assertTrue(source.contains("R.drawable.pix_info"))
        assertTrue(source.contains("tint = colors.note"))
        assertTrue(source.contains("R.string.cli_dns_filter_enable_info"))
    }

    @Test
    fun `updates app panel labels version rather than runtime status`() {
        val updates = cliSettingsSource("CliUpdatesSubScreen.kt")

        assertTrue(updates.contains("key = stringResource(R.string.cli_updates_app_version)"))
    }

    private fun cliSettingsSource(fileName: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
        ).first { file -> file.isFile }.readText()
}
