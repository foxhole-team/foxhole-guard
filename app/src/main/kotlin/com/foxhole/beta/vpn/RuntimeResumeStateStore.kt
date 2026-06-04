package com.foxhole.beta.vpn

import android.content.Context
import androidx.core.content.edit
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession

internal data class RuntimeResumeState(
    val trafficMode: TrafficMode,
    val profileId: Long?,
    val protocolOptionId: String?,
    val localGuardMode: LocalGuardMode?,
)

internal object RuntimeResumeStateStore {
    private const val PREFERENCES_NAME = "foxhole_runtime_resume"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TRAFFIC_MODE = "traffic_mode"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_PROTOCOL_OPTION_ID = "protocol_option_id"
    private const val KEY_LOCAL_GUARD_MODE = "local_guard_mode"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_USER_STOPPED_AT = "user_stopped_at"
    private const val USER_STOP_SUPPRESSION_WINDOW_MS = 45_000L

    fun markProfileRuntime(
        context: Context,
        trafficMode: TrafficMode,
        session: VpnSession,
    ) {
        context.preferences().edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_TRAFFIC_MODE, trafficMode.name)
            putLong(KEY_PROFILE_ID, session.profileId)
            session.protocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.let { putString(KEY_PROTOCOL_OPTION_ID, it) }
                ?: remove(KEY_PROTOCOL_OPTION_ID)
            remove(KEY_LOCAL_GUARD_MODE)
            remove(KEY_USER_STOPPED_AT)
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }
    }

    fun markLocalGuardRuntime(
        context: Context,
        mode: LocalGuardMode,
    ) {
        context.preferences().edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_TRAFFIC_MODE, TrafficMode.TUNNEL.name)
            remove(KEY_PROFILE_ID)
            remove(KEY_PROTOCOL_OPTION_ID)
            putString(KEY_LOCAL_GUARD_MODE, mode.name)
            remove(KEY_USER_STOPPED_AT)
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }
    }

    fun markUserStop(context: Context) {
        context.preferences().edit {
            putBoolean(KEY_ACTIVE, false)
            remove(KEY_PROFILE_ID)
            remove(KEY_PROTOCOL_OPTION_ID)
            remove(KEY_LOCAL_GUARD_MODE)
            putLong(KEY_USER_STOPPED_AT, System.currentTimeMillis())
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }
    }

    fun clearRecentUserStop(context: Context) {
        context.preferences().edit { remove(KEY_USER_STOPPED_AT) }
    }

    fun hasRecentUserStop(context: Context): Boolean {
        val preferences = context.preferences()
        val stoppedAt = preferences.getLong(KEY_USER_STOPPED_AT, 0L)
        if (stoppedAt <= 0L) {
            return false
        }
        val recent = System.currentTimeMillis() - stoppedAt <= USER_STOP_SUPPRESSION_WINDOW_MS
        if (!recent) {
            clearRecentUserStop(context)
        }
        return recent
    }

    fun clear(context: Context) {
        context.preferences().edit { clear() }
    }

    fun read(context: Context): RuntimeResumeState? {
        val preferences = context.preferences()
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            return null
        }
        val trafficMode =
            preferences.getString(KEY_TRAFFIC_MODE, null)
                ?.let { raw -> runCatching { TrafficMode.valueOf(raw) }.getOrNull() }
                ?: TrafficMode.TUNNEL
        val profileId =
            if (preferences.contains(KEY_PROFILE_ID)) {
                preferences.getLong(KEY_PROFILE_ID, 0L)
            } else {
                null
            }
        val protocolOptionId =
            preferences
                .getString(KEY_PROTOCOL_OPTION_ID, null)
                ?.takeIf(String::isNotBlank)
        val localGuardMode =
            preferences.getString(KEY_LOCAL_GUARD_MODE, null)
                ?.let { raw -> runCatching { LocalGuardMode.valueOf(raw) }.getOrNull() }
        return RuntimeResumeState(
            trafficMode = trafficMode,
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            localGuardMode = localGuardMode,
        )
    }

    private fun Context.preferences() =
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
