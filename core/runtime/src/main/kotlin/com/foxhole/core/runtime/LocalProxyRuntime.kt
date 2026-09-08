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

const val LOCAL_PROXY_INBOUND_NAME = "device-local"

data class LocalProxyRequest(
    val port: Int,
    val username: String?,
    val password: String?,
    val upstream: LocalProxyUpstream,
    val allowAnonymous: Boolean = false,
) {
    fun toConfigJson(): String = toConfigJson(LOCAL_PROXY_INBOUND_NAME)

    internal fun toConfigJson(inboundName: String): String {
        val document = buildJsonObject {
            put("name", inboundName)
            put("http_port", port.coerceIn(0, MAX_PORT))
            put("allow_anonymous", allowAnonymous)
            val user = username?.trim().orEmpty()
            val secret = password.orEmpty()
            if (username != null) {
                put("username", user)
            }
            if (password != null) {
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

    val serving = ours.text("state").equals("ready", ignoreCase = true) && address != null
    return LocalProxyStatusSnapshot(
        phase = if (serving) LocalProxyPhase.SERVING else LocalProxyPhase.ARMING,
        address = address,
        upstream = LocalProxyUpstream.fromWireName(ours.text("upstream")),
        updatedAt = now,
    )
}

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
