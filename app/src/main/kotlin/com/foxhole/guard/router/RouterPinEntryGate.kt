package com.foxhole.guard.router

// Track only length transitions, never PIN contents. Recomposition and eligibility changes cannot retry an entry.
internal class RouterPinEntryGate {
    private var previousLength: Int? = null
    fun observe(length: Int, eligible: Boolean): Boolean {
        require(length in 0..ROUTER_PIN_DIGITS)
        val previous = previousLength
        previousLength = length
        return previous != null && previous != length && length == ROUTER_PIN_DIGITS && eligible
    }
}
