package com.foxhole.guard.router

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class RouterApiError { NETWORK, IDENTITY, PIN, CHANGE_PIN, LOCKED, CONFLICT, LIMIT, UNSUPPORTED, INSECURE_TLS, UNAVAILABLE, INVALID_RESPONSE, PIN_SETUP, LICENSE }
internal open class RouterApiFailure(val kind: RouterApiError) : IOException("router_api_${kind.name.lowercase()}")
internal class RouterApiTimeout : RouterApiFailure(RouterApiError.NETWORK)

private fun routerApiIoFailure(error: IOException): RouterApiFailure = when (error) {
    is RouterApiFailure -> error
    is javax.net.ssl.SSLException -> RouterApiFailure(RouterApiError.IDENTITY)
    is java.io.InterruptedIOException -> RouterApiTimeout()
    else -> RouterApiFailure(RouterApiError.NETWORK)
}


internal class RouterPanelSession(token: String, val changePinRequired: Boolean,
    val licenseRequired: Boolean = false, val licenseVersion: Int = 0) : AutoCloseable {
    private val secret = token.toCharArray()
    init { require(token.matches(Regex("(p1\\.)?[a-f0-9]{64}"))) }
    fun token(): String { check(secret.any { it != '\u0000' }); return secret.concatToString() }
    override fun close() { secret.fill('\u0000') }
    override fun toString(): String = "RouterPanelSession(changePinRequired=$changePinRequired)"
}

