package com.foxhole.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileStorageTokenMigrationTest {
    @Test
    fun `retired raw source token migrates to the neutral source type`() {
        val retired = retiredRawConfigSourceStorageToken()

        assertEquals(
            listOf(82, 65, 87, 95, 83, 73, 78, 71, 66, 79, 88, 95, 74, 83, 79, 78),
            retired.map(Char::code),
        )
        assertEquals(ProfileSourceType.RAW_CONFIG_JSON, storedProfileSourceType(retired))
        assertEquals(ProfileSourceType.RAW_CONFIG_JSON.name, migrateStoredProfileSourceToken(retired))
    }

    @Test
    fun `retired engine hint migrates to fail closed custom config`() {
        val retired = retiredCustomConfigProtocolStorageToken()

        assertEquals(listOf(83, 73, 78, 71, 95, 66, 79, 88), retired.map(Char::code))
        assertEquals(ProtocolHint.CUSTOM_CONFIG, storedProtocolHint(retired))
        assertEquals(ProtocolHint.CUSTOM_CONFIG.name, migrateStoredProtocolToken(retired))
    }

    @Test
    fun `unknown persisted protocol remains unknown to the parser`() {
        assertNull(storedProtocolHintOrNull("FUTURE_PROTOCOL"))
    }
}
