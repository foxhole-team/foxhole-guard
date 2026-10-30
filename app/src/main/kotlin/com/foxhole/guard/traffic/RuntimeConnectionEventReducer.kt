package com.foxhole.guard.traffic

internal class RuntimeConnectionEventReducer(private val capacity: Int = 2_000) {
    init { require(capacity > 0) }
    private val metadata = linkedMapOf<String, RuntimeConnectionRecord>()
    private val closedIds = linkedSetOf<String>()
    private var generation = 0L
    private var epoch = 0L
    private var processInfo = false

    fun reset() {
        metadata.clear()
        closedIds.clear()
        generation = 0L
    }

    fun reduce(
        snapshot: RuntimeConnectionSnapshot,
        drain: RuntimeTrafficDrain,
        precedingLive: List<RuntimeConnectionRecord> = emptyList(),
        resetEpoch: Long = 0L,
        includeProcessInfo: Boolean = true,
    ): RuntimeConnectionSnapshot {
        if (snapshot.generation <= 0L) {
            reset()
            return snapshot.copy(connections = emptyList())
        }
        if (!matchesContext(snapshot.generation, resetEpoch, includeProcessInfo)) reset()
        generation = snapshot.generation
        epoch = resetEpoch
        processInfo = includeProcessInfo
        var partial = snapshot.truncated || drain.dropped > 0L
        if (drain.dropped > 0L) {
            metadata.clear()
            closedIds.clear()
        }
        val fresh = precedingLive + snapshot.connections
        fresh.forEach { if (remember(it, includeProcessInfo)) partial = true }
        if (drain.dropped == 0L) {
            drain.openedConnections.forEach { opened ->
                if (opened.connectionId !in closedIds && opened.connectionId !in metadata &&
                    remember(opened, includeProcessInfo)
                ) {
                    partial = true
                }
            }
        }
        val resolved = resolveClosures(drain.closedConnections)
        return snapshot.copy(
            connections = (snapshot.connections.filterNot { it.connectionId in closedIds } + resolved.rows)
                .take(capacity).map { if (includeProcessInfo) it else it.withoutProcessInfo() },
            truncated = partial || resolved.partial,
        )
    }

    private fun resolveClosures(closedRows: List<RuntimeConnectionRecord>): ResolvedClosures {
        val finalRows = mutableListOf<RuntimeConnectionRecord>()
        var partial = false
        closedRows.forEach { closed ->
            if (closed.connectionId !in closedIds) {
                val before = metadata.remove(closed.connectionId)
                if (before == null) partial = true
                finalRows += (before ?: closed).copy(bytesTx = closed.bytesTx, bytesRx = closed.bytesRx)
                closedIds += closed.connectionId
                while (closedIds.size > capacity) closedIds.remove(closedIds.first())
            }
        }
        return ResolvedClosures(finalRows, partial)
    }

    private fun matchesContext(nextGeneration: Long, nextEpoch: Long, includeOwners: Boolean): Boolean =
        generation == nextGeneration && epoch == nextEpoch && processInfo == includeOwners

    private fun remember(record: RuntimeConnectionRecord, includeProcessInfo: Boolean): Boolean {
        if (record.connectionId in closedIds) return false
        metadata.remove(record.connectionId)
        metadata[record.connectionId] = if (includeProcessInfo) record else record.withoutProcessInfo()
        var evicted = false
        while (metadata.size > capacity) {
            metadata.remove(metadata.keys.first())
            evicted = true
        }
        return evicted
    }
}

private data class ResolvedClosures(val rows: List<RuntimeConnectionRecord>, val partial: Boolean)

private fun RuntimeConnectionRecord.withoutProcessInfo(): RuntimeConnectionRecord =
    copy(packageNames = emptyList(), sharedUidCandidates = emptyList(), uid = null)
