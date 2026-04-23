package com.foxhole.beta.vpn

import android.net.TrafficStats
import android.os.Process
import com.foxhole.beta.core.model.TrafficSnapshot

internal interface TrafficByteSource {
    fun uidRxBytes(uid: Int): Long

    fun uidTxBytes(uid: Int): Long
}

internal object AndroidTrafficByteSource : TrafficByteSource {
    override fun uidRxBytes(uid: Int): Long = TrafficStats.getUidRxBytes(uid)

    override fun uidTxBytes(uid: Int): Long = TrafficStats.getUidTxBytes(uid)
}

internal class TrafficStatsSampler(
    private val uid: Int = Process.myUid(),
    private val byteSource: TrafficByteSource = AndroidTrafficByteSource,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private var baselineRx = 0L
    private var baselineTx = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSampleAt = 0L
    private var started = false

    fun start() {
        val rx = byteSource.uidRxBytes(uid)
        val tx = byteSource.uidTxBytes(uid)
        val now = nowProvider()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            started = false
            baselineRx = 0L
            baselineTx = 0L
            lastRx = 0L
            lastTx = 0L
            lastSampleAt = now
            return
        }
        started = true
        baselineRx = rx
        baselineTx = tx
        lastRx = rx
        lastTx = tx
        lastSampleAt = now
    }

    fun sample(resetRateBaseline: Boolean = false): TrafficSnapshot {
        val now = nowProvider()
        if (!started) {
            return TrafficSnapshot(sampledAt = now)
        }
        val rx = byteSource.uidRxBytes(uid)
        val tx = byteSource.uidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            started = false
            return TrafficSnapshot(sampledAt = now)
        }
        val totalRx = (rx - baselineRx).coerceAtLeast(0L)
        val totalTx = (tx - baselineTx).coerceAtLeast(0L)
        if (resetRateBaseline) {
            lastRx = rx
            lastTx = tx
            lastSampleAt = now
            return TrafficSnapshot(
                available = true,
                rxBytesPerSec = 0L,
                txBytesPerSec = 0L,
                rxTotalBytes = totalRx,
                txTotalBytes = totalTx,
                sampledAt = now,
            )
        }
        val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
        val rxRate = ((rx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed
        val txRate = ((tx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed
        lastRx = rx
        lastTx = tx
        lastSampleAt = now
        return TrafficSnapshot(
            available = true,
            rxBytesPerSec = rxRate,
            txBytesPerSec = txRate,
            rxTotalBytes = totalRx,
            txTotalBytes = totalTx,
            sampledAt = now,
        )
    }

    fun reset(): TrafficSnapshot {
        started = false
        baselineRx = 0L
        baselineTx = 0L
        lastRx = 0L
        lastTx = 0L
        lastSampleAt = 0L
        return TrafficSnapshot()
    }
}
