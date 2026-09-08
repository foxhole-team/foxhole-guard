package com.foxhole.guard.runtime

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

internal data class NetworkRuleCommandRequest(
    val token: String,
    val profileId: Long,
    val protocolOptionId: String?,
    val issuedAt: Long,
) {
    fun matches(token: String?, profileId: Long, optionId: String?, now: Long): Boolean =
        this.token == token && this.profileId == profileId && protocolOptionId == optionId &&
            now >= issuedAt && now - issuedAt < EXPIRY_MS

    companion object {
        const val EXPIRY_MS = 10 * 60 * 1_000L
    }
}

internal object NetworkRuleCommandRequests {
    @Synchronized
    fun issue(context: Context, profileId: Long, optionId: String?): String {
        val token = UUID.randomUUID().toString()
        check(
            preferences(context).edit().clear()
                .putString("token", token)
                .putLong("profile", profileId)
                .putString("option", optionId)
                .putLong("issued", SystemClock.elapsedRealtime())
                .putInt("boot", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1))
                .commit()
        ) { "notification command could not be persisted" }
        return token
    }

    @Synchronized
    fun consume(context: Context, token: String?, profileId: Long, optionId: String?): Boolean {
        val preferences = preferences(context)
        val issuedToken = preferences.getString("token", null) ?: return false
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0 || preferences.getInt("boot", -2) != boot) return false
        val issued = NetworkRuleCommandRequest(
            issuedToken,
            preferences.getLong("profile", -1L),
            preferences.getString("option", null),
            preferences.getLong("issued", -1L),
        )
        if (!issued.matches(token, profileId, optionId, SystemClock.elapsedRealtime())) return false
        return preferences.edit().clear().commit()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("network_rule_command", Context.MODE_PRIVATE)
}
