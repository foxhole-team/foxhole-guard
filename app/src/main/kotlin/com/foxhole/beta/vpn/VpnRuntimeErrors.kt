package com.foxhole.beta.vpn

import java.lang.ExceptionInInitializerError
import java.lang.reflect.InvocationTargetException

internal fun unwrapVpnRuntimeFailure(error: Throwable): Throwable {
    var current = error
    while (true) {
        current =
            when (current) {
                is InvocationTargetException -> current.targetException ?: current.cause ?: return current
                is ExceptionInInitializerError -> current.exception ?: current.cause ?: return current
                else -> return current
            }
    }
}

internal fun describeVpnRuntimeFailure(error: Throwable): String {
    val root = unwrapVpnRuntimeFailure(error)
    return root.message?.trim().takeIf { !it.isNullOrEmpty() } ?: root.javaClass.simpleName
}
