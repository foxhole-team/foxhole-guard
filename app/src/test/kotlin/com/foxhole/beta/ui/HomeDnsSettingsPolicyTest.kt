package com.foxhole.beta.ui

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
}
