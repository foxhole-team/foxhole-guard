package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsFilterStatusAgeTest {
    @Test
    fun `dns filter status keeps minute labels from 1 to 59`() {
        val now = 60L * 60L * 1000L

        assertEquals(DnsFilterStatusAge.Minutes(1), resolveDnsFilterStatusAge(now - 1L, now))
        assertEquals(DnsFilterStatusAge.Minutes(59), resolveDnsFilterStatusAge(now - 59L * 60L * 1000L, now))
    }

    @Test
    fun `dns filter status clamps hours to first four hours`() {
        val now = 8L * 60L * 60L * 1000L

        assertEquals(DnsFilterStatusAge.Hours(1), resolveDnsFilterStatusAge(now - 60L * 60L * 1000L, now))
        assertEquals(DnsFilterStatusAge.Hours(4), resolveDnsFilterStatusAge(now - 5L * 60L * 60L * 1000L, now))
    }

    @Test
    fun `dns filter status clamps days to first six days`() {
        val now = 10L * 24L * 60L * 60L * 1000L

        assertEquals(DnsFilterStatusAge.Days(1), resolveDnsFilterStatusAge(now - 24L * 60L * 60L * 1000L, now))
        assertEquals(DnsFilterStatusAge.Days(6), resolveDnsFilterStatusAge(now - 8L * 24L * 60L * 60L * 1000L, now))
    }
}
