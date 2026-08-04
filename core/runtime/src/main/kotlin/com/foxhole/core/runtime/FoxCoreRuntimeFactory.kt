package com.foxhole.core.runtime

fun createVpnRuntime(
    diagnosticsLogger: RuntimeDiagnosticsSink,
): FoxholeRuntime =
    FoxCoreRuntime(diagnosticsLogger = diagnosticsLogger)
