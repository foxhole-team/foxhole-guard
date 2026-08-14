package com.foxhole.guard.core.webapps

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Profile of one web app. Ids are positive, so the reserved "Default" is unreachable. */
internal fun webAppProfileName(appId: Long): String = "webapp-$appId"

/** How this WebView can clear one app's data without logging the other apps out. */
internal enum class WebAppClearMode { PER_APP_PROFILE, PER_SITE, FULL_WIPE_ONLY }

internal fun webAppClearMode(multiProfile: Boolean, perSiteDelete: Boolean): WebAppClearMode =
    when {
        multiProfile -> WebAppClearMode.PER_APP_PROFILE
        perSiteDelete -> WebAppClearMode.PER_SITE
        else -> WebAppClearMode.FULL_WIPE_ONLY
    }

/**
 * Per-app WebView profiles: each web app keeps its cookies, storages and cache in its own
 * profile, so apps cannot see each other's sessions and one app's wipe is exactly its profile.
 * A WebView without MULTI_PROFILE keeps every app on the shared default profile and the
 * pre-profile clearing paths stay in charge. Profile calls are main-thread only, like the
 * WebViews they serve.
 */
internal object WebAppProfiles {
    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Binds [view] to its app's profile — before the first load, while the WebView is still
     * profile-less. False leaves the view on the shared default profile.
     */
    fun install(view: WebView, appId: Long): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return false
        }
        return runCatching {
            val name = webAppProfileName(appId)
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(view, name)
        }.isSuccess
    }

    /** The cookie manager [view] actually uses: its profile's own, or the shared default one. */
    fun cookieManager(view: WebView): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.getProfile(view).cookieManager }
                .getOrDefault(CookieManager.getInstance())
        } else {
            CookieManager.getInstance()
        }

    /**
     * Drops the app's profile with everything in it. True also when the profile never existed —
     * the app owns no data either way; false when deletion failed (e.g. a live WebView still
     * holds the profile).
     */
    fun delete(appId: Long): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            return false
        }
        return runCatching {
            ProfileStore.getInstance().deleteProfile(webAppProfileName(appId))
            true
        }.getOrDefault(false)
    }
}
