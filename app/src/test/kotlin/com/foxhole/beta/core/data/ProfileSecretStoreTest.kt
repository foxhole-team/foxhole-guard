package com.foxhole.beta.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileSecretStoreTest {
    @Test
    fun `rejects secret refs that cannot map to one local secret file`() {
        val directory = Files.createTempDirectory("foxhole-profile-secret-ref").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            profileSecretFileFor(directory, "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            profileSecretFileFor(directory, "not-a-uuid")
        }
    }

    @Test
    fun `maps uuid secret refs inside profile secret directory`() {
        val directory = Files.createTempDirectory("foxhole-profile-secret-ref").toFile()
        val profileRefUnderTest = "123e4567-e89b-12d3-a456-426614174000"

        val file = profileSecretFileFor(directory, profileRefUnderTest)

        assertEquals(directory.canonicalFile, requireNotNull(file.parentFile).canonicalFile)
        assertEquals("$profileRefUnderTest.json", file.name)
    }

    @Test
    fun `orphan cleanup removes inactive secret files only`() {
        val directory = Files.createTempDirectory("foxhole-profile-secret-cleanup").toFile()
        val activeRef = "123e4567-e89b-12d3-a456-426614174000"
        val orphanRef = "223e4567-e89b-12d3-a456-426614174000"
        val invalidRef = "legacy-secret"
        val activeFile = directory.resolve("$activeRef.json").apply { writeText("active") }
        val orphanFile = directory.resolve("$orphanRef.json").apply { writeText("orphan") }
        val invalidFile = directory.resolve("$invalidRef.json").apply { writeText("invalid") }

        val deleted = deleteOrphanProfileSecretFiles(directory, setOf(activeRef))

        assertEquals(2, deleted)
        assertTrue(activeFile.exists())
        assertFalse(orphanFile.exists())
        assertFalse(invalidFile.exists())
    }
}
