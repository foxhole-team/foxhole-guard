package com.foxhole.core.runtime

/**
 * What a finished teardown leaves behind.
 *
 * Two facts used to be one condition — "the native generation is gone" and
 * "the master TUN descriptor is closed" — and only their conjunction released
 * the runtime for a restart. That made a transient descriptor state permanent;
 * see [settleTeardown].
 */
internal enum class TeardownSettlement {
    /** Native gone, master descriptor closed. The runtime is idle. */
    RELEASED,

    /**
     * Native gone, master descriptor still open. The session is over and a
     * restart is allowed, but the descriptor is unaccounted for, so this is
     * still an error state rather than a clean stop.
     */
    RELEASED_WITH_TUN_OPEN,

    /** The native generation was not released. Nothing may restart on it. */
    NOT_RELEASED,
}

/**
 * Decide what a stop or force-kill actually settled.
 *
 * The native side is the only thing that gates a restart, so it alone decides
 * whether the active session is cleared. A master descriptor that could not be
 * closed is a failure and stays visible as one — it is just not a reason to
 * refuse every future connection.
 *
 * The trap this encodes: `nativeForceKill` removes the handle from the core's
 * registry *before* it can report `STOP_TIMED_OUT`, and a quarantined worker
 * keeps its own duplicate of the TUN descriptor, so the descriptor probe
 * legitimately reads OPEN after a successful kill. Requiring both to be true
 * meant one wedged stop left the app answering `ALREADY_RUNNING` to every
 * connect attempt for the lifetime of the process.
 */
internal fun settleTeardown(
    nativeReleased: Boolean,
    masterTunClosed: Boolean,
): TeardownSettlement =
    when {
        !nativeReleased -> TeardownSettlement.NOT_RELEASED
        masterTunClosed -> TeardownSettlement.RELEASED
        else -> TeardownSettlement.RELEASED_WITH_TUN_OPEN
    }

/**
 * Whether a `nativeForceKill` return code means the handle is no longer ours.
 *
 * `STOP_TIMED_OUT` belongs here. The core removes the registry entry first and
 * documents that the app is then entitled to believe the handle is gone; the
 * timeout describes a worker thread sent to the process quarantine, not a
 * runtime the app still owns. `STOP_PANICKED` does not belong here — that call
 * never reached the point where the handle is released.
 */
internal fun forceKillReleasedHandle(code: Int): Boolean =
    code == FoxholeNativeEngine.STOPPED ||
        code == FoxholeNativeEngine.ALREADY_STOPPED ||
        code == FoxholeNativeEngine.STOP_UNKNOWN_HANDLE ||
        code == FoxholeNativeEngine.STOP_TIMED_OUT
