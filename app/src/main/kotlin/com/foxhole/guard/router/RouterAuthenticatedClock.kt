package com.foxhole.guard.router

internal class RouterAuthenticatedClock {
    private data class Basis(val uptime: Long, val receivedAt: Long)
    private var basis: Basis? = null

    fun clear() { basis = null }

    fun observe(uptime: Long, requestedAt: Long, receivedAt: Long): Boolean {
        require(uptime in 0..9007199254740991L && requestedAt >= 0 && receivedAt >= requestedAt && receivedAt - requestedAt <= 5000)
        val old = basis
        val rebooted = old != null && (receivedAt < old.receivedAt || uptime < old.uptime)
        basis = Basis(uptime, receivedAt)
        return rebooted
    }

    fun estimate(now: Long): Long? {
        val observed = basis ?: return null
        if (now < observed.receivedAt || now - observed.receivedAt > 5000) return null
        val elapsed = now - observed.receivedAt
        return observed.uptime.takeIf { it <= 9007199254740991L - elapsed }?.plus(elapsed)
    }
}
