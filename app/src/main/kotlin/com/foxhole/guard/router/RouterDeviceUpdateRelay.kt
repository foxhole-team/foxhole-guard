package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

internal data class RouterDeviceUpload(val uploadId: String, val nextOffset: Long, val maxChunkBytes: Int)
internal data class RouterDeviceUploadStatus(val nextOffset: Long, val complete: Boolean)
internal fun requireRouterDeviceUploadId(value: String) {
    if (!value.matches(Regex("update-upload-[a-f0-9]{32}"))) invalidEngineResponse()
}

internal class RouterDeviceUpdateRelay(private val source: RouterDeviceReleaseSource) {
    suspend fun prepare(api: RouterPanelApi, session: RouterPanelSession, status: RouterUpdateStatus,
        progress: (Int?) -> Unit): RouterUpdateStatus? = try {
        withTimeout(180000) { withContext(Dispatchers.IO) {
            require(status.supported && status.deviceDownloadRequired && !status.busy)
            val current = status.currentVersion ?: invalidEngineResponse()
            val downloaded = try { source.downloadNewer(current, progress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RouterUpdateFailure) { throw failure }
            catch (_: RouterApiFailure) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
            catch (_: IOException) { throw RouterUpdateFailure(RouterUpdateFailureReason.DOWNLOAD_FAILED) }
            catch (_: SerializationException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
            catch (_: IllegalArgumentException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
                ?: return@withContext null
            downloaded.use { bundle ->
                val release = bundle.release
                val requestId = UUID.randomUUID().toString().replace("-", "")
                val upload = try { api.beginDeviceUpdate(session, status.revision, requestId, release) }
                catch (failure: RouterApiFailure) {
                    if (failure.kind != RouterApiError.NETWORK) throw failure
                    api.beginDeviceUpdate(session, status.revision, requestId, release)
                }
                if (upload.nextOffset != release.bytes && upload.nextOffset % upload.maxChunkBytes != 0L) invalidEngineResponse()
                var offset = upload.nextOffset
                RandomAccessFile(bundle.file, "r").use { file ->
                    file.seek(offset)
                    while (offset < release.bytes) {
                        currentCoroutineContext().ensureActive()
                        val bytes = ByteArray(minOf(upload.maxChunkBytes.toLong(), release.bytes - offset).toInt())
                        try {
                            file.readFully(bytes)
                            offset = sendChunk(api, session, upload.uploadId, offset, bytes, release.bytes)
                            progress((60 + offset * 35 / release.bytes).toInt().coerceIn(60, 95))
                        } finally { bytes.fill(0) }
                    }
                }
                try { verifyRouterDevicePackage(bundle.file, release) }
                catch (_: IOException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
                catch (_: IllegalArgumentException) { throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED) }
                progress(95)
                val result = try { api.finishDeviceUpdate(session, upload.uploadId) }
                catch (failure: RouterApiFailure) {
                    if (failure.kind != RouterApiError.NETWORK) throw failure
                    val observed = parseRouterUpdateStatus(api.mobileRequest(RouterMobileAction.UPDATES_STATUS,
                        buildJsonObject { put("token", session.token()) }))
                    if (observed.availability != RouterUpdateAvailability.AVAILABLE || observed.availableVersion != release.version) throw failure
                    observed
                }
                if (result.routerId != status.routerId || result.revision < status.revision || result.currentVersion != current ||
                    result.availability != RouterUpdateAvailability.AVAILABLE || result.availableVersion != release.version ||
                    result.failureReason != null || result.deviceDownloadRequired) invalidEngineResponse()
                progress(100)
                result
            }
        } }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw RouterUpdateFailure(RouterUpdateFailureReason.DOWNLOAD_FAILED)
    }

    private suspend fun sendChunk(api: RouterPanelApi, session: RouterPanelSession, uploadId: String,
        offset: Long, bytes: ByteArray, totalBytes: Long): Long {
        val end = offset + bytes.size
        repeat(2) { attempt ->
            try { return api.sendDeviceUpdateChunk(session, uploadId, offset, bytes) }
            catch (failure: RouterApiFailure) {
                if (failure.kind != RouterApiError.NETWORK) throw failure
                val observed = api.deviceUpdateStatus(session, uploadId)
                if (observed.complete && observed.nextOffset != totalBytes) invalidEngineResponse()
                if (observed.nextOffset == end) return end
                if (observed.nextOffset != offset) invalidEngineResponse()
                if (attempt == 1) throw failure
            }
        }
        invalidEngineResponse()
    }
}
