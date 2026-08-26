package com.foxhole.core.importer

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.isPrivateOrLocalAddress
import java.net.InetAddress

internal fun memoizedPublicHostResolver(
    delegate: RemoteHostResolver,
    ttlMs: Long = RESOLVER_CACHE_TTL_MS,
    maxEntries: Int = RESOLVER_CACHE_MAX_ENTRIES,
    now: () -> Long = System::currentTimeMillis,
): RemoteHostResolver {
    val cache = LinkedHashMap<String, CachedHostResolution>()
    val lock = Any()
    return { host ->
        val cached =
            synchronized(lock) {
                val entry = cache[host]
                when {
                    entry == null -> null
                    now() - entry.resolvedAtMs > ttlMs -> {
                        cache.remove(host)
                        null
                    }
                    else -> entry.addresses
                }
            }
        cached
            ?: delegate(host).also { resolved ->
                if (resolved.isNotEmpty() && resolved.none(InetAddress::isPrivateOrLocalAddress)) {
                    synchronized(lock) {
                        cache[host] = CachedHostResolution(resolved, now())
                        while (cache.size > maxEntries) {
                            cache.remove(cache.keys.first())
                        }
                    }
                }
            }
    }
}

internal val systemRemoteHostResolver: RemoteHostResolver = { host ->
    InetAddress.getAllByName(host).toList()
}

private data class CachedHostResolution(
    val addresses: List<InetAddress>,
    val resolvedAtMs: Long,
)

private const val RESOLVER_CACHE_TTL_MS = 30_000L
private const val RESOLVER_CACHE_MAX_ENTRIES = 256
