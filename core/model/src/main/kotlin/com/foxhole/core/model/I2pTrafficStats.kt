package com.foxhole.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class I2pTrafficSnapshot(
    val rxTotalBytes: Long = 0L,
    val txTotalBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
)

/**
 * Session byte accounting for the I2P lane: connections libbox routed into the `i2p` outbound. The
 * Tor lane is identified by its outbound TYPE, but i2pd is reached through a plain SOCKS outbound,
 * so the lane is identified by its outbound TAG instead — the type alone would sweep in every other
 * socks outbound. Rates follow the Tor lane's math: delta over the time since the previous record,
 * with the first record pinned to zero (no elapsed baseline to divide by).
 */
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
