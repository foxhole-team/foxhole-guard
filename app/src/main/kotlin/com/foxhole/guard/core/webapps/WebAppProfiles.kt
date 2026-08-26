package com.foxhole.guard.core.webapps

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal fun webAppProfileName(appId: Long): String = "webapp-$appId"

internal enum class WebAppClearMode { PER_APP_PROFILE, PER_SITE, FULL_WIPE_ONLY }

internal fun webAppClearMode(multiProfile: Boolean, perSiteDelete: Boolean): WebAppClearMode =
    when {
        multiProfile -> WebAppClearMode.PER_APP_PROFILE
        perSiteDelete -> WebAppClearMode.PER_SITE
        else -> WebAppClearMode.FULL_WIPE_ONLY
    }

internal object WebAppProfiles {
    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

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

    fun cookieManager(view: WebView): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.getProfile(view).cookieManager }
                .getOrDefault(CookieManager.getInstance())
        } else {
            CookieManager.getInstance()
        }

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
