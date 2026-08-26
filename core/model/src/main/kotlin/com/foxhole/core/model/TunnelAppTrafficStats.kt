package com.foxhole.core.model

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TunnelAppTrafficDelta(
    val rxBytes: Long,
    val txBytes: Long,
)

object TunnelAppTrafficStats {
    private val rxByPackage = ConcurrentHashMap<String, AtomicLong>()
    private val txByPackage = ConcurrentHashMap<String, AtomicLong>()

    fun add(
        packageName: String,
        rxDelta: Long,
        txDelta: Long,
    ) {
        val normalized = packageName.trim()
        if (normalized.isEmpty() || (rxDelta <= 0L && txDelta <= 0L)) {
            return
        }
        if (rxDelta > 0L) {
            rxByPackage.computeIfAbsent(normalized) { AtomicLong() }.addAndGet(rxDelta)
        }
        if (txDelta > 0L) {
            txByPackage.computeIfAbsent(normalized) { AtomicLong() }.addAndGet(txDelta)
        }
    }

    fun drain(): Map<String, TunnelAppTrafficDelta> {
        val packages = rxByPackage.keys + txByPackage.keys
        if (packages.isEmpty()) {
            return emptyMap()
        }
        return packages
            .mapNotNull { packageName ->
                val rx = rxByPackage.remove(packageName)?.get() ?: 0L
                val tx = txByPackage.remove(packageName)?.get() ?: 0L
                if (rx > 0L || tx > 0L) {
                    packageName to TunnelAppTrafficDelta(rxBytes = rx, txBytes = tx)
                } else {
                    null
                }
            }
            .toMap()
    }

    fun reset() {
        rxByPackage.clear()
        txByPackage.clear()
    }
}
