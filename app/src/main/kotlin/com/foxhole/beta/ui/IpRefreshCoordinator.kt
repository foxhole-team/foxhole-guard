package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot

internal class IpRefreshCoordinator {
    private var active: ActiveIpRefresh? = null
    private var nextToken: Long = 0L

    fun request(
        target: IpInfoRefreshTarget,
        reason: IpInfoRefreshReason,
        generation: Long,
    ): IpRefreshDecision {
        val current = active
        if (current != null && shouldCoalesce(current, target, reason, generation)) {
            return IpRefreshDecision.Coalesced(
                activeToken = current.token,
                activeReason = current.reason,
                target = current.target,
                generation = current.generation,
            )
        }
        val token = ++nextToken
        active =
            ActiveIpRefresh(
                token = token,
                target = target,
                reason = reason,
                generation = generation,
            )
        return IpRefreshDecision.Start(
            token = token,
            target = target,
            generation = generation,
            supersededActive = current != null,
        )
    }

    fun cancelAll(): Long {
        active = null
        nextToken += 1
        return nextToken
    }

    fun isCurrent(token: Long): Boolean =
        active?.token == token

    fun complete(token: Long) {
        if (active?.token == token) {
            active = null
        }
    }

    private fun shouldCoalesce(
        current: ActiveIpRefresh,
        target: IpInfoRefreshTarget,
        reason: IpInfoRefreshReason,
        generation: Long,
    ): Boolean =
        current.target == target &&
            current.generation == generation &&
            reason != IpInfoRefreshReason.MANUAL &&
            reason.priority <= current.reason.priority
}

internal sealed interface IpRefreshDecision {
    data class Start(
        val token: Long,
        val target: IpInfoRefreshTarget,
        val generation: Long,
        val supersededActive: Boolean,
    ) : IpRefreshDecision

    data class Coalesced(
        val activeToken: Long,
        val activeReason: IpInfoRefreshReason,
        val target: IpInfoRefreshTarget,
        val generation: Long,
    ) : IpRefreshDecision
}

private data class ActiveIpRefresh(
    val token: Long,
    val target: IpInfoRefreshTarget,
    val reason: IpInfoRefreshReason,
    val generation: Long,
)

internal fun ipInfoRefreshGenerationForSnapshot(snapshot: ConnectionSnapshot): Long =
    snapshot.lastChangeAt + snapshot.upstreamNetworkRevision

private val IpInfoRefreshReason.priority: Int
    get() =
        when (this) {
            IpInfoRefreshReason.MANUAL -> 100
            IpInfoRefreshReason.TOR_ROUTE -> 90
            IpInfoRefreshReason.NETWORK_CHANGE -> 80
            IpInfoRefreshReason.POST_CONNECT,
            IpInfoRefreshReason.RESTORED_VPN,
            -> 70
            IpInfoRefreshReason.FOREGROUND,
            IpInfoRefreshReason.POST_UPDATE,
            -> 10
        }
