package com.foxhole.guard.ui

import com.foxhole.core.model.SecureDnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsResolverPresetAvailabilityTest {
    @Test fun newQuicModesHaveNoUnverifiedProviderOrDefaultHost() {
        for (type in listOf("quic", "h3")) {
            val mode = SecureDnsMode.entries.single { it.configType == type }
            assertNull(defaultDnsServerFor(mode))
            for (preset in DnsResolverPreset.entries.filter { it != DnsResolverPreset.CUSTOM }) {
                assertThrows(IllegalStateException::class.java) { preset.hostFor(mode) }
            }
        }
    }

    @Test fun existingProviderDefaultsRemainUnchanged() {
        assertEquals("1.1.1.1", defaultDnsServerFor(SecureDnsMode.PLAIN))
        assertEquals("cloudflare-dns.com", defaultDnsServerFor(SecureDnsMode.DOH))
        assertEquals("one.one.one.one", defaultDnsServerFor(SecureDnsMode.DOT))
    }
}
