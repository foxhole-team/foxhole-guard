package com.foxhole.guard.ui

import com.foxhole.core.runtime.network.IpInfoFetchMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicDnsIdentityPolicyTest {
    @Test
    fun `public resolver refresh follows primary identity checks only`() {
        assertTrue(shouldRefreshPublicDnsIdentity(IpInfoFetchMode.FULL, IpInfoRefreshReason.MANUAL))
        assertTrue(shouldRefreshPublicDnsIdentity(IpInfoFetchMode.ENTRY_QUICK, IpInfoRefreshReason.POST_CONNECT))
        assertFalse(shouldRefreshPublicDnsIdentity(IpInfoFetchMode.GEO_ENRICHMENT, IpInfoRefreshReason.POST_UPDATE))
        assertFalse(shouldRefreshPublicDnsIdentity(IpInfoFetchMode.FULL, IpInfoRefreshReason.TOR_ROUTE))
    }
}
