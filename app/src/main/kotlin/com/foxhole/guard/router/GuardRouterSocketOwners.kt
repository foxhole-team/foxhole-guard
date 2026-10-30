package com.foxhole.guard.router

import java.net.Socket
import java.util.WeakHashMap

internal class GuardRouterSocketOwnerSnapshot(
    val runtime: Any,
    val tunnelOwner: Any,
    val serviceGeneration: Long,
    val runtimeGeneration: Long,
    val commandSequence: Long,
    val commandOwner: Long,
) {
    fun sameOwner(other: GuardRouterSocketOwnerSnapshot): Boolean = runtime === other.runtime && tunnelOwner === other.tunnelOwner &&
        serviceGeneration == other.serviceGeneration && runtimeGeneration == other.runtimeGeneration &&
        commandSequence == other.commandSequence && commandOwner == other.commandOwner
}

internal class GuardRouterSocketOwnerRegistry {
    private class Owner(val identity: Any, val capture: () -> GuardRouterSocketOwnerSnapshot?, val protect: (Socket) -> Boolean)
    private val lock = Any()
    private var current: Owner? = null
    private val protected = WeakHashMap<Socket, Pair<Owner, GuardRouterSocketOwnerSnapshot>>()

    fun register(identity: Any, capture: () -> GuardRouterSocketOwnerSnapshot?, protect: (Socket) -> Boolean) {
        synchronized(lock) { protected.clear(); current = Owner(identity, capture, protect) }
    }

    fun invalidate(identity: Any) {
        synchronized(lock) {
            if (current?.identity === identity) current = null
            protected.entries.removeAll { it.value.first.identity === identity }
        }
    }

    fun protect(socket: Socket): Boolean {
        val captured = synchronized(lock) {
            val owner = current ?: return false
            val snapshot = owner.capture() ?: return false
            owner to snapshot
        }
        if (!captured.first.protect(socket)) return false
        return synchronized(lock) {
            protected.entries.removeAll { it.key.isClosed }
            if (current !== captured.first || captured.first.capture()?.let(captured.second::sameOwner) != true || protected.size >= 128) false
            else { protected[socket] = captured; true }
        }
    }

    fun isCurrent(socket: Socket): Boolean = synchronized(lock) {
        val captured = protected[socket] ?: return false
        val valid = !socket.isClosed && current === captured.first && captured.first.capture()?.let(captured.second::sameOwner) == true
        if (!valid) protected.remove(socket)
        valid
    }
}

internal object GuardRouterSocketOwners {
    private val owners = GuardRouterSocketOwnerRegistry()
    fun register(identity: Any, capture: () -> GuardRouterSocketOwnerSnapshot?, protect: (Socket) -> Boolean) =
        owners.register(identity, capture, protect)
    fun invalidate(identity: Any) = owners.invalidate(identity)
    fun protect(socket: Socket): Boolean = owners.protect(socket)
    fun isCurrent(socket: Socket): Boolean = owners.isCurrent(socket)
}
