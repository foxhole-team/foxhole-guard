package com.foxhole.guard.ui

import com.foxhole.guard.runtime.RemoteUpdatePhase

enum class FoxholeUpdatePhase {
    IDLE,
    CHECKING,
    NO_UPDATE,
    DOWNLOADING,
    VERIFYING,
    DONE,
    FAILED,
}

fun RemoteUpdatePhase.toFoxholeUpdatePhase(): FoxholeUpdatePhase = when (this) {
    RemoteUpdatePhase.CHECKING -> FoxholeUpdatePhase.CHECKING
    RemoteUpdatePhase.DOWNLOADING -> FoxholeUpdatePhase.DOWNLOADING
    RemoteUpdatePhase.VERIFYING -> FoxholeUpdatePhase.VERIFYING
}

internal val FoxholeUpdatePhase.isRunning: Boolean
    get() = this == FoxholeUpdatePhase.CHECKING ||
        this == FoxholeUpdatePhase.DOWNLOADING ||
        this == FoxholeUpdatePhase.VERIFYING
