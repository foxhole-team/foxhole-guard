package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibboxDnsRuntimeStatsTrackerTest {
    @Test
    fun `runtime endpoint parser splits ipv4 host and port`() {
        val endpoint = "149.154.167.41:443".toRuntimeEndpoint()

        assertEquals("149.154.167.41", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun `runtime endpoint parser keeps bracketed ipv6 readable`() {
        val endpoint = "[2a00:1450:4010:c0f::65]:443".toRuntimeEndpoint()

        assertEquals("2a00:1450:4010:c0f::65", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun `runtime endpoint parser keeps raw ipv6 without treating suffix as port`() {
        val endpoint = "2a00:1450:4010:c0f::65".toRuntimeEndpoint()

        assertEquals("2a00:1450:4010:c0f::65", endpoint.host)
        assertNull(endpoint.port)
    }
}
