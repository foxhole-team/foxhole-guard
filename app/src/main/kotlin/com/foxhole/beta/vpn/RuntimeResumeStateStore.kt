package com.foxhole.beta.vpn

import android.content.Context
import androidx.core.content.edit
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession

internal data class RuntimeResumeState(
    val trafficMode: TrafficMode,
    val profileId: Long?,
    val localGuardMode: LocalGuardMode?,
)

internal object RuntimeResumeStateStore {
    private const val PREFERENCES_NAME = "foxhole_runtime_resume"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TRAFFIC_MODE = "traffic_mode"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_LOCAL_GUARD_MODE = "local_guard_mode"
    private const val KEY_UPDATED_AT = "updated_at"

    fun markProfileRuntime(
        context: Context,
        trafficMode: TrafficMode,
        session: VpnSession,
    ) {
        context.preferences().edit {
            putBoolean(KEY_ACTIVE, true)
            putString(KEY_TRAFFIC_MODE, trafficMode.name)
            putLong(KEY_PROFILE_ID, session.profileId)
            remove(KEY_LOCAL_GUARD_MODE)
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
            putString(KEY_LOCAL_GUARD_MODE, mode.name)
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }
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
        val localGuardMode =
            preferences.getString(KEY_LOCAL_GUARD_MODE, null)
                ?.let { raw -> runCatching { LocalGuardMode.valueOf(raw) }.getOrNull() }
        return RuntimeResumeState(
            trafficMode = trafficMode,
            profileId = profileId,
            localGuardMode = localGuardMode,
        )
    }

    private fun Context.preferences() =
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
