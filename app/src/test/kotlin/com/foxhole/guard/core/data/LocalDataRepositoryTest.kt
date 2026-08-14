package com.foxhole.guard.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalDataRepositoryTest {
    @Test
    fun `delete local data targets removes files and nested directories once`() {
        val directory = Files.createTempDirectory("foxhole-local-data").toFile()
        val file = directory.resolve("single.txt").apply { writeText("one") }
        val nested = directory.resolve("nested").apply { mkdirs() }
        val child = nested.resolve("child.txt").apply { writeText("two") }

        val deleted = deleteLocalDataTargets(listOf(file, nested, nested))

        assertEquals(3, deleted)
        assertFalse(file.exists())
        assertFalse(child.exists())
        assertFalse(nested.exists())
    }

    @Test
    fun `database files include secure database sidecars`() {
        val root = Files.createTempDirectory("foxhole-db-targets").toFile()

        val files = databaseFiles(root.resolve("foxhole.secure.db")).map(File::getName)

        assertEquals(
            listOf(
                "foxhole.secure.db",
                "foxhole.secure.db-wal",
                "foxhole.secure.db-shm",
                "foxhole.secure.db-journal",
            ),
            files,
        )
    }

    @Test
    fun `factory reset contract covers sensitive local targets`() {
        val source =
            listOf(
                File("src/main/kotlin/com/foxhole/guard/core/data/LocalDataRepository.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/core/data/LocalDataRepository.kt"),
                File("../app/src/main/kotlin/com/foxhole/guard/core/data/LocalDataRepository.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("PROFILE_SECRETS_DIR_NAME = \"profile-secrets\""))
        assertTrue(source.contains("DIAGNOSTICS_JOURNAL_DIR_NAME = \"diagnostics-journal\""))
        assertTrue(source.contains("DIAGNOSTICS_EXPORT_DIR_NAME = \"diagnostics-export\""))
        assertTrue(source.contains("DNS_RULE_SETS_DIR_NAME = \"dns-rule-sets\""))
        assertTrue(source.contains("PROFILE_DB_PASSPHRASE_PATH = \"keys/profile-db.passphrase\""))
        assertTrue(source.contains("PROFILE_SECURE_DB_NAME = \"foxhole.secure.db\""))
        assertTrue(source.contains("LEGACY_PROFILE_DB_NAME = \"foxhole.db\""))
        assertTrue(source.contains("database.clearAllTables()"))
        assertTrue(source.contains("runtimeFactoryResetFileTargets(appContext)"))
        assertTrue(source.contains("settingsRepository.resetAllLocalSettings()"))
    }
}
