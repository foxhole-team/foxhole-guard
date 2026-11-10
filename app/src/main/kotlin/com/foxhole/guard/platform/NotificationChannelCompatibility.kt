package com.foxhole.guard.platform

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(api = 26, lambda = 2)
internal inline fun <T> androidApi26Value(
    legacy: () -> T,
    sdkInt: Int = Build.VERSION.SDK_INT,
    modern: () -> T,
): T = if (sdkInt >= 26) modern() else legacy()

@ChecksSdkIntAtLeast(api = 26, lambda = 2)
internal inline fun <T> notificationChannelValue(
    legacyValue: T,
    sdkInt: Int = Build.VERSION.SDK_INT,
    channelAccess: () -> T,
): T = androidApi26Value({ legacyValue }, sdkInt, channelAccess)

@ChecksSdkIntAtLeast(api = 26, lambda = 1)
internal inline fun withNotificationChannels(
    sdkInt: Int = Build.VERSION.SDK_INT,
    channelAccess: () -> Unit,
) {
    notificationChannelValue(Unit, sdkInt, channelAccess)
}
