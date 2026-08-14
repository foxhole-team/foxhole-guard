package com.foxhole.core.runtime

import com.foxhole.core.model.LocalProxyPhase
import com.foxhole.core.model.LocalProxyUpstream
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.ProxyAccessType
import java.security.SecureRandom
import java.util.Base64

/** Identity of the exact service-owned runtime generation allowed to use the private probe. */
data class TorProbeProxyOwner(
    val sessionId: String,
    val runtimeGeneration: Long,
)

/**
 * Read-only, in-memory access to the authenticated Tor identity probe. Nothing here is part of
 * Settings, backup or any user/LAN proxy surface.
 */
data class TorProbeProxyLease(
    val owner: TorProbeProxyOwner,
    val access: HttpProxyAccess,
)

/** Typed failure for the same owner fence, without exposing listener address or credentials. */
data class TorProbeProxyIssue(
    val owner: TorProbeProxyOwner,
    val failure: TorProbeProxyFailure,
)

enum class TorProbeProxyFailure {
    NO_RUNTIME,
    START_REFUSED,
    NOT_READY,
    INVALID_LOOPBACK_ADDRESS,
    STALE_GENERATION,
}

class TorProbeProxyUnavailableException(
    val failure: TorProbeProxyFailure,
) : IllegalStateException("Tor identity probe unavailable: ${failure.name.lowercase()}")

/** Post-fetch fence: a result from a listener that was replaced during I/O is never publishable. */
internal fun requireCurrentTorProbeLease(
    started: TorProbeProxyLease,
    current: TorProbeProxyLease?,
): TorProbeProxyLease {
    if (current?.owner != started.owner || current.access != started.access) {
        throw TorProbeProxyUnavailableException(TorProbeProxyFailure.STALE_GENERATION)
    }
    return current
}

/** Dedicated inbound name: intentionally unrelated to the user-visible device-local proxy. */
internal const val TOR_PROBE_INBOUND_NAME = "tor-identity-probe"

/**
 * Owns one authenticated 127.0.0.1 HTTP CONNECT inbound for Tor identity lookups.
 *
 * Credentials are minted once per [TorProbeProxyOwner], retained only in this runtime object and
 * dropped with the owner. Repeated sync passes query status but never rebind an unchanged inbound.
 */
internal class TorProbeProxyController(
    native: FoxCoreNativeApi,
    clock: () -> Long = System::currentTimeMillis,
    private val credentialFactory: () -> Pair<String, String> = ::newTorProbeCredentials,
) {
    private val localProxy = LocalProxyController(
        native = native,
        clock = clock,
        inboundName = TOR_PROBE_INBOUND_NAME,
    )
    private val lock = Any()
    private var owner: TorProbeProxyOwner? = null
    private var request: LocalProxyRequest? = null
    private var lease: TorProbeProxyLease? = null
    private var issue: TorProbeProxyIssue? = null

    fun sync(
        handle: Long?,
        requestedOwner: TorProbeProxyOwner?,
    ): Result<TorProbeProxyLease?> = synchronized(lock) {
        if (requestedOwner == null) {
            val stopped = runCatching { localProxy.sync(handle = handle, request = null) }
            clearMemory()
            return@synchronized if (stopped.isSuccess) {
                Result.success(null)
            } else {
                failure(TorProbeProxyFailure.START_REFUSED)
            }
        }
        if (handle == null) {
            runCatching { localProxy.sync(handle = null, request = null) }
            clearMemory()
            return@synchronized ownerFailure(requestedOwner, TorProbeProxyFailure.NO_RUNTIME)
        }
        if (owner != requestedOwner || request == null) {
            runCatching { localProxy.sync(handle = handle, request = null) }
            val (username, password) = credentialFactory()
            owner = requestedOwner
            request = LocalProxyRequest(
                port = 0,
                username = username,
                password = password,
                upstream = LocalProxyUpstream.TOR,
            )
            lease = null
            issue = null
        }
        val activeRequest = requireNotNull(request)
        val status = runCatching {
            localProxy.sync(handle = handle, request = activeRequest)
        }.getOrElse {
            return@synchronized ownerFailure(requestedOwner, TorProbeProxyFailure.START_REFUSED)
        }
        if (status.phase != LocalProxyPhase.SERVING) {
            lease = null
            return@synchronized ownerFailure(
                requestedOwner,
                if (status.phase == LocalProxyPhase.UNAVAILABLE) {
                    TorProbeProxyFailure.START_REFUSED
                } else {
                    TorProbeProxyFailure.NOT_READY
                },
            )
        }
        val address = status.address?.toStrictLoopbackAddress()
        if (address == null) {
            // A native report outside 127.0.0.1 is a security boundary failure, not a display-only
            // mismatch. Tear the listener down immediately so a broken implementation can never
            // leave the private credentials reachable from LAN while the ticker keeps retrying.
            runCatching { localProxy.sync(handle = handle, request = null) }
            clearMemory()
            return@synchronized ownerFailure(requestedOwner, TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS)
        }
        val activeOwner = requireNotNull(owner)
        val next = TorProbeProxyLease(
            owner = activeOwner,
            access = HttpProxyAccess(
                host = address.first,
                port = address.second,
                username = activeRequest.username,
                password = activeRequest.password,
                type = ProxyAccessType.HTTP,
            ),
        )
        lease = next
        issue = null
        Result.success(next)
    }

    fun currentLease(): TorProbeProxyLease? = synchronized(lock) { lease }

    fun currentIssue(): TorProbeProxyIssue? = synchronized(lock) { issue }

    /** Compare-and-close for an asynchronous transition teardown; a successor is never touched. */
    fun release(
        handle: Long?,
        expectedOwner: TorProbeProxyOwner,
    ): Result<Boolean> = synchronized(lock) {
        if (owner != expectedOwner) return@synchronized Result.success(false)
        val stopped = runCatching { localProxy.sync(handle = handle, request = null) }
        clearMemory()
        if (stopped.isSuccess) Result.success(true) else failure(TorProbeProxyFailure.START_REFUSED)
    }

    private fun clearMemory() {
        owner = null
        request = null
        lease = null
        issue = null
    }

    private fun <T> failure(reason: TorProbeProxyFailure): Result<T> {
        lease = null
        return Result.failure(TorProbeProxyUnavailableException(reason))
    }

    private fun <T> ownerFailure(
        failedOwner: TorProbeProxyOwner,
        reason: TorProbeProxyFailure,
    ): Result<T> {
        lease = null
        issue = TorProbeProxyIssue(owner = failedOwner, failure = reason)
        return Result.failure(TorProbeProxyUnavailableException(reason))
    }
}

private fun String.toStrictLoopbackAddress(): Pair<String, Int>? {
    val host = substringBeforeLast(':').trim()
    val port = substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    if (host != "127.0.0.1" || port == null || port !in 1..65535) return null
    return host to port
}

private fun newTorProbeCredentials(): Pair<String, String> =
    secureToken(TOR_PROBE_USERNAME_BYTES) to secureToken(TOR_PROBE_PASSWORD_BYTES)

private fun secureToken(size: Int): String {
    val bytes = ByteArray(size)
    TOR_PROBE_RANDOM.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val TOR_PROBE_RANDOM = SecureRandom()
private const val TOR_PROBE_USERNAME_BYTES = 12
private const val TOR_PROBE_PASSWORD_BYTES = 24
