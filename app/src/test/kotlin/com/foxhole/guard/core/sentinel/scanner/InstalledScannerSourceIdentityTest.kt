package com.foxhole.guard.core.sentinel.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstalledScannerSourceIdentityTest {
    private val base = InstalledScannerArtifact("base", ScannerArtifactKind.BASE, "/owned/base.apk", 100, 1)
    private val source =
        InstalledScannerSource("org.owned.fixture", 12345, 1, 10, 20, setOf("a".repeat(64)), listOf(base))

    @Test fun installationSignerAndSourceChangesEachInvalidateIdentity() {
        listOf(
            source.copy(uid = 12346), source.copy(versionCode = 2), source.copy(firstInstallTime = 11),
            source.copy(lastUpdateTime = 21), source.copy(currentSignerSha256 = setOf("b".repeat(64))),
            source.copy(
                artifacts = listOf(base.copy(bytes = 101))
            ),
            source.copy(artifacts = listOf(base.copy(modified = 2))),
            source.copy(artifacts = listOf(base.copy(path = "/owned/replacement.apk"))),
            source.copy(
                artifacts = listOf(base.copy(inode = 2))
            ),
            source.copy(artifacts = listOf(base.copy(device = 2))),
            source.copy(artifacts = listOf(base.copy(changed = 2))),
        ).forEach { assertNotEquals(source.installationId, it.installationId) }
    }

    @Test fun fullSignerSetIsOrderIndependentAndNeverReducedToOneCertificate() {
        val multi = source.copy(currentSignerSha256 = linkedSetOf("a".repeat(64), "b".repeat(64)))
        assertEquals(
            multi.installationId,
            multi.copy(currentSignerSha256 = linkedSetOf("b".repeat(64), "a".repeat(64))).installationId
        )
        assertNotEquals(source.installationId, multi.installationId)
    }

    @Test fun MissingOrMalformedSigningAndArtifactFactsCannotBeCompleteIdentity() {
        assertThrows(IllegalArgumentException::class.java) { source.copy(currentSignerSha256 = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(currentSignerSha256 = setOf("unknown")) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(artifacts = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(uid = -1) }
    }
}
