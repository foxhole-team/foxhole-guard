package com.foxhole.guard.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the migration chain that protects user profiles. [ProfileDatabase] uses
 * `fallbackToDestructiveMigration`, so a missing migration would silently recreate the tables and
 * wipe the user's saved profiles rather than crash. This JVM test fails the build the moment the
 * `@Database` version is bumped without a matching migration, catching that in CI before release.
 *
 * The schema-level correctness of each step (that the migrated schema matches the exported JSON) is
 * verified by the instrumented `ProfileDatabaseSchemaMigrationTest` against the schemas under
 * app/schemas/com.foxhole.guard.core.data.ProfileDatabase/.
 */
class ProfileDatabaseMigrationsTest {
    private val declaredVersion: Int = PROFILE_DATABASE_VERSION

    @Test
    fun `migration chain is contiguous from 1 to the current version`() {
        val steps = ProfileDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        for (from in 1 until declaredVersion) {
            assertTrue(
                "missing Room migration $from -> ${from + 1}; without it a $from-version database is " +
                    "destructively wiped instead of migrated",
                (from to from + 1) in steps,
            )
        }
    }

    @Test
    fun `every migration advances exactly one version and stays within range`() {
        ProfileDatabase.ALL_MIGRATIONS.forEach { migration ->
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} must advance a single version",
                migration.startVersion + 1,
                migration.endVersion,
            )
            assertTrue(
                "migration ${migration.startVersion}->${migration.endVersion} targets beyond the database version",
                migration.endVersion <= declaredVersion,
            )
        }
    }

    @Test
    fun `no duplicate migration steps are registered`() {
        val steps = ProfileDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(
            "duplicate migration steps registered: $steps",
            steps.size,
            steps.toSet().size,
        )
    }

    @Test
    fun `only a newer stored schema counts as a downgrade`() {
        assertTrue(isDowngradedProfileDatabase(declaredVersion + 1, declaredVersion))
        assertFalse(isDowngradedProfileDatabase(declaredVersion, declaredVersion))
        assertFalse(isDowngradedProfileDatabase(declaredVersion - 1, declaredVersion))
        // The "never recorded" preferences default must not trip the notice on a fresh install.
        assertFalse(isDowngradedProfileDatabase(0, declaredVersion))
    }

    @Test
    fun `create() prechecks a downgrade before Room can reach the destructive fallback`() {
        // Source-order contract (same style as the release-engineering pins): the downgrade guard
        // throws before Room.databaseBuilder is configured, so an older APK opening a newer
        // database fails loud instead of letting fallbackToDestructiveMigration wipe profiles.
        val source =
            File("src/main/kotlin/com/foxhole/guard/core/data/ProfileDatabase.kt").readText()
        val guardIndex = source.indexOf("assertNoProfileDatabaseDowngrade(")
        val fallbackIndex = source.indexOf(".fallbackToDestructiveMigration(")
        assertTrue("create() must precheck the on-disk schema version", guardIndex >= 0)
        assertTrue("destructive fallback must stay declared (corruption recovery)", fallbackIndex >= 0)
        assertTrue(
            "the downgrade precheck must run before the destructive fallback is configured",
            guardIndex < fallbackIndex,
        )
    }
}
