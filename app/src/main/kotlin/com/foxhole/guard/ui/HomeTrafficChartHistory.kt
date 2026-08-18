package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pTrafficSnapshot
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.TorTrafficSnapshot
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.core.model.TrafficSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val TRAFFIC_CHART_LANE_TOTAL_RX = 0
internal const val TRAFFIC_CHART_LANE_TOTAL_TX = 1
internal const val TRAFFIC_CHART_LANE_TOR_RX = 2
internal const val TRAFFIC_CHART_LANE_TOR_TX = 3
internal const val TRAFFIC_CHART_LANE_I2P_RX = 4
internal const val TRAFFIC_CHART_LANE_I2P_TX = 5
internal const val TRAFFIC_CHART_LANE_COUNT = 6

internal class TrafficChartHistory(
    capacitySeconds: Int,
) {
    var capacitySeconds: Int = capacitySeconds.coerceAtLeast(1)
        private set
    private var lanes = Array(TRAFFIC_CHART_LANE_COUNT) { LongArray(this.capacitySeconds) }
    private var head = 0

    var sampleCount: Int = 0
        private set

    fun append(values: LongArray) {
        require(values.size == TRAFFIC_CHART_LANE_COUNT) { "expected one value per lane" }
        for (lane in 0 until TRAFFIC_CHART_LANE_COUNT) {
            lanes[lane][head] = values[lane]
        }
        head = (head + 1) % capacitySeconds
        if (sampleCount < capacitySeconds) sampleCount++
    }

    fun appendZeros(count: Int) {
        val zerosToAppend = count.coerceIn(0, capacitySeconds)
        repeat(zerosToAppend) {
            append(EMPTY_TRAFFIC_CHART_SAMPLE)
        }
    }

    fun valueAt(
        lane: Int,
        indexFromOldest: Int,
    ): Long {
        require(indexFromOldest in 0 until sampleCount) { "index $indexFromOldest of $sampleCount" }
        val start = (head - sampleCount + capacitySeconds * 2) % capacitySeconds
        return lanes[lane][(start + indexFromOldest) % capacitySeconds]
    }

    fun clearAll() {
        head = 0
        sampleCount = 0
    }

    fun clearLanePair(
        rxLane: Int,
        txLane: Int,
    ) {
        lanes[rxLane].fill(0L)
        lanes[txLane].fill(0L)
    }

    fun resize(newCapacitySeconds: Int) {
        val capacity = newCapacitySeconds.coerceAtLeast(1)
        if (capacity == capacitySeconds) return
        val keep = minOf(sampleCount, capacity)
        val next = Array(TRAFFIC_CHART_LANE_COUNT) { LongArray(capacity) }
        for (lane in 0 until TRAFFIC_CHART_LANE_COUNT) {
            for (i in 0 until keep) {
                next[lane][i] = valueAt(lane, sampleCount - keep + i)
            }
        }
        lanes = next
        capacitySeconds = capacity
        head = keep % capacity
        sampleCount = keep
    }
}

internal class TrafficChartFrame(
    val version: Long,
    val history: TrafficChartHistory,
)

