package com.foxhole.guard.runtime

import com.foxhole.guard.BuildConfig
import java.time.LocalDate
import java.time.ZoneId

object AppUpdateBuildSignals {
    val channel: String get() = BuildConfig.UPDATE_CHANNEL

    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    val selfUpdateAllowed: Boolean get() = AppUpdatePolicy.selfUpdateAllowed(channel)

    fun fdroidUpdateNoticeRequired(today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Boolean =
        AppUpdatePolicy.fdroidUpdateNoticeRequired(
            channel = channel,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            floorVersionCode = BuildConfig.UPDATE_FLOOR_VERSION_CODE,
            supportedUntilEpochDay = BuildConfig.UPDATE_SUPPORTED_UNTIL_EPOCH_DAY,
            todayEpochDay = today.toEpochDay(),
        )
}
