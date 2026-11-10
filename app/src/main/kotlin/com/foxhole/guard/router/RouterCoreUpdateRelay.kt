package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

internal class RouterCoreUpdateRelay(private val source: RouterCoreUpdateSource) {
    suspend fun prepare(api: RouterPanelApi, session: RouterPanelSession, initial: RouterUpdateStatus,
        progress: (Int?) -> Unit): RouterUpdateStatus = try {
        withTimeout(600000) { withContext(Dispatchers.IO) {
            require(initial.supported && initial.deviceDownloadRequired && !initial.busy &&
                initial.deviceDownloadKind == RouterUpdateCohortKind.CORE && initial.coreCohortRelay == 1)
            val release = source.latest()
            val requestId = UUID.randomUUID().toString().replace("-", "")
            var uploadId = ""
            var cohortSha: String? = null
            var next = release.index
            val completed = mutableSetOf<String>()
            repeat(16) {
                currentCoroutineContext().ensureActive()
                val current = next
                if (!completed.add(current.id)) invalidEngineResponse()
                val result = source.download(current, discoveryIndex = uploadId.isEmpty(), progress).use { download ->
                    val beginPayload = buildJsonObject {
                        put("cohortKind", "CORE"); put("assetId", current.id); put("uploadId", uploadId)
                        put("expectedRevision", initial.revision); put("requestId", requestId)
                        put("releaseTag", release.tag); put("version", release.version)
                        put("fileSha256", download.sha256); put("fileBytes", current.bytes)
                    }
                    var observed = request(api, session, "updates.deviceBegin", beginPayload, retry = true)
                    if (uploadId.isNotEmpty() && observed.uploadId != uploadId) invalidEngineResponse()
                    uploadId = observed.uploadId
                    requireReply(observed, uploadId, cohortSha)
                    if (observed.assetId != current.id || observed.phase !in setOf("RECEIVING", "VERIFYING") ||
                        observed.nextOffset > current.bytes || observed.nextOffset != current.bytes && observed.nextOffset % 65536 != 0L) invalidEngineResponse()
                    var offset = observed.nextOffset
                    RandomAccessFile(download.file, "r").use { file ->
                        file.seek(offset)
                        while (offset < current.bytes) {
                            currentCoroutineContext().ensureActive()
                            val bytes = ByteArray(minOf(65536, current.bytes - offset).toInt())
                            try {
                                file.readFully(bytes)
                                observed = sendChunk(api, session, uploadId, current.id, offset, bytes, cohortSha)
                                offset = observed.nextOffset
                            } finally { bytes.fill(0) }
                        }
                    }
                    if (verifyRouterCoreDownload(download.file, current, discoveryIndex = current.id == "catalog-index") != download.sha256)
                        invalidEngineResponse()
                    val payload = action(uploadId, current.id)
                    try { request(api, session, "updates.deviceFinish", payload) }
                    catch (failure: RouterApiFailure) {
                        if (failure.kind != RouterApiError.NETWORK) throw failure
                        val read = request(api, session, "updates.deviceStatus", payload)
                        requireReply(read, uploadId, cohortSha)
                        if (read.phase in setOf("STAGED", "WAITING")) read
                        else request(api, session, "updates.deviceFinish", payload)
                    }
                }
                requireReply(result, uploadId, cohortSha)
                if (result.assetId != null) invalidEngineResponse()
                if (cohortSha == null) cohortSha = result.cohortSha256
                if (result.phase == "STAGED") {
                    val status = result.status ?: invalidEngineResponse()
                    if (status.routerId != initial.routerId || status.currentVersion != initial.currentVersion ||
                        status.revision < initial.revision || status.installedModules != initial.installedModules ||
                        status.coreCohortSha256 != cohortSha || status.failureReason != null || status.deviceDownloadRequired ||
                        status.availability == RouterUpdateAvailability.AVAILABLE && status.availableVersion != initial.currentVersion ||
                        status.availability !in setOf(RouterUpdateAvailability.AVAILABLE, RouterUpdateAvailability.NOT_CHECKED) ||
                        status.availability == RouterUpdateAvailability.NOT_CHECKED && status.coreAvailability != RouterCoreUpdateAvailability.CURRENT)
                        invalidEngineResponse()
                    progress(100); return@withContext status
                }
                if (result.phase != "WAITING") invalidEngineResponse()
                next = result.requiredAssets.firstOrNull() ?: invalidEngineResponse()
                progress(null)
            }
            invalidEngineResponse()
        } }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive(); throw RouterUpdateFailure(RouterUpdateFailureReason.DOWNLOAD_FAILED)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: RouterUpdateFailure) { throw failure }
    catch (failure: RouterApiFailure) { throw failure }
    catch (_: IOException) { throw RouterUpdateFailure(RouterUpdateFailureReason.DOWNLOAD_FAILED) }
    catch (_: SerializationException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
    catch (_: IllegalArgumentException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }

    private fun action(uploadId: String, assetId: String) = buildJsonObject {
        put("cohortKind", "CORE"); put("uploadId", uploadId); put("assetId", assetId)
    }
    private fun requireReply(reply: RouterCoreUpload, uploadId: String, cohortSha: String?) {
        if (reply.uploadId != uploadId || cohortSha != null && reply.cohortSha256 != cohortSha) invalidEngineResponse()
    }
    private suspend fun request(api: RouterPanelApi, session: RouterPanelSession, method: String,
        payload: JsonObject, retry: Boolean = false): RouterCoreUpload = try {
        parseRouterCoreUpload(api.coreUpdateRequest(method, session, payload))
    } catch (failure: RouterApiFailure) {
        if (!retry || failure.kind != RouterApiError.NETWORK) throw failure
        parseRouterCoreUpload(api.coreUpdateRequest(method, session, payload))
    }
    private suspend fun sendChunk(api: RouterPanelApi, session: RouterPanelSession, uploadId: String,
        assetId: String, offset: Long, bytes: ByteArray, cohortSha: String?): RouterCoreUpload {
        val payload = buildJsonObject {
            put("cohortKind", "CORE"); put("uploadId", uploadId); put("assetId", assetId); put("offset", offset)
            put("dataBase64", java.util.Base64.getEncoder().encodeToString(bytes)); put("chunkSha256", routerReleaseSha256(bytes))
        }
        val end = offset + bytes.size
        repeat(2) { attempt ->
            val reply = try { request(api, session, "updates.deviceChunk", payload) }
            catch (failure: RouterApiFailure) {
                if (failure.kind != RouterApiError.NETWORK) throw failure
                val read = request(api, session, "updates.deviceStatus", action(uploadId, assetId))
                requireReply(read, uploadId, cohortSha)
                if (read.assetId != assetId || read.phase != "RECEIVING") invalidEngineResponse()
                if (read.nextOffset == end) return read
                if (read.nextOffset != offset) invalidEngineResponse()
                if (attempt == 1) throw failure
                null
            }
            if (reply != null) {
                requireReply(reply, uploadId, cohortSha)
                if (reply.assetId != assetId || reply.nextOffset != end || reply.phase != "RECEIVING") invalidEngineResponse()
                return reply
            }
        }
        invalidEngineResponse()
    }
}
