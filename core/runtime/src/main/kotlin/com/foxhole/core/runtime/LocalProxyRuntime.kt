package com.foxhole.core.runtime

import com.foxhole.core.model.LocalProxyPhase
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.LocalProxyUpstream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The device-local proxy: the scenario where the VPN serves this phone instead of capturing it.
 *
 * Everything here mirrors the LAN surface deliberately — a request the app computes, a controller
 * that applies it to the live session, and a status document read back from the core — because the
 * two answer the same question in different places, and one of them has already been through this.
 * What differs is what the core allows: this listener binds `127.0.0.1`, speaks HTTP CONNECT only,
 * and may be raised without credentials, which the LAN one may never be.
 *
 * The name is fixed rather than user-supplied. Named inbounds exist so several web apps can each
 * have their own; the scenario here is the single proxy the owner points other apps at, and giving
 * it a stable name is what lets the status document be found again after a restart.
 */
const val LOCAL_PROXY_INBOUND_NAME = "device-local"

/** What the app asks for. Absent credentials mean an anonymous listener — allowed on loopback. */
data class LocalProxyRequest(
    val port: Int,
    val username: String?,
    val password: String?,
    val upstream: LocalProxyUpstream,
) {
    /**
     * The document the core parses. `http_port` 0 asks for an ephemeral port, and that is the
     * recommended form: a phone has no port registry, so a fixed number is a coin flip against
     * every other app on the device.
     *
     * Credentials are written as a pair or not at all — half a credential is a refusal in the core,
     * and building one here would turn an abandoned form into an error the user cannot read.
     */
    fun toConfigJson(): String = toConfigJson(LOCAL_PROXY_INBOUND_NAME)

    internal fun toConfigJson(inboundName: String): String {
        val document = buildJsonObject {
            put("name", inboundName)
            put("http_port", port.coerceIn(0, MAX_PORT))
            val user = username?.trim().orEmpty()
            val secret = password.orEmpty()
            if (user.isNotEmpty() && secret.isNotEmpty()) {
                put("username", user)
                put("password", secret)
            }
            put("upstream", upstream.wireName)
        }
        return json.encodeToString(JsonObject.serializer(), document)
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}

/**
 * Reads the core's account of its named inbounds and answers for ours alone.
 *
 * A document that cannot be parsed is not an error state: the engine may simply be stopped, and the
 * caller already distinguishes "no session" from "refused". What must never happen is inventing an
 * address — the port may have been ephemeral, and a guessed one sends the owner's other app at
 * whatever else is listening.
 */
internal fun parseLocalProxyStatusJson(
    raw: String,
    now: Long,
    inboundName: String = LOCAL_PROXY_INBOUND_NAME,
): LocalProxyStatusSnapshot? {
    val document = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val inbounds = runCatching { document["inbounds"]?.jsonArray }.getOrNull() ?: return null
    val ours = inbounds
        .asSequence()
        .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
        .firstOrNull { entry -> entry.text("name") == inboundName }
        ?: return LocalProxyStatusSnapshot(phase = LocalProxyPhase.OFF, updatedAt = now)
    val address = ours.text("http_address")
    // FoxCore serializes `LanProxyState::Ready` as `ready` for both LAN and named loopback
    // inbounds. Keep this parser on that public wire vocabulary: accepting an invented
    // `listening` value left a successfully bound Tor identity probe permanently in ARMING.
    val serving = ours.text("state").equals("ready", ignoreCase = true) && address != null
    return LocalProxyStatusSnapshot(
        phase = if (serving) LocalProxyPhase.SERVING else LocalProxyPhase.ARMING,
        address = address,
        upstream = LocalProxyUpstream.fromWireName(ours.text("upstream")),
        updatedAt = now,
    )
}

/**
 * Owns the device-local proxy across a session.
 *
 * Remembers what it applied so an unchanged request does not re-bind the listener on every settings
 * write, and reads the state back from the core rather than assuming its own call worked — a start
 * that returned OK still has to say which port it got.
 */
internal class LocalProxyController(
    private val native: FoxCoreNativeApi,
    private val inboundName: String = LOCAL_PROXY_INBOUND_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var applied: LocalProxyRequest? = null

    @Volatile
    private var lastStatus: LocalProxyStatusSnapshot = LocalProxyStatusSnapshot()

    fun status(): LocalProxyStatusSnapshot = lastStatus

    /**
     * Brings the listener in line with [request]; a null request takes it down.
     *
     * A null [handle] is not a failure either: the switch is on and there is no session yet, which
     * is exactly what ARMING means and what the screen must show instead of a port.
     */
    fun sync(
        handle: Long?,
        request: LocalProxyRequest?,
    ): LocalProxyStatusSnapshot {
        if (request == null) {
            stopIfApplied(handle)
            return publish(LocalProxyStatusSnapshot(phase = LocalProxyPhase.OFF, updatedAt = clock()))
        }
        if (handle == null) {
            applied = null
            return publish(
                LocalProxyStatusSnapshot(
                    phase = LocalProxyPhase.ARMING,
                    upstream = request.upstream,
                    updatedAt = clock(),
                ),
            )
        }
        if (applied != request) {
            // Stop first: the core refuses a second inbound under a name it already holds, and a
            // silent AlreadyExists would leave the previous port serving the previous upstream.
            stopIfApplied(handle)
            val code = native.startLoopbackInbound(handle, request.toConfigJson(inboundName))
            if (code != RESULT_OK) {
                applied = null
                return publish(
                    LocalProxyStatusSnapshot(
                        phase = LocalProxyPhase.UNAVAILABLE,
                        upstream = request.upstream,
                        updatedAt = clock(),
                    ),
                )
            }
            applied = request
        }
        val reported = parseLocalProxyStatusJson(
            raw = native.loopbackInbounds(handle),
            now = clock(),
            inboundName = inboundName,
        )
        return publish(
            reported ?: LocalProxyStatusSnapshot(
                phase = LocalProxyPhase.ARMING,
                upstream = request.upstream,
                updatedAt = clock(),
            ),
        )
    }

    private fun stopIfApplied(handle: Long?) {
        if (applied == null) {
            return
        }
        applied = null
        if (handle != null) {
            native.stopLoopbackInbound(handle, inboundName)
        }
    }

    private fun publish(snapshot: LocalProxyStatusSnapshot): LocalProxyStatusSnapshot {
        lastStatus = snapshot
        return snapshot
    }

    private companion object {
        const val RESULT_OK = 0
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
