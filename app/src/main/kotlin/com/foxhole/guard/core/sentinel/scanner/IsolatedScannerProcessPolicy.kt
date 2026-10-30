package com.foxhole.guard.core.sentinel.scanner

internal fun isStandardIsolatedScannerUid(uid: Int): Boolean =
    uid >= 0 && uid % 100000 in 99000..99999

internal data class ScannerRequestOwner(val requestId: String, val installationId: String, val ruleEpoch: String) {
    init {
        require(requestId.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(installationId.matches(Regex("[a-zA-Z0-9_.:-]{1,192}")))
        require(ruleEpoch.matches(Regex("[a-zA-Z0-9_.:-]{1,128}")))
    }
}

internal class ScannerPublicationLease(val owner: ScannerRequestOwner) {
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    fun mayPublish(observed: ScannerRequestOwner, current: ScannerRequestOwner): Boolean =
        !cancelled && observed == owner && current == owner
}
