package com.foxhole.core.runtime

import com.foxhole.core.model.I2pAddressBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I2pdHostsTest {
    private val destinationA = "A".repeat(516)
    private val destinationB = "B".repeat(516)

    @Test
    fun `formats entries as host=destination, lowercased and sorted`() {
        val lines =
            buildI2pdHostsLines(
                listOf(
                    I2pAddressBookEntry(host = "Zzz.i2p", destination = destinationB),
                    I2pAddressBookEntry(host = " aaa.i2p ", destination = destinationA),
                ),
            )

        assertEquals(
            listOf("aaa.i2p=$destinationA", "zzz.i2p=$destinationB"),
            lines,
        )
    }

    @Test
    fun `empty addressbook renders no lines`() {
        assertTrue(buildI2pdHostsLines(emptyList()).isEmpty())
    }

    @Test
    fun `fingerprint ignores entry order but tracks content`() {
        val first = I2pAddressBookEntry(host = "aaa.i2p", destination = destinationA)
        val second = I2pAddressBookEntry(host = "bbb.i2p", destination = destinationB)

        assertEquals(
            i2pAddressBookFingerprint(listOf(first, second)),
            i2pAddressBookFingerprint(listOf(second, first)),
        )
        assertNotEquals(
            i2pAddressBookFingerprint(listOf(first)),
            i2pAddressBookFingerprint(listOf(first.copy(destination = destinationB))),
        )
        assertNotEquals(
            i2pAddressBookFingerprint(emptyList()),
            i2pAddressBookFingerprint(listOf(first)),
        )
    }
}
