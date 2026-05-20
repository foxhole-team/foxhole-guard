package com.foxhole.beta.vpn

import com.foxhole.beta.BuildConfig

internal val FOXHOLE_RUNTIME_LOG_LEVEL: String = if (BuildConfig.DEBUG) "debug" else "info"
