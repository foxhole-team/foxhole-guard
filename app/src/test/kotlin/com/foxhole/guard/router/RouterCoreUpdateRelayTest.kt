package com.foxhole.guard.router

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RouterCoreUpdateRelayTest {
    @Test fun cancelledDownloadCancelsHttpAndRemovesItsPrivateTemporaryFiles() = runBlocking {
        val cache = Files.createTempDirectory("core-cancel-test-").toFile()
        try {
            val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
            val http = RouterDeviceReleaseHttp(Call.Factory { request ->
                object : Call by OkHttpClient().newCall(request) {
                    override fun enqueue(responseCallback: Callback) { started.complete(Unit) }
                    override fun cancel() { cancelled.complete(Unit) }
                }
            })
            val asset = RouterCoreAsset("asset-${"b".repeat(64)}", "b".repeat(64), 10,
                "https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/module.apk", RouterCoreAssetKind.PACKAGE)
            val job = async { RouterCoreUpdateSource(cache, http).download(asset) { } }
            started.await(); job.cancelAndJoin(); cancelled.await()
            assertEquals(emptyList<String>(), cache.listFiles()!!.map { it.name })
        } finally { cache.deleteRecursively() }
    }
    @Test fun digestMismatchNeverLeavesADownloadedAssetForADeviceUpload() = runBlocking {
        val cache = Files.createTempDirectory("core-hash-test-").toFile()
        try {
            val bytes = "ADBdwrong-module".toByteArray()
            val http = RouterDeviceReleaseHttp(Call.Factory { request ->
                object : Call by OkHttpClient().newCall(request) {
                    override fun enqueue(responseCallback: Callback) {
                        responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                            .message("fixture").body(bytes.toResponseBody()).build())
                    }
                }
            })
            val asset = RouterCoreAsset("asset-${"b".repeat(64)}", "b".repeat(64), bytes.size.toLong(),
                "https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/module.apk", RouterCoreAssetKind.PACKAGE)
            try { RouterCoreUpdateSource(cache, http).download(asset) { }; fail("tampered module accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(emptyList<String>(), cache.listFiles()!!.map { it.name })
        } finally { cache.deleteRecursively() }
    }
    @Test fun signedModuleCohortStagesWithoutChangingBaseOrAutomaticallyApplying() = runBlocking {
        fixture(available = true, lostFinish = false)
    }
    @Test fun lostFinalAcknowledgementUsesReadOnlyStatusAndCoreCurrentStaysPartial() = runBlocking {
        fixture(available = false, lostFinish = true)
    }
    @Test fun coreRequestRequiresPairedTokenAndFiniteUpdateActionBeforeTransport() = runBlocking {
        val f = EngineApiFixture(); val api = f.api()
        val payload = buildJsonObject { put("cohortKind", "CORE"); put("assetId", "catalog-index") }
        try { api.coreUpdateRequest("updates.apply", RouterPanelSession(EngineApiFixture.TOKEN, false), payload); fail("apply accepted") }
        catch (_: IllegalArgumentException) { }
        try { api.coreUpdateRequest("updates.deviceStatus", RouterPanelSession("b".repeat(64), false), payload); fail("admin token accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(f.actions.isEmpty())
    }
    private suspend fun fixture(available: Boolean, lostFinish: Boolean) {
        val cache = Files.createTempDirectory("core-relay-test-").toFile()
        try {
            val base = "https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/"
            val index = "ADBdtest-index".toByteArray(); val metadata = "ADBdtest-metadata".toByteArray()
            val module = "ADBdtest-module".toByteArray(); val cohort = "c".repeat(64)
            val moduleId = "asset-" + routerReleaseSha256(module)
            val files = mapOf("catalog-index" to index, "catalog-apk" to metadata, moduleId to module)
            val urls = mapOf("catalog-index" to base + "core-catalog.adb", "catalog-apk" to base + "foxcore-update-catalog-1.0.4-r1.apk",
                moduleId to base + "foxcore-engine-vless-1.0.4-r1.apk")
            val downloaded = mutableListOf<String>()
            val metadataJson = """{"draft":false,"prerelease":false,"tag_name":"v1.0.4","assets":[{"name":"core-catalog.adb",
                "size":${index.size},"browser_download_url":"${urls["catalog-index"]}"}]}""".toByteArray()
            val http = RouterDeviceReleaseHttp(Call.Factory { request ->
                object : Call by OkHttpClient().newCall(request) {
                    override fun enqueue(responseCallback: Callback) {
                        val bytes = if (request.url.toString() == ROUTER_CORE_RELEASE_METADATA) metadataJson else {
                            val id = urls.entries.single { it.value == request.url.toString() }.key
                            downloaded += id; files.getValue(id)
                        }
                        responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(bytes.toResponseBody()).build())
                    }
                }
            })
            val initialDoc = UpdateDocuments.status() plusField ("currentVersion" to JsonPrimitive("1.0.6-r1")) plusField
                ("availability" to JsonPrimitive("CHECK_FAILED")) plusField ("availableVersion" to JsonNull) plusField
                ("failureReason" to JsonPrimitive("DOWNLOAD_FAILED")) plusField ("deviceDownloadRequired" to JsonPrimitive(true)) plusField
                ("deviceDownloadKind" to JsonPrimitive("CORE")) plusField ("coreCohortRelay" to JsonPrimitive(1))
            val initial = parseRouterUpdateStatus(initialDoc)
            val staged = initialDoc plusField ("revision" to JsonPrimitive(5)) plusField
                ("availability" to JsonPrimitive(if (available) "AVAILABLE" else "NOT_CHECKED")) plusField
                ("availableVersion" to if (available) JsonPrimitive("1.0.6-r1") else JsonNull) plusField
                ("coreAvailability" to JsonPrimitive(if (available) "AVAILABLE" else "CURRENT")) plusField
                ("coreCohortSha256" to JsonPrimitive(cohort)) plusField ("failureReason" to JsonNull) plusField
                ("deviceDownloadRequired" to JsonPrimitive(false))
            val f = EngineApiFixture(); val completed = mutableSetOf<String>(); val received = mutableMapOf<String, ByteArray>()
            var active: String? = null; var phase = "WAITING"; var coreKnown = false; var lost = false
            val upload = "update-upload-" + "a".repeat(32)
            fun required(): List<String> = when {
                "catalog-index" !in completed -> listOf("catalog-index")
                "catalog-apk" !in completed -> listOf("catalog-apk")
                available && moduleId !in completed -> listOf(moduleId)
                else -> emptyList()
            }
            fun reply() = buildJsonObject {
                put("schema", 1); put("cohortKind", "CORE"); put("uploadId", upload)
                put("assetId", active?.let(::JsonPrimitive) ?: JsonNull); put("nextOffset", active?.let { received[it]?.size ?: 0 } ?: 0)
                put("maxChunkBytes", 65536); put("phase", phase); put("cohortSha256", if (coreKnown) JsonPrimitive(cohort) else JsonNull)
                put("requiredAssets", JsonArray(required().map { id -> buildJsonObject {
                    put("id", id); put("sha256", if (id == "catalog-apk" && active == null) JsonNull else JsonPrimitive(routerReleaseSha256(files.getValue(id))))
                    put("bytes", files.getValue(id).size); put("url", urls.getValue(id));
                    put("kind", if (id == "catalog-index") "CATALOG_INDEX" else if (id == "catalog-apk") "CATALOG_APK" else "PACKAGE")
                } }))
                if (phase == "STAGED") put("status", staged)
            }
            f.reply = { action, payload ->
                assertEquals("CORE", payload["cohortKind"]!!.jsonPrimitive.content)
                val id = payload["assetId"]!!.jsonPrimitive.content
                when (action) {
                    "updates.deviceBegin" -> {
                        assertEquals("1.0.4", payload["version"]!!.jsonPrimitive.content)
                        active = id; phase = "RECEIVING"; received[id] = byteArrayOf(); reply()
                    }
                    "updates.deviceChunk" -> {
                        assertEquals(active, id)
                        val bytes = java.util.Base64.getDecoder().decode(payload["dataBase64"]!!.jsonPrimitive.content)
                        assertEquals(routerReleaseSha256(bytes), payload["chunkSha256"]!!.jsonPrimitive.content)
                        received[id] = received.getValue(id) + bytes; reply()
                    }
                    "updates.deviceFinish" -> {
                        assertArrayEquals(files.getValue(id), received[id]); completed += id; active = null
                        if (id == "catalog-apk") coreKnown = true
                        phase = if (required().isEmpty()) "STAGED" else "WAITING"
                        if (phase == "STAGED" && lostFinish && !lost) { lost = true; throw RouterApiFailure(RouterApiError.NETWORK) }
                        reply()
                    }
                    "updates.deviceStatus" -> reply()
                    else -> error("unexpected cohort action: $action")
                }
            }
            val result = RouterCoreUpdateRelay(RouterCoreUpdateSource(cache, http)).prepare(f.api(),
                RouterPanelSession(EngineApiFixture.TOKEN, false), initial) { }
            assertEquals("1.0.6-r1", result.currentVersion)
            assertEquals(if (available) RouterUpdateAvailability.AVAILABLE else RouterUpdateAvailability.NOT_CHECKED, result.availability)
            assertEquals(cohort, result.coreCohortSha256); assertEquals(initial.installedModules, result.installedModules)
            assertFalse(f.actions.contains("updates.apply")); assertEquals(if (available) 3 else 2, downloaded.size)
            assertEquals(emptyList<String>(), cache.listFiles()!!.map { it.name })
            if (lostFinish) assertEquals(1, f.actions.count { it == "updates.deviceStatus" })
        } finally { cache.deleteRecursively() }
    }
}
