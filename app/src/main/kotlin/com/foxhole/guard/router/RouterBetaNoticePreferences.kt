package com.foxhole.guard.router

import android.content.Context

/** Presentation preference only; independent of pairing and legal consent. */
internal class RouterBetaNoticePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("router-ui-notices", Context.MODE_PRIVATE)

    fun isSuppressed(): Boolean = preferences.getBoolean("beta_notice_suppressed", false)

    fun setSuppressed(value: Boolean) {
        preferences.edit().putBoolean("beta_notice_suppressed", value).apply()
    }
}
