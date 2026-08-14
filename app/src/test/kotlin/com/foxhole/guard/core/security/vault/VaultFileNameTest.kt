package com.foxhole.guard.core.security.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Имена файлов vault: обратимый base64url без паддинга, безопасный для ФС при любых ключах. */
internal class VaultFileNameTest {
    @Test
    fun `round trip survives arbitrary keys`() {
        listOf("webapp/1", "путь/с юникодом", "a..b//c", "x".repeat(120)).forEach { key ->
            val encoded = encodeVaultFileName(key)
            assertTrue(encoded.none { char -> char == '/' || char == '\\' })
            assertEquals(key, decodeVaultFileName(encoded))
        }
    }

    @Test
    fun `garbage file names decode to null`() {
        assertNull(decodeVaultFileName("не base64 !!!"))
    }
}
