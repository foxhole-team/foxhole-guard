package com.foxhole.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class I2pTrafficSnapshot(
    val rxTotalBytes: Long = 0L,
    val txTotalBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
)

object I2pTrafficStats {
    private val state = MutableStateFlow(I2pTrafficSnapshot())
    private val lock = Any()
    private var lastRecordAtMs = 0L

    val snapshot: StateFlow<I2pTrafficSnapshot> get() = state

    fun record(
        rxDelta: Long,
        txDelta: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val current = state.value
            val elapsedMs = (nowMs - lastRecordAtMs).coerceAtLeast(1L)
            val rates =
                if (lastRecordAtMs == 0L) {
                    0L to 0L
                } else {
                    (rxDelta.coerceAtLeast(0L) * 1000L / elapsedMs) to
                        (txDelta.coerceAtLeast(0L) * 1000L / elapsedMs)
                }
            lastRecordAtMs = nowMs
            state.value =
                I2pTrafficSnapshot(
                    rxTotalBytes = current.rxTotalBytes + rxDelta.coerceAtLeast(0L),
                    txTotalBytes = current.txTotalBytes + txDelta.coerceAtLeast(0L),
                    rxBytesPerSec = rates.first,
                    txBytesPerSec = rates.second,
                )
        }
    }

    fun reset() {
        synchronized(lock) {
            lastRecordAtMs = 0L
            state.value = I2pTrafficSnapshot()
        }
    }
}
