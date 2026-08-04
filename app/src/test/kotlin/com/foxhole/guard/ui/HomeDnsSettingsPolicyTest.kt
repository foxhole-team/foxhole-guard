package com.foxhole.guard.ui

import com.foxhole.core.model.DnsSettings
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeDnsSettingsPolicyTest {
    @Test
    fun `dns filter refresh only allows runtime after verified update`() {
        assertTrue(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.UPDATED))
        assertFalse(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.SKIPPED))
        assertFalse(dnsFilterRefreshAllowsRuntime(DnsFilterUpdateStatus.FAILED))
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
    fun `enabling dns rule set filtering skips preflight after verified refresh`() {
        assertFalse(
            shouldPreflightDnsRuleSetEnable(
                current = DnsSettings(filteringEnabled = false, filtersUpdatedAt = 1_000L),
                next = DnsSettings(filteringEnabled = true, filtersUpdatedAt = 1_000L),
            ),
        )
    }

    @Test
    fun `successful dns refresh reloads active runtime only when rule set filtering is enabled`() {
        assertTrue(
            shouldReloadRuntimeAfterDnsRuleSetRefresh(
                status = DnsFilterUpdateStatus.UPDATED,
                dnsSettings = DnsSettings(filteringEnabled = true),
            ),
        )
        assertFalse(
            shouldReloadRuntimeAfterDnsRuleSetRefresh(
                status = DnsFilterUpdateStatus.SKIPPED,
                dnsSettings = DnsSettings(filteringEnabled = true),
            ),
        )
        assertFalse(
            shouldReloadRuntimeAfterDnsRuleSetRefresh(
                status = DnsFilterUpdateStatus.UPDATED,
                dnsSettings = DnsSettings(filteringEnabled = false),
            ),
        )
    }

    @Test
    fun `dns filter controls collapse into protection group while disabled`() {
        val source = cliSettingsSource("CliSettingsDnsSection.kt")
        val updates = cliSettingsSource("CliUpdatesSubScreen.kt")

        // The per-category controls live below the master toggle and collapse when it is off
        // (the filtering group early-returns on !filteringEnabled), and the tracker-list level is
        // itself gated on the trackers category.
        assertTrue(source.contains("if (!dns.filteringEnabled)"))
        assertTrue(source.contains("R.string.cli_cfg_dns_tracker_level"))
        assertTrue(source.contains("dnsTrackerLevelVisible(dns)"))
        assertFalse(source.contains("dns-filter-update"))
        assertTrue(updates.contains("viewModel::onDnsFilterManualRefresh"))
    }

    private fun cliSettingsSource(fileName: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/$fileName"),
        ).first { file -> file.isFile }.readText()
}
