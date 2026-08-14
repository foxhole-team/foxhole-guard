package com.foxhole.core.runtime

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Build.VERSION_CODES

inline fun <reified T : Any> Context.requireSystemServiceSafe(
    serviceName: String = T::class.java.name,
): T =
    getSystemService(T::class.java)
        ?: throw IllegalStateException(
            buildString {
                append("Missing Android system service ")
                append(serviceName)
                append(" sdk=")
                append(Build.VERSION.SDK_INT)
                append(" device=")
                append(Build.MANUFACTURER)
                append('/')
                append(Build.MODEL)
                append(" process=")
                append(processNameForDiagnostics())
            },
        )

@PublishedApi
internal fun Context.processNameForDiagnostics(): String =
    if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        packageName
    }
