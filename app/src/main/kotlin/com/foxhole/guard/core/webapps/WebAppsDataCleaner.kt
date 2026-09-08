package com.foxhole.guard.core.webapps

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/** Host of a configured https web app; junk and non-https never turn into someone else's wipe. */
internal fun webAppClearSite(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    return parsed.host.takeIf { parsed.scheme == "https" }
}

internal class WebAppsDataCleaner(
    private val context: Context,
    private val profileSupported: () -> Boolean = { WebAppProfiles.supported },
    private val siteSupported: () -> Boolean = {
        WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
    },
    private val deleteProfile: (Long) -> Boolean = WebAppProfiles::delete,
    private val deleteLegacySite: suspend (String) -> String? = ::clearDefaultWebAppSite,
) {
    private val pending = WebAppPendingDeletions(context)

    fun recordDeletion(appId: Long, url: String) = pending.record(appId, url)

    val perAppClearSupported: Boolean
        get() = siteSupported()

    suspend fun clearApp(appId: Long, url: String): String? =
        withContext(NonCancellable + Dispatchers.Main) {
            val site = webAppClearSite(url) ?: return@withContext null
            pending.record(appId, url)
            runCatching {
                val namedCleared = !profileSupported() || deleteProfile(appId)
                // Existing users may have data from a provider that lacked MULTI_PROFILE.
                val legacyCleared = siteSupported() && deleteLegacySite(site) != null
                CookieManager.getInstance().flush()
                site.takeIf { namedCleared && legacyCleared && pending.completed(appId) }
            }.getOrNull()
        }

    suspend fun prepareForLoad(appId: Long): Boolean {
        retryPending()
        return !pending.fullWipePending() && appId !in pending.entries() &&
            (profileSupported() || pending.entries().isEmpty())
    }

    suspend fun retryPending() {
        if (pending.fullWipePending()) {
            clearAll(emptyList())
        } else {
            pending.entries().forEach { (id, url) -> clearApp(id, url) }
        }
    }

    suspend fun clearAll(appIds: List<Long>): Boolean =
        withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                pending.recordFullWipe()
                val ids = (appIds + pending.entries().keys).distinct()
                ids.forEach { pending.record(it, pending.entries()[it] ?: FULL_WIPE_PLACEHOLDER) }
                val namedOutcomes = ids.map { id -> !profileSupported() || deleteProfile(id) }
                clearDefaultWebAppData(context)
                CookieManager.getInstance().flush()
                if (!namedOutcomes.all { it }) return@runCatching false
                val completed = ids.map(pending::completed).all { it }
                completed && pending.completeFullWipe()
            }.getOrDefault(false)
        }

    private companion object {
        const val FULL_WIPE_PLACEHOLDER = "https://pending.invalid"
    }
}

private suspend fun clearDefaultWebAppSite(site: String): String? = runCatching {
    var resolvedSite: String? = null
    suspendCancellableCoroutine<Unit> { continuation ->
        resolvedSite = WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), site) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    resolvedSite
}.getOrNull()

private suspend fun clearDefaultWebAppData(context: Context) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
        suspendCancellableCoroutine<Unit> { continuation ->
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    } else {
        suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().removeAllCookies {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        WebStorage.getInstance().deleteAllData()
        WebView(context).apply {
            clearCache(true)
            destroy()
        }
    }
}
