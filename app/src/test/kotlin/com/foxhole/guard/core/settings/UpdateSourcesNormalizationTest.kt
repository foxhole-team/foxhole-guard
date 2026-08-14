package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import com.foxhole.core.model.UpdateSourceSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two redirectable update channels.
 *
 * What is being defended: a value that reaches disk must be an endpoint this app can actually read,
 * because a half-parsed one becomes a silent update source — the screen would report a repository
 * while every fetch quietly failed, or worse, succeeded against something unintended. Anything that
 * does not normalise to such an endpoint becomes blank, and blank means the built-in source.
 */
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

    /** A mirror hosted anywhere else is fine — the signature check, not the host, is the gate. */
    @Test
    fun `a plain https base is kept without its trailing slash`() {
        val sources = normalize(databaseBaseUrl = "https://mirror.example.org/foxhole-db/")

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

    /**
     * A release feed this app can read has one shape. Guessing it from an arbitrary host would
     * invent an endpoint that never answers, and the updates screen would report it as configured.
     */
    @Test
    fun `a host that is not a release feed is refused`() {
        assertEquals("", normalize(appReleasesUrl = "https://example.org/releases").appReleasesUrl)
    }

    /** A credential with nowhere to go is a secret kept for no reason. */
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

    /** Storage normalises on every read, so a stored value must be a fixed point of it. */
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
