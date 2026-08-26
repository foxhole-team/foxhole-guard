package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.core.model.UpdateSourceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

internal class UpdateSourcesNormalizationTest {

    @Test
    fun `a github repository page becomes the pages base of the data feeds`() {
        val sources = normalize(databaseBaseUrl = "https://github.com/someone/foxhole-db")

        assertEquals("https://someone.github.io/foxhole-db", sources.databaseBaseUrl)
    }

    @Test
    fun `a git suffix is not part of the repository name`() {
        val sources = normalize(databaseBaseUrl = "https://github.com/someone/foxhole-db.git")

        assertEquals("https://someone.github.io/foxhole-db", sources.databaseBaseUrl)
    }

    @Test
    fun `a plain https base is kept without its trailing slash`() {
        val sources = normalize(databaseBaseUrl = "https://mirror.example.org/foxhole-db/")

        assertEquals("https://mirror.example.org/foxhole-db", sources.databaseBaseUrl)
    }

    @Test
    fun `a manifest url is stored as a feed base`() {
        val sources = normalize(
            databaseBaseUrl = "https://mirror.example.org/foxhole-db/manifest.json",
        )

        assertEquals("https://mirror.example.org/foxhole-db", sources.databaseBaseUrl)
    }

    @Test
    fun `a manifest url with a trailing slash is stored as a feed base`() {
        val sources = normalize(
            databaseBaseUrl = "https://mirror.example.org/foxhole-db/manifest.json/",
        )

        assertEquals("https://mirror.example.org/foxhole-db", sources.databaseBaseUrl)
    }

    @Test
    fun `an unusable database address falls back to the built-in source`() {
        assertEquals("", normalize(databaseBaseUrl = "not a url").databaseBaseUrl)
        assertEquals("", normalize(databaseBaseUrl = "http://mirror.example.org/db").databaseBaseUrl)
        assertEquals("", normalize(databaseBaseUrl = "   ").databaseBaseUrl)
    }

    @Test
    fun `a github repository page becomes the releases endpoint`() {
        val sources = normalize(appReleasesUrl = "https://github.com/someone/foxhole-guard")

        assertEquals(
            "https://api.github.com/repos/someone/foxhole-guard/releases/latest",
            sources.appReleasesUrl,
        )
    }

    @Test
    fun `an api endpoint the user already has is kept`() {
        val url = "https://api.github.com/repos/someone/foxhole-guard/releases/latest"

        assertEquals(url, normalize(appReleasesUrl = url).appReleasesUrl)
    }

    @Test
    fun `a host that is not a release feed is refused`() {
        assertEquals("", normalize(appReleasesUrl = "https://example.org/releases").appReleasesUrl)
    }

    @Test
    fun `the token is dropped together with the repository it belonged to`() {
        val sources = normalize(appReleasesUrl = "", appReleasesToken = "ghp_secret")

        assertEquals("", sources.appReleasesToken)
    }

    @Test
    fun `the token survives beside a usable repository`() {
        val sources = normalize(
            appReleasesUrl = "https://github.com/someone/foxhole-guard",
            appReleasesToken = "  ghp_secret  ",
        )

        assertEquals("ghp_secret", sources.appReleasesToken)
    }

    @Test
    fun `normalising twice changes nothing`() {
        val once = Settings(
            updateSources = UpdateSourceSettings(
                databaseBaseUrl = "https://github.com/someone/foxhole-db",
                appReleasesUrl = "https://github.com/someone/foxhole-guard",
                appReleasesToken = "ghp_secret",
            ),
        ).normalized()

        assertEquals(once.updateSources, once.normalized().updateSources)
    }

    private fun normalize(
        databaseBaseUrl: String = "",
        appReleasesUrl: String = "",
        appReleasesToken: String = "",
    ): UpdateSourceSettings =
        Settings(
            updateSources = UpdateSourceSettings(
                databaseBaseUrl = databaseBaseUrl,
                appReleasesUrl = appReleasesUrl,
                appReleasesToken = appReleasesToken,
            ),
        ).normalized().updateSources
}
