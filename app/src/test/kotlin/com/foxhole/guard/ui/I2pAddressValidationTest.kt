package com.foxhole.guard.ui

import com.foxhole.guard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class I2pAddressValidationTest {
    @Test
    fun `normalization lowercases, trims and appends the i2p suffix`() {
        assertEquals("example.i2p", normalizedI2pHostInput(" Example.i2p "))
        assertEquals("example.i2p", normalizedI2pHostInput("example"))
        assertEquals("sub.example.i2p", normalizedI2pHostInput("sub.example"))
        assertNull(normalizedI2pHostInput("   "))
    }

    @Test
    fun `valid hosts pass`() {
        assertNull(i2pHostValidationErrorRes("example.i2p"))
        assertNull(i2pHostValidationErrorRes("example"))
        assertNull(i2pHostValidationErrorRes("sub.example.i2p"))
        assertNull(i2pHostValidationErrorRes("a-1.i2p"))
    }

    @Test
    fun `b32 names get their dedicated error`() {
        val b32 = "a".repeat(52)
        assertEquals(
            R.string.i2p_address_host_b32_not_needed,
            i2pHostValidationErrorRes("$b32.b32.i2p"),
        )
    }

    @Test
    fun `malformed hosts are rejected`() {
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes(""))
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes("-bad.i2p"))
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes("bad-.i2p"))
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes("под.i2p"))
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes("has space.i2p"))
        assertEquals(R.string.i2p_address_host_invalid, i2pHostValidationErrorRes("${"a".repeat(64)}.i2p"))
    }

    @Test
    fun `destination length bounds are enforced`() {
        assertNull(i2pDestinationValidationErrorRes("A".repeat(516)))
        assertNull(i2pDestinationValidationErrorRes("A".repeat(616)))
        assertEquals(R.string.i2p_address_destination_invalid, i2pDestinationValidationErrorRes("A".repeat(515)))
        assertEquals(R.string.i2p_address_destination_invalid, i2pDestinationValidationErrorRes("A".repeat(617)))
    }

    @Test
    fun `destination charset accepts i2p base64 and rejects standard base64 extras`() {
        val body = "Ab0".repeat(171) // 513 chars
        assertNull(i2pDestinationValidationErrorRes("$body-~="))
        assertEquals(R.string.i2p_address_destination_invalid, i2pDestinationValidationErrorRes("$body+/="))
        assertEquals(R.string.i2p_address_destination_invalid, i2pDestinationValidationErrorRes("$body a="))
    }
}
