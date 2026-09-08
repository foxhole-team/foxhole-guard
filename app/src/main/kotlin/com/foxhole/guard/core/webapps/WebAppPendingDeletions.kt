package com.foxhole.guard.core.webapps

import android.content.Context

internal class WebAppPendingDeletions(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "web_app_pending_deletions",
        Context.MODE_PRIVATE
    )

    fun record(appId: Long, url: String) {
        check(preferences.edit().putString(appId.toString(), url).commit()) {
            "WebApp cleanup could not be recorded"
        }
    }

    fun entries(): Map<Long, String> = preferences.all.mapNotNull { (id, url) ->
        id.toLongOrNull()?.let { value -> (url as? String)?.let { value to it } }
    }.toMap()

    fun recordFullWipe() {
        check(preferences.edit().putBoolean("full_wipe_pending", true).commit()) {
            "WebApp full cleanup could not be recorded"
        }
    }

    fun fullWipePending(): Boolean = preferences.getBoolean("full_wipe_pending", false)

    fun completeFullWipe(): Boolean = preferences.edit().remove("full_wipe_pending").commit()

    fun completed(appId: Long): Boolean = preferences.edit().remove(appId.toString()).commit()
}
