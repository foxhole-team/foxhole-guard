package com.foxhole.guard.core.sentinel.scanner

internal fun requireScannerArtifactBudget(bytes: Int) {
    require(bytes == 16 * 1024 * 1024 || bytes == 64 * 1024 * 1024) { "Unsupported scanner artifact budget" }
}
