package com.foxhole.beta.ui

import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.vpn.DnsFilterUpdateStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
