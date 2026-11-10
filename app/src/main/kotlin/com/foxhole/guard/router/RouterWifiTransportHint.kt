package com.foxhole.guard.router

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

// Legacy Android cannot expose the identity of this exact Network; certificate checks remain authoritative.
@ChecksSdkIntAtLeast(api = 29, lambda = 1)
internal inline fun routerWifiTransportHint(
    sdkInt: Int = Build.VERSION.SDK_INT,
    readHint: () -> String?,
): String? = if (sdkInt >= 29) readHint() else null
