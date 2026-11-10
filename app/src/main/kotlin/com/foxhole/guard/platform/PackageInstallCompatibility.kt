package com.foxhole.guard.platform

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

@ChecksSdkIntAtLeast(api = 26, lambda = 1)
internal fun requiresPerAppInstallSettings(
    sdkInt: Int = Build.VERSION.SDK_INT,
    canRequestInstalls: () -> Boolean,
): Boolean = sdkInt >= 26 && !canRequestInstalls()
