package com.foxhole.guard.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardPackageInspectorContractTest {
    @Test
    fun `periodic inventory snapshot uses one bulk package query`() {
        val source = sourceFile().readText()
        val snapshot =
            source
                .substringAfter("fun snapshotInstalledApps()")
                .substringBefore("fun describe(")

        assertTrue(snapshot.contains("installedPackages()"))
        assertTrue(source.contains("getInstalledPackages"))
        assertFalse(snapshot.contains("versionCodeOf(packageName)"))
        assertFalse(source.contains("getInstalledApplications"))
    }

    private fun sourceFile(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/guardian/GuardPackageInspector.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/guardian/GuardPackageInspector.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/guardian/GuardPackageInspector.kt"),
        ).first(File::isFile)
}
