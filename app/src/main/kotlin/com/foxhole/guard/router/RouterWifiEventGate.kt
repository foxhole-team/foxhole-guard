package com.foxhole.guard.router

internal data class RouterWifiObservation(val usable: Boolean, val internet: Boolean, val validated: Boolean,
    val interfaceName: String?, val addresses: Set<String>, val routes: Set<String>)

internal class RouterWifiEventGate {
    private val seen = mutableMapOf<Long, RouterWifiObservation>()
    @Synchronized fun changed(handle: Long, observation: RouterWifiObservation): Boolean {
        if (seen[handle] == observation) return false
        seen[handle] = observation
        return true
    }
    @Synchronized fun lost(handle: Long): Boolean = seen.remove(handle) != null
}