internal class TrafficChartRecorder(
    private val scope: CoroutineScope,
    private val traffic: StateFlow<TrafficSnapshot>,
    private val connection: StateFlow<ConnectionSnapshot>,
    private val torTraffic: StateFlow<TorTrafficSnapshot> = TorTrafficStats.snapshot,
    private val i2pTraffic: StateFlow<I2pTrafficSnapshot> = I2pTrafficStats.snapshot,
    initialCapacityMinutes: Int = 5,
) {
    private val history = TrafficChartHistory(initialCapacityMinutes * SECONDS_PER_MINUTE)
    private val frameMutable = MutableStateFlow(TrafficChartFrame(version = 0L, history = history))
    val frame: StateFlow<TrafficChartFrame> = frameMutable

    private var version = 0L
    private var lastSampleAtMs = 0L
    private var lastTorRxTotal = -1L
    private var lastTorTxTotal = -1L
    private var lastI2pRxTotal = -1L
    private var lastI2pTxTotal = -1L
    private val sample = LongArray(TRAFFIC_CHART_LANE_COUNT)

    fun start() {
        scope.launch(Dispatchers.Main.immediate) {
            traffic.collect { snapshot -> onSample(snapshot) }
        }
        scope.launch(Dispatchers.Main.immediate) {
            connection.map { it.state }.distinctUntilChanged().collect(::onConnectionState)
        }
    }

    internal fun onConnectionState(state: ConnectionState) {
        if (state != ConnectionState.IDLE && state != ConnectionState.ERROR) return
        dropLaneBaselines()
    }

    fun setCapacityMinutes(minutes: Int) {
        history.resize(minutes.coerceAtLeast(1) * SECONDS_PER_MINUTE)
        publish()
    }

    fun clearLanePair(
        rxLane: Int,
        txLane: Int,
    ) {
        history.clearLanePair(rxLane, txLane)
        publish()
    }

    internal fun onSample(
        snapshot: TrafficSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!snapshot.available) {
            dropLaneBaselines()
            return
        }
        val elapsedSinceLastSampleMs =
            if (lastSampleAtMs == 0L) {
                MS_PER_SECOND
            } else {
                (nowMs - lastSampleAtMs).coerceAtLeast(1L)
            }
        val gap = lastSampleAtMs != 0L && elapsedSinceLastSampleMs > TRAFFIC_CHART_GAP_BASELINE_RESET_MS
        val missingWholeSeconds =
            (elapsedSinceLastSampleMs / MS_PER_SECOND - 1L)
                .coerceIn(0L, history.capacitySeconds.toLong())
                .toInt()
        history.appendZeros(missingWholeSeconds)
        if (gap) {
            dropLaneBaselines()
        }
        val tor = torTraffic.value
        val i2p = i2pTraffic.value
        sample[TRAFFIC_CHART_LANE_TOTAL_RX] = snapshot.rxBytesPerSec.coerceAtLeast(0L)
        sample[TRAFFIC_CHART_LANE_TOTAL_TX] = snapshot.txBytesPerSec.coerceAtLeast(0L)
        sample[TRAFFIC_CHART_LANE_TOR_RX] =
            laneRate(tor.rxTotalBytes, lastTorRxTotal, elapsedSinceLastSampleMs)
        sample[TRAFFIC_CHART_LANE_TOR_TX] =
            laneRate(tor.txTotalBytes, lastTorTxTotal, elapsedSinceLastSampleMs)
        sample[TRAFFIC_CHART_LANE_I2P_RX] =
            laneRate(i2p.rxTotalBytes, lastI2pRxTotal, elapsedSinceLastSampleMs)
        sample[TRAFFIC_CHART_LANE_I2P_TX] =
            laneRate(i2p.txTotalBytes, lastI2pTxTotal, elapsedSinceLastSampleMs)
        lastTorRxTotal = tor.rxTotalBytes
        lastTorTxTotal = tor.txTotalBytes
        lastI2pRxTotal = i2p.rxTotalBytes
        lastI2pTxTotal = i2p.txTotalBytes
        lastSampleAtMs = nowMs
        history.append(sample)
        publish()
    }

    private fun dropLaneBaselines() {
        lastTorRxTotal = -1L
        lastTorTxTotal = -1L
        lastI2pRxTotal = -1L
        lastI2pTxTotal = -1L
    }

    private fun laneRate(
        total: Long,
        lastTotal: Long,
        elapsedMs: Long,
    ): Long =
        if (lastTotal < 0L) {
            0L
        } else {
            ((total - lastTotal).coerceAtLeast(0L) * MS_PER_SECOND) / elapsedMs
        }

    private fun publish() {
        frameMutable.value = TrafficChartFrame(version = ++version, history = history)
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val MS_PER_SECOND = 1000L
private val EMPTY_TRAFFIC_CHART_SAMPLE = LongArray(TRAFFIC_CHART_LANE_COUNT)
internal const val TRAFFIC_CHART_GAP_BASELINE_RESET_MS = 5_000L
