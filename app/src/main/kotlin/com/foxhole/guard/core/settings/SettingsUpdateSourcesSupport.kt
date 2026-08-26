package com.foxhole.guard.core.settings

import com.foxhole.core.model.UpdateSourceSettings
import com.foxhole.core.network.ensurePublicHttpsUrl
import okhttp3.HttpUrl

internal fun UpdateSourceSettings.normalized(): UpdateSourceSettings {
    val database = normalizeDatabaseBaseUrl(databaseBaseUrl)
    val releases = normalizeAppReleasesUrl(appReleasesUrl)
    return copy(
        databaseBaseUrl = database,
        appReleasesUrl = releases,

        appReleasesToken = appReleasesToken.trim().takeIf { releases.isNotEmpty() }.orEmpty(),
    )
}

private fun normalizeDatabaseBaseUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return runCatching {
        val url = trimmed.ensurePublicHttpsUrl().withoutManifestPath()
        val pages = url.asGithubRepository()?.let { (owner, repository) ->
            "https://$owner.github.io/$repository"
        }
        pages ?: url.toString().trimEnd('/')
    }.getOrDefault("")
}

private fun HttpUrl.withoutManifestPath(): HttpUrl {
    val path = encodedPath.trimEnd('/')
    return if (path.endsWith(MANIFEST_PATH_SUFFIX, ignoreCase = true)) {
        val basePath = path.dropLast(MANIFEST_PATH_SUFFIX.length).ifEmpty { "/" }
        newBuilder().encodedPath(basePath).build()
    } else {
        this
    }
}

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

            else -> ""
        }
    }.getOrDefault("")
}

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

private const val MANIFEST_PATH_SUFFIX = "/manifest.json"
