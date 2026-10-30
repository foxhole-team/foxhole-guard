package com.foxhole.guard.core.sentinel.scanner

internal enum class ScannerArtifactKind { BASE, SPLIT }

internal data class ScannerDescriptorClaim(val label: String, val kind: ScannerArtifactKind, val bytes: Long) {
    init {
        require(label.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        require(bytes in 1..67108864L)
    }
}

internal fun validateScannerDescriptorClaims(claims: List<ScannerDescriptorClaim>) {
    require(claims.size in 1..32)
    require(claims.count { it.kind == ScannerArtifactKind.BASE } == 1)
    require(claims.map { it.label }.toSet().size == claims.size)
    require(claims.sumOf { it.bytes } <= 268435456L)
}

internal fun scannerSenderIsOwner(senderUid: Int, expectedOwnerUid: Int): Boolean =
    expectedOwnerUid >= 0 && expectedOwnerUid % 100000 in 10000..19999 && senderUid == expectedOwnerUid

internal object ScannerIpcCodes {
    const val HELLO = 1
    const val SCAN = 2
    const val CANCEL = 3
    const val RESULT = 4
    const val SHUTDOWN = 5
    const val PROTOCOL_VERSION = 2
    const val MAX_RESULT_BYTES = 262144
    const val MAX_RULE_SOURCE_BYTES = 262144
    const val OWNER_WATCHDOG_MILLIS = 15000L
}
