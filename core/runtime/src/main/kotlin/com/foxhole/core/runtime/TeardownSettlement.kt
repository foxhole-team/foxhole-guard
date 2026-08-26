package com.foxhole.core.runtime

internal enum class TeardownSettlement {
    /** Native gone, master descriptor closed. The runtime is idle. */
    RELEASED,

    RELEASED_WITH_TUN_OPEN,

    NOT_RELEASED,
}

internal fun settleTeardown(
    nativeReleased: Boolean,
    masterTunClosed: Boolean,
): TeardownSettlement =
    when {
        !nativeReleased -> TeardownSettlement.NOT_RELEASED
        masterTunClosed -> TeardownSettlement.RELEASED
        else -> TeardownSettlement.RELEASED_WITH_TUN_OPEN
    }

internal fun nativeForceStopOutcome(
    callCompleted: Boolean,
    code: Int,
): NativeForceStopOutcome =
    when {
        !callCompleted -> NativeForceStopOutcome.CALL_TIMED_OUT
        code == FoxholeNativeEngine.STOP_TIMED_OUT -> NativeForceStopOutcome.QUARANTINED
        code == FoxholeNativeEngine.STOPPED ||
            code == FoxholeNativeEngine.ALREADY_STOPPED ||
            code == FoxholeNativeEngine.STOP_UNKNOWN_HANDLE -> NativeForceStopOutcome.RELEASED
        else -> NativeForceStopOutcome.FAILED
    }
