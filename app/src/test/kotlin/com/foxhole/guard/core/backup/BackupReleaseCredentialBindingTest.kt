package com.foxhole.guard.core.backup

import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupReleaseCredentialBindingTest {
    @Test fun currentReleaseCredentialKeepsItsOriginalSourceAcrossRestore() {
        val defaults = Settings()
        val current = defaults.copy(
            updateSources = defaults.updateSources.copy(
                appReleasesUrl = "https://api.github.com/repos/owned/current/releases/latest",
                appReleasesToken = "owned-current-token",
            )
        )
        for (url in listOf("", "https://api.github.com/repos/owned/imported/releases/latest")) {
            val backup = defaults.copy(updateSources = defaults.updateSources.copy(appReleasesUrl = url))
            val merged = mergeRestoredSettings(backup, current)
            assertEquals(current.updateSources.appReleasesUrl, merged.updateSources.appReleasesUrl)
            assertEquals(current.updateSources.appReleasesToken, merged.updateSources.appReleasesToken)
        }
    }

    @Test fun sourceWithoutCurrentCredentialsRemainsTransferable() {
        val current = Settings()
        val backup = current.copy(
            updateSources = current.updateSources.copy(
                appReleasesUrl = "https://api.github.com/repos/owned/imported/releases/latest",
            )
        )
        val merged = mergeRestoredSettings(backup, current)
        assertEquals(backup.updateSources.appReleasesUrl, merged.updateSources.appReleasesUrl)
        assertEquals("", merged.updateSources.appReleasesToken)
    }
}
