package com.foxhole.beta.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ProfileImportPayloadLimitsTest {
    @Test
    fun `local import stream rejects payloads beyond cap`() {
        val payload = ByteArray((MAX_LOCAL_PROFILE_IMPORT_BYTES + 1L).toInt()) { 'a'.code.toByte() }

        assertThrows(ProfileImportPayloadTooLargeException::class.java) {
            ByteArrayInputStream(payload).readLocalProfileImportUtf8Capped()
        }
    }

    @Test
    fun `local import string limit counts utf8 bytes`() {
        val payload = "a".repeat(MAX_LOCAL_PROFILE_IMPORT_BYTES.toInt() - 1) + "Ж"

        assertThrows(ProfileImportPayloadTooLargeException::class.java) {
            requireLocalProfileImportWithinLimit(payload)
        }
    }

    @Test
    fun `local import string within cap is returned unchanged`() {
        val payload = "vless://example"

        assertSame(payload, requireLocalProfileImportWithinLimit(payload))
        assertEquals(payload.length.toLong(), localProfileImportByteCount(payload))
    }
}