internal class RouterPanelApi internal constructor(val identity: RouterTlsIdentity, private val calls: Call.Factory,
    closeTransport: () -> Unit = {}, invalidateTransport: () -> Unit = {}) : AutoCloseable {
    var homeVpnBound: Boolean = false
        private set
    private var transportCheck: () -> Unit = {}
    fun requireTransportCurrent() { transportCheck() }
    private val closed = AtomicBoolean(false)
    private val cleanup = RouterTransportCleanup(invalidate = invalidateTransport, cleanup = closeTransport)
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val url = HttpUrl.Builder().scheme("https").host(identity.endpoint.address).port(identity.endpoint.port)
        .addPathSegments("cgi-bin/foxhole").build()

    suspend fun packageStatus(): RouterPackageStatus {
        val value = request("package.status", buildJsonObject {})
        val schema = (value["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val product = (value["product"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val installed = (value["installed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val configured = (value["pinConfigured"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val reset = (value["resetSupported"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (schema != 1L || product != "foxhole" || installed != true || configured == null || reset == null) {
            throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        }
        val version = (value["version"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return RouterPackageStatus(configured, reset, routerDiscoveryPackageVersion(version))
    }

    suspend fun beginDeviceUpdate(session: RouterPanelSession, expectedRevision: Long, requestId: String,
        release: RouterDeviceRelease): RouterDeviceUpload {
        require(requestId.matches(Regex("[a-f0-9]{32}")) && expectedRevision >= 0)
        val reply = pairedDeviceRequest("updates.deviceBegin", session, buildJsonObject {
            put("expectedRevision", expectedRevision); put("requestId", requestId)
            put("releaseTag", release.releaseTag); put("version", release.version)
            put("fileSha256", release.sha256); put("fileBytes", release.bytes)
        })
        reply.engineSchema()
        val upload = reply.engineText("uploadId").also(::requireRouterDeviceUploadId)
        val offset = reply.engineNumber("nextOffset")
        val chunk = reply.engineNumber("maxChunkBytes")
        if (offset > release.bytes || chunk != 65536L) invalidEngineResponse()
        return RouterDeviceUpload(upload, offset, chunk.toInt())
    }

    suspend fun sendDeviceUpdateChunk(session: RouterPanelSession, uploadId: String, offset: Long, bytes: ByteArray): Long {
        requireRouterDeviceUploadId(uploadId)
        require(offset >= 0 && bytes.size in 1..65536 && offset + bytes.size <= 8388608L)
        val reply = pairedDeviceRequest("updates.deviceChunk", session, buildJsonObject {
            put("uploadId", uploadId); put("offset", offset)
            put("dataBase64", java.util.Base64.getEncoder().encodeToString(bytes)); put("chunkSha256", routerReleaseSha256(bytes))
        })
        reply.engineSchema()
        return reply.engineNumber("nextOffset").also { if (it != offset + bytes.size) invalidEngineResponse() }
    }

    suspend fun deviceUpdateStatus(session: RouterPanelSession, uploadId: String): RouterDeviceUploadStatus {
        requireRouterDeviceUploadId(uploadId)
        val reply = pairedDeviceRequest("updates.deviceStatus", session, buildJsonObject {
            put("uploadId", uploadId)
        })
        reply.engineSchema()
        val offset = reply.engineNumber("nextOffset")
        if (offset > 8388608L) invalidEngineResponse()
        return RouterDeviceUploadStatus(offset, reply.engineBoolean("complete"))
    }

    suspend fun finishDeviceUpdate(session: RouterPanelSession, uploadId: String): RouterUpdateStatus {
        requireRouterDeviceUploadId(uploadId)
        return parseRouterUpdateStatus(pairedDeviceRequest("updates.deviceFinish", session, buildJsonObject {
            put("uploadId", uploadId)
        }))
    }

    private suspend fun pairedDeviceRequest(action: String, session: RouterPanelSession, payload: JsonObject): JsonObject {
        currentCoroutineContext().ensureActive()
        requireTransportCurrent()
        val value = request(action, JsonObject(payload + ("token" to JsonPrimitive(pairedDeviceToken(session)))))
        currentCoroutineContext().ensureActive()
        requireTransportCurrent()
        return value
    }

    private fun pairedDeviceToken(session: RouterPanelSession): String = session.token().also {
        require(!session.changePinRequired && it.matches(Regex("p1\\.[a-f0-9]{64}")))
    }

    suspend fun resetFoxHole(session: RouterPanelSession): RouterPanelSession {
        require(!session.changePinRequired)
        val current = state(session)
        val revision = (current["revision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val result = request("setup.reset", buildJsonObject {
            put("token", session.token()); put("revision", revision); put("confirm", true)
        })
        val reset = (result["reset"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val scope = (result["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val updated = (result["revision"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val schema = (result["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (schema != 1L || revision < 0 || reset != true || scope != "foxhole" || updated == null || updated <= revision) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val restricted = session(result, requireChangeFlag = true)
        if (!restricted.changePinRequired) { restricted.close(); throw RouterApiFailure(RouterApiError.INVALID_RESPONSE) }
        session.close()
        return restricted
    }

    suspend fun unlock(pin: CharArray): RouterPanelSession {
        try {
            requirePin(pin)
            val result = request("unlock", buildJsonObject { put("pin", pin.concatToString()) })
            return session(result, requireChangeFlag = true)
        } finally { pin.fill('\u0000') }
    }

    suspend fun changePin(session: RouterPanelSession, newPin: CharArray, licenseAccepted: Boolean = false,
        licenseVersion: Int = 0): RouterPanelSession {
        try {
            require(session.changePinRequired)
            requirePin(newPin)
            val license = routerPinLicenseFields(RouterLicenseRequirement(session.licenseRequired, session.licenseVersion),
                licenseAccepted, licenseVersion)
            val result = request("pin.change", buildJsonObject {
                put("token", session.token()); put("newPin", newPin.concatToString())
                license.forEach { (name, value) -> put(name, value) }
            })
            return session(result, requireChangeFlag = false).also { session.close() }
        } finally { newPin.fill('\u0000') }
    }

    suspend fun createPairing(session: RouterPanelSession, pairing: RouterPairing) {
        require(!session.changePinRequired && pairing.identity == identity)
        pairing.validate()
        val result = request("pair.create", buildJsonObject {
            put("token", session.token()); put("id", pairing.id); put("credential", credential(pairing)); put("name", pairing.name); put("client", "guard"); put("clientVersion", com.foxhole.guard.BuildConfig.VERSION_NAME)
        })
        if (result["id"]?.jsonPrimitive?.contentOrNull != pairing.id) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    }

    suspend fun unlockPairing(pairing: RouterPairing): RouterPanelSession {
        require(pairing.identity == identity)
        pairing.validate()
        val result = request("pair.unlock", buildJsonObject { put("id", pairing.id); put("credential", credential(pairing)); put("client", "guard"); put("clientVersion", com.foxhole.guard.BuildConfig.VERSION_NAME) })
        val token = result["token"]?.jsonPrimitive?.contentOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        if (!token.matches(Regex("p1\\.[a-f0-9]{64}"))) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        return RouterPanelSession(token, false)
    }

    suspend fun telemetry(session: RouterPanelSession): JsonObject = request("telemetry", buildJsonObject { put("token", session.token()) })

    suspend fun state(session: RouterPanelSession): JsonObject = request("state", buildJsonObject { put("token", session.token()) })

    suspend fun mobileRequest(action: RouterMobileAction, payload: JsonObject): JsonObject {
        val allowed = when (action) {
            RouterMobileAction.UPDATES_STATUS -> setOf("token")
            RouterMobileAction.UPDATES_CHECK, RouterMobileAction.UPDATES_PLAN -> setOf("token", "expectedRevision")
            RouterMobileAction.UPDATES_APPLY -> setOf("token", "planId", "planSha256", "expectedRevision", "confirmed")
            RouterMobileAction.UPDATES_JOB -> setOf("token", "jobId")
            RouterMobileAction.UPDATES_SETTINGS -> setOf("token", "expectedRevision", "autoUpdateEnabled")
            RouterMobileAction.ENGINES_STATUS -> setOf("token")
            RouterMobileAction.ENGINES_PLAN -> setOf("token", "operation", "engineIds", "storage", "expectedRevision", "catalogSha256")
            RouterMobileAction.ENGINES_APPLY -> setOf("token", "planId", "planSha256", "expectedRevision", "confirmed")
            RouterMobileAction.ENGINES_JOB -> setOf("token", "jobId")
            RouterMobileAction.PROFILE_TEST -> setOf("token", "config", "engine")
            RouterMobileAction.PROFILE_SAVE -> setOf("token", "revision", "config", "id", "name", "engine", "subscription")
            RouterMobileAction.PROFILE_GET -> setOf("token", "id")
            RouterMobileAction.DEVICE_LIST, RouterMobileAction.CLIENT_STATUS -> setOf("token")
            RouterMobileAction.PACKAGE_REMOVE -> setOf("token", "revision", "pairingId")
            RouterMobileAction.CLIENT_GET -> setOf("token", "id")
            RouterMobileAction.CLIENT_DELETE -> setOf("token", "revision", "id")
            RouterMobileAction.CLIENT_CREATE -> setOf("token", "revision", "name", "deviceType")
            RouterMobileAction.PROFILE_START, RouterMobileAction.PROFILE_DELETE -> setOf("token", "revision", "id")
            RouterMobileAction.PROFILE_STOP -> setOf("token", "revision")
            RouterMobileAction.SETTINGS -> setOf("token", "revision", "settings", "countries", "devices", "sites")
            RouterMobileAction.PHONE_PROVISION -> setOf("token", "name", "address", "createRemote", "registerPhone")
            RouterMobileAction.PHONE_STATUS -> setOf("token", "address")
            RouterMobileAction.PHONE_CAPABILITIES -> setOf("token")
            RouterMobileAction.PHONE_RESOLVE -> setOf("token", "address", "host")
            RouterMobileAction.PHONE_HANDOFF -> setOf("token", "address", "claimId", "mode", "leaseMs", "upstreamTargets", "expectedServerId", "expectedGeneration", "requestUptimeMs")
        }
        val token = (payload["token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        require(token?.matches(Regex("p1\\.[a-f0-9]{64}")) == true && payload.keys.all { it in allowed })
        payload["engine"]?.let { require((it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull in setOf("", "core")) }
        validateUpdateRequest(action, payload)
        validateEngineRequest(action, payload)
        return request(action.action, payload)
    }

    private fun validateUpdateRequest(action: RouterMobileAction, payload: JsonObject) {
        when (action) {
            RouterMobileAction.UPDATES_STATUS -> require(payload.keys == setOf("token"))
            RouterMobileAction.UPDATES_CHECK, RouterMobileAction.UPDATES_PLAN -> {
                require(payload.keys == setOf("token", "expectedRevision")); payload.engineNumber("expectedRevision")
            }
            RouterMobileAction.UPDATES_APPLY -> {
                require(payload.keys == setOf("token", "planId", "planSha256", "expectedRevision", "confirmed"))
                payload.updateId("planId", "plan"); payload.engineHash("planSha256"); payload.engineNumber("expectedRevision")
                require(payload.engineBoolean("confirmed"))
            }
            RouterMobileAction.UPDATES_JOB -> {
                require(payload.keys == setOf("token", "jobId")); payload.updateId("jobId", "job")
            }
            RouterMobileAction.UPDATES_SETTINGS -> {
                require(payload.keys == setOf("token", "expectedRevision", "autoUpdateEnabled"))
                payload.engineNumber("expectedRevision"); payload.engineBoolean("autoUpdateEnabled")
            }
            else -> Unit
        }
    }

    private fun validateEngineRequest(action: RouterMobileAction, payload: JsonObject) {
        when (action) {
            RouterMobileAction.ENGINES_STATUS -> require(payload.keys == setOf("token"))
            RouterMobileAction.ENGINES_JOB -> { require(payload.keys == setOf("token", "jobId")); payload.engineId("jobId") }
            RouterMobileAction.ENGINES_PLAN -> {
                require(payload.keys == setOf("token", "operation", "engineIds", "storage", "expectedRevision", "catalogSha256"))
                payload.engineEnum<RouterEngineOperation>("operation")
                val names = engineStrings(payload["engineIds"], 12)
                require(names.isNotEmpty() && names.distinct().size == names.size)
                names.forEach(::engineProtocol)
                require(payload.engineText("storage") in setOf("AUTO", "FLASH", "RAM"))
                payload.engineNumber("expectedRevision"); payload.engineHash("catalogSha256")
            }
            RouterMobileAction.ENGINES_APPLY -> {
                require(payload.keys == setOf("token", "planId", "planSha256", "expectedRevision", "confirmed"))
                payload.engineId("planId"); payload.engineHash("planSha256"); payload.engineNumber("expectedRevision")
                require(payload.engineBoolean("confirmed"))
            }
            else -> Unit
        }
    }

    suspend fun revoke(session: RouterPanelSession, pairing: RouterPairing) {
        require(pairing.identity == identity)
        request("pair.revoke", buildJsonObject { put("token", session.token()); put("id", pairing.id) })
    }

    suspend fun lock(session: RouterPanelSession) {
        try { request("lock", buildJsonObject { put("token", session.token()) }) } finally { session.close() }
    }

    private fun session(result: JsonObject, requireChangeFlag: Boolean): RouterPanelSession {
        val token = (result["token"] as? JsonPrimitive)?.contentOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        if (!token.matches(Regex("[a-f0-9]{64}"))) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val required = (result["pinChangeRequired"] as? JsonPrimitive)?.booleanOrNull
        if (requireChangeFlag && required == null) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val license = try { routerLicenseRequirement(result) }
            catch (_: IllegalArgumentException) { throw RouterApiFailure(RouterApiError.INVALID_RESPONSE) }
        if (license.required && required != true) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        return RouterPanelSession(token, required == true, license.required, license.version)
    }

    private suspend fun request(action: String, payload: JsonObject): JsonObject {
        check(!closed.get())
        val bytes = buildJsonObject { put("action", action); put("payload", payload) }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 300000)
        try {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("Cache-Control", "no-store").post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            return try {
                withTimeout(30_000L) {
                    suspendCancellableCoroutine { continuation ->
                        val call = calls.newCall(request)
                        val cancellation = RouterTransportCleanup(cleanup = call::cancel)
                        continuation.invokeOnCancellation { cancellation.close() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(routerApiIoFailure(e))
                            }
                            override fun onResponse(call: Call, response: Response) {
                                if (!continuation.isActive) { response.close(); return }
                                try {
                                    val result = response.use { readResponse(it) { continuation.isActive && !closed.get() } }
                                    if (continuation.isActive) continuation.resume(result)
                                } catch (error: IOException) {
                                    if (continuation.isActive) continuation.resumeWithException(
                                        routerApiIoFailure(error))
                                } catch (_: SerializationException) {
                                    if (continuation.isActive) continuation.resumeWithException(
                                        RouterApiFailure(RouterApiError.INVALID_RESPONSE))
                                } catch (_: IllegalArgumentException) {
                                    if (continuation.isActive) continuation.resumeWithException(
                                        RouterApiFailure(RouterApiError.INVALID_RESPONSE))
                                }
                            }
                        })
                    }
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw RouterApiTimeout()
            }
        } finally { bytes.fill(0) }
    }

    private fun readResponse(response: Response, requestActive: () -> Boolean): JsonObject {
        if (!requestActive()) throw IOException("router_request_cancelled")
        if (response.code !in 200..299) throw RouterApiFailure(RouterApiError.UNAVAILABLE)
        if (response.body.contentType()?.subtype != "json" || response.body.contentLength() > 300000) {
            throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        }
        val data = response.body.byteStream().use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    if (!requestActive()) throw IOException("router_request_cancelled")
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (!requestActive()) throw IOException("router_request_cancelled")
                    if (output.size() + count > 300000) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
            } finally { buffer.fill(0) }
            output.toByteArray()
        }
        val result = try {
            if (!requestActive()) throw IOException("router_request_cancelled")
            requireBoundedRouterJson(data)
            json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
        }
        finally { data.fill(0) } ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
        val error = (result["_foxhole_error"] as? JsonPrimitive)?.contentOrNull
        if (error == "pin_cooldown") {
            val retry = (result["retryAfterSeconds"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?.takeIf { it in 1..300 } ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
            throw RouterPinCooldown(retry)
        }
        if (error?.startsWith("update_") == true) throw routerUpdateFailure(error)
        if (error?.startsWith("engine_") == true) throw routerEngineFailure(error)
        if (error != null) throw RouterApiFailure(when (error) {
            "unauthorized", "invalid_pin", "pin_mismatch", "pin_unchanged" -> RouterApiError.PIN
            "pin_required" -> RouterApiError.CHANGE_PIN
            "pin_setup_required", "luci_required" -> RouterApiError.PIN_SETUP
            "license_required" -> RouterApiError.LICENSE
            "locked" -> RouterApiError.LOCKED
            "pairing_conflict", "revision_conflict" -> RouterApiError.CONFLICT
            "pairing_limit" -> RouterApiError.LIMIT
            "unsupported_config", "invalid_config", "inbound_required" -> RouterApiError.UNSUPPORTED
            else -> RouterApiError.UNAVAILABLE
        })
        return result
    }

    companion object {
        fun forWifi(connection: RouterWifiConnection, identity: RouterTlsIdentity): RouterPanelApi {
            require(connection.endpoint == identity.endpoint)
            return forBoundConnection(identity, connection.socketFactory, connection::requireCurrent, homeVpnBound = false)
        }

        fun forDiscoveryWifi(connection: RouterWifiConnection, identity: RouterTlsIdentity): RouterPanelApi {
            require(connection.endpoint == identity.endpoint)
            return forBoundConnection(identity, connection.socketFactory, connection::requireCurrent,
                homeVpnBound = false, discovery = true)
        }

        fun forHomeVpn(connection: RouterHomeVpnConnection, identity: RouterTlsIdentity): RouterPanelApi {
            require(connection.endpoint == identity.endpoint)
            return forBoundConnection(identity, connection.socketFactory, connection::requireCurrent, homeVpnBound = true)
        }

        private fun forBoundConnection(identity: RouterTlsIdentity, sockets: javax.net.SocketFactory,
            requireCurrent: () -> Unit, homeVpnBound: Boolean, discovery: Boolean = false): RouterPanelApi {
            requireCurrent()
            val trust = RouterPinnedTls(identity)
            val ownedSockets = RouterOwnedSocketFactory(sockets)
            val client = OkHttpClient.Builder().socketFactory(ownedSockets)
                .sslSocketFactory(trust.socketFactory, trust)
                .hostnameVerifier { hostname, _ -> hostname == identity.endpoint.address }
                .dns(Dns { hostname ->
                    if (hostname != identity.endpoint.address) throw UnknownHostException()
                    listOf(identity.endpoint.socketAddress().address)
                }).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).connectTimeout(if (discovery) 2 else 15, TimeUnit.SECONDS)
                .readTimeout(if (discovery) 3 else 20, TimeUnit.SECONDS)
                .writeTimeout(if (discovery) 3 else 20, TimeUnit.SECONDS)
                .callTimeout(if (discovery) 4 else 30, TimeUnit.SECONDS)
                .addInterceptor { chain -> requireCurrent(); chain.proceed(chain.request()) }
                .addNetworkInterceptor { chain -> requireCurrent(); chain.proceed(chain.request()) }.build()
            return RouterPanelApi(identity, client, closeTransport = {
                ownedSockets.closeOwnedOnIo()
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }, invalidateTransport = ownedSockets::invalidate).also { it.homeVpnBound = homeVpnBound; it.transportCheck = requireCurrent }
        }
    }

    override fun close() { if (closed.compareAndSet(false, true)) cleanup.close() }
    suspend fun closeAndAwait() { close(); cleanup.closeAndAwait() }
}

private fun requirePin(pin: CharArray) { require(validRouterPin(pin)) }
private fun credential(pairing: RouterPairing): String = pairing.credential.joinToString("") { "%02x".format(it.toInt() and 255) }
