package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.DnsSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliDnsSectionGatingTest {
    @Test
    fun `tracker level shows only with filtering on and trackers blocked`() {
        assertTrue(dnsTrackerLevelVisible(DnsSettings(filteringEnabled = true, blockTrackers = true)))
        assertFalse(dnsTrackerLevelVisible(DnsSettings(filteringEnabled = true, blockTrackers = false)))
        assertFalse(dnsTrackerLevelVisible(DnsSettings(filteringEnabled = false, blockTrackers = true)))
    }

    @Test
    fun `threat level shows only with filtering on and malicious domains blocked`() {
        assertTrue(dnsThreatLevelVisible(DnsSettings(filteringEnabled = true, blockMaliciousDomains = true)))
        assertFalse(dnsThreatLevelVisible(DnsSettings(filteringEnabled = true, blockMaliciousDomains = false)))
        assertFalse(dnsThreatLevelVisible(DnsSettings(filteringEnabled = false, blockMaliciousDomains = true)))
    }

    @Test
    fun `intercept warning shows when filtering is armed but interception is off`() {
        assertTrue(dnsInterceptWarningVisible(DnsSettings(filteringEnabled = true, interceptDnsRequests = false)))
        assertFalse(dnsInterceptWarningVisible(DnsSettings(filteringEnabled = true, interceptDnsRequests = true)))
        assertFalse(dnsInterceptWarningVisible(DnsSettings(filteringEnabled = false, interceptDnsRequests = false)))
    }

    @Test
    fun `leak warning shows only when both resolver paths are off`() {
        assertTrue(dnsLeakWarningVisible(DnsSettings(useVpnProviderDns = false, dnsThroughVpn = false)))
        assertFalse(dnsLeakWarningVisible(DnsSettings(useVpnProviderDns = true, dnsThroughVpn = false)))
        assertFalse(dnsLeakWarningVisible(DnsSettings(useVpnProviderDns = false, dnsThroughVpn = true)))
    }

    @Test
    fun `ipv6 leak warning tracks the replace-system toggle`() {
        assertTrue(dnsReplaceSystemIpv6WarningVisible(DnsSettings(replaceSystemDns = true)))
        assertFalse(dnsReplaceSystemIpv6WarningVisible(DnsSettings(replaceSystemDns = false)))
    }
}
