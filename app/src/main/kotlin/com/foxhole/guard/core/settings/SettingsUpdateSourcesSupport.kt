package com.foxhole.guard.core.settings

import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.core.network.ensurePublicHttpsUrl
import okhttp3.HttpUrl

/**
 * Normalisation of the two update sources the user may redirect.
 *
 * Both accept what a person actually has in hand — the repository page they were looking at — and
 * derive the machine endpoint from it, because nobody keeps the GitHub releases API path or a Pages
 * base memorised. Anything that is not a public https URL normalises to blank, which means "the
 * built-in source": a half-parsed host would otherwise become a silent update channel of its own.
 *
 * Neither field relaxes verification. The data feeds stay pinned to the FoxHole DB signing key, so a
 * redirected repository only works if it is published by the same tooling; the app's own updater
 * keeps requiring the manifest's sha256 to match the APK it downloads.
 */
internal fun UpdateSourceSettings.normalized(): UpdateSourceSettings {
    val database = normalizeDatabaseBaseUrl(databaseBaseUrl)
    val releases = normalizeAppReleasesUrl(appReleasesUrl)
    return copy(
        databaseBaseUrl = database,
        appReleasesUrl = releases,
        // A token with no host to send it to is a stored secret that can never be used. Clearing
        // it with the URL means "reset the source" also disposes of the credential.
        appReleasesToken = appReleasesToken.trim().takeIf { releases.isNotEmpty() }.orEmpty(),
    )
}

/**
 * The FoxHole DB feed base: the directory the four manifests sit in. A GitHub repository URL is
 * mapped to that project's Pages base, which is where the publishing workflow puts them.
 */
private fun normalizeDatabaseBaseUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return runCatching {
        val url = trimmed.ensurePublicHttpsUrl()
        val pages = url.asGithubRepository()?.let { (owner, repository) ->
            "https://$owner.github.io/$repository"
        }
        pages ?: url.toString().trimEnd('/')
    }.getOrDefault("")
}

/**
 * The app's release feed: the GitHub releases API endpoint. A repository URL is mapped to its
 * `releases/latest`; an API URL that already names a repository is kept as it is.
 */
private fun normalizeAppReleasesUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return runCatching {
        val url = trimmed.ensurePublicHttpsUrl()
        val repository = url.asGithubRepository()
        when {
            repository != null ->
                "https://api.github.com/repos/${repository.first}/${repository.second}/releases/latest"
            url.host.equals(GITHUB_API_HOST, ignoreCase = true) -> url.toString().trimEnd('/')
            // Neither a repository page nor the API: a release feed this app can read is a very
            // specific shape, and guessing one from an arbitrary host would be a fiction.
            else -> ""
        }
    }.getOrDefault("")
}

/** `https://github.com/owner/repo(.git)` → owner and repository, or null for anything else. */
private fun HttpUrl.asGithubRepository(): Pair<String, String>? {
    if (!host.equals(GITHUB_HOST, ignoreCase = true)) {
        return null
    }
    val segments = pathSegments.filter(String::isNotBlank)
    if (segments.size != 2) {
        return null
    }
    val owner = segments[0]
    val repository = segments[1].removeSuffix(".git")
    return if (owner.isNotBlank() && repository.isNotBlank()) owner to repository else null
}

private const val GITHUB_HOST = "github.com"

private const val GITHUB_API_HOST = "api.github.com"
