package com.foxhole.guard.core.webapps

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/** Host of a configured https web app; junk and non-https never turn into someone else's wipe. */
internal fun webAppClearSite(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https") {
        return null
    }
    return parsed.host
}

/**
 * Native wipe of web-app data. With MULTI_PROFILE each app is its own profile, so a per-app wipe
 * is [WebAppProfiles.delete] — plus a per-site sweep of the shared default profile, where data
 * from before the profile split may still linger. Without profiles, per-site clearing rides
 * [WebStorageCompat.deleteBrowsingDataForSite]; a WebView with neither feature only offers the
 * full-profile wipe, which the UI confirms separately because it clears every web app at once.
 * Callers pause the watchdog around a wipe ([WebAppsWatchdog.withPollingPaused]) so its hidden
 * WebView is not holding the site — or the profile — open.
 */
internal class WebAppsDataCleaner(
    private val context: Context,
) {
    private val perSiteSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    val perAppClearSupported: Boolean
        get() = webAppClearMode(WebAppProfiles.supported, perSiteSupported) !=
            WebAppClearMode.FULL_WIPE_ONLY

    /** Clears one app's data; returns the cleared site label, null when refused or failed. */
    suspend fun clearApp(appId: Long, url: String): String? {
        val site = webAppClearSite(url) ?: return null
        return when (webAppClearMode(WebAppProfiles.supported, perSiteSupported)) {
            WebAppClearMode.PER_APP_PROFILE ->
                withContext(Dispatchers.Main) {
                    val dropped = WebAppProfiles.delete(appId)
                    // Leftovers from before the profile split live in the default profile;
                    // sweep them per-site where the WebView can.
                    if (perSiteSupported) {
                        clearDefaultProfileSite(site)
                        CookieManager.getInstance().flush()
                    }
                    if (dropped) site else null
                }
            WebAppClearMode.PER_SITE ->
                withContext(Dispatchers.Main) {
                    clearDefaultProfileSite(site).also { CookieManager.getInstance().flush() }
                }
            WebAppClearMode.FULL_WIPE_ONLY -> null
        }
    }

    private suspend fun clearDefaultProfileSite(site: String): String? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) return null
        return runCatching {
            var resolvedSite: String? = null
            suspendCancellableCoroutine<Unit> { continuation ->
                resolvedSite =
                    WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), site) {
                        continuation.resume(Unit)
                    }
            }
            resolvedSite
        }.getOrNull()
    }

    /** Full wipe: every app's profile and the shared default profile's cookies, storages, cache. */
    suspend fun clearAll(appIds: List<Long>): Boolean =
        withContext(Dispatchers.Main) {
            runCatching {
                if (WebAppProfiles.supported) {
                    appIds.forEach { appId -> WebAppProfiles.delete(appId) }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) {
                            continuation.resume(Unit)
                        }
                    }
                } else {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
                    }
                    WebStorage.getInstance().deleteAllData()
                    // The HTTP cache has no static handle before DELETE_BROWSING_DATA: a throwaway
                    // WebView is the documented way to reach the profile-wide cache.
                    WebView(context).apply {
                        clearCache(true)
                        destroy()
                    }
                }
                CookieManager.getInstance().flush()
                true
            }.getOrDefault(false)
        }
}
