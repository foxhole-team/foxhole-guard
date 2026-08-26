package com.foxhole.guard.core.data

import android.content.Context
import com.foxhole.core.model.WebAppRoute
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.webapps.WEB_APP_ICON_DIR
import com.foxhole.guard.core.webapps.resolveWebAppIconFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class WebAppPreview(
    val url: String,
    val name: String,
    val iconBytes: ByteArray?,
)

class WebAppsRepository(
    private val context: Context,
    private val daoProvider: () -> WebAppDao,
    httpClient: OkHttpClient,
    private val awaitDatabaseReady: suspend () -> Unit,
) {
    private val fetchClient = httpClient.withBoundedRemoteFetchTimeouts()

    private val changesMutable =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val changes: SharedFlow<Unit> = changesMutable.asSharedFlow()

    fun observeWebApps(): Flow<List<WebAppEntity>> =
        flow {
            awaitDatabaseReady()
            emitAll(daoProvider().observeAll())
        }

    suspend fun listWebApps(): List<WebAppEntity> {
        awaitDatabaseReady()
        return daoProvider().listAll()
    }

    suspend fun webApp(id: Long): WebAppEntity? {
        awaitDatabaseReady()
        return daoProvider().byId(id)
    }

    suspend fun preview(rawUrl: String): Result<WebAppPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = requireNotNull(normalizeWebAppInputUrl(rawUrl)) { "invalid url" }

                val html = runCatching { fetchText(url, WEB_APP_PAGE_MAX_BYTES) }.getOrNull().orEmpty()
                val meta = parseWebAppHtml(url, html)
                val manifest =
                    meta.manifestUrl?.let { manifestUrl ->
                        runCatching {
                            parseWebAppManifest(
                                manifestUrl,
                                fetchText(manifestUrl, WEB_APP_MANIFEST_MAX_BYTES).orEmpty(),
                            )
                        }.getOrNull()
                    }
                val name = manifest?.name ?: meta.siteName ?: meta.title ?: url.toHttpUrl().host
                val iconCandidates =
                    (manifest?.iconUrls.orEmpty() + meta.iconCandidates + listOfNotNull(resolveWebAppUrl(url, "/favicon.ico")))
                        .distinct()
                        .take(WEB_APP_ICON_CANDIDATE_LIMIT)
                val iconBytes =
                    iconCandidates.firstNotNullOfOrNull { candidate ->
                        runCatching { fetchBytes(candidate, WEB_APP_ICON_MAX_BYTES) }
                            .getOrNull()
                            ?.takeIf(ByteArray::isNotEmpty)
                    }
                WebAppPreview(url = url, name = name, iconBytes = iconBytes)
            }
        }

    suspend fun add(preview: WebAppPreview): Long {
        awaitDatabaseReady()
        val dao = daoProvider()
        val sortOrder = (dao.listAll().maxOfOrNull(WebAppEntity::sortOrder) ?: -1) + 1
        val id =
            dao.insert(
                WebAppEntity(
                    url = preview.url,
                    name = preview.name.ifBlank { preview.url.toHttpUrl().host },
                    iconPath = null,
                    sortOrder = sortOrder,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        preview.iconBytes?.let { bytes ->
            val relativePath = "$WEB_APP_ICON_DIR/$id.img"
            withContext(Dispatchers.IO) {
                File(context.filesDir, relativePath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
            dao.updateIconPath(id, relativePath)
        }
        changesMutable.tryEmit(Unit)
        return id
    }

    suspend fun rename(id: Long, name: String) {
        awaitDatabaseReady()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return
        }
        daoProvider().rename(id, trimmed)
        changesMutable.tryEmit(Unit)
    }

    suspend fun setRoute(id: Long, route: WebAppRoute) {
        awaitDatabaseReady()
        daoProvider().updateRoute(id, route.name)
        changesMutable.tryEmit(Unit)
    }

    suspend fun remove(id: Long) {
        awaitDatabaseReady()
        val entity = daoProvider().byId(id) ?: return
        daoProvider().delete(id)
        entity.iconPath?.let { path ->
            withContext(Dispatchers.IO) { File(context.filesDir, path).delete() }
        }
        changesMutable.tryEmit(Unit)
    }

    suspend fun setBadge(id: Long, count: Int, polledAtMs: Long) {
        awaitDatabaseReady()
        daoProvider().updateBadge(id, count, polledAtMs)
        changesMutable.tryEmit(Unit)
    }

    suspend fun resetBadge(id: Long) {
        awaitDatabaseReady()
        daoProvider().resetBadge(id)
        changesMutable.tryEmit(Unit)
    }

    fun iconFile(entity: WebAppEntity): File? =
        resolveWebAppIconFile(context.filesDir, entity.iconPath)

    private fun fetchText(url: String, maxBytes: Long): String? {
        val response =
            executeBoundedPublicGet(
                client = fetchClient,
                initialUrl = url.toHttpUrl(),
                allowHttp = false,
                maxBytes = maxBytes,

                truncateOversizedBody = true,
            ) { target ->
                Request.Builder()
                    .url(target)
                    .header("Accept", "text/html,application/manifest+json,application/json;q=0.9,*/*;q=0.8")
                    .header("User-Agent", WEB_APP_USER_AGENT)
                    .build()
            }
        return if (response.isSuccessful) response.body else null
    }

    private fun fetchBytes(url: String, maxBytes: Long): ByteArray? =
        executeBoundedPublicGetBytes(
            client = fetchClient,
            initialUrl = url.toHttpUrl(),
            allowHttp = false,
            maxBytes = maxBytes,
        ) { target ->
            Request.Builder()
                .url(target)
                .header("Accept", "image/*,*/*;q=0.8")
                .build()
        }
}

private const val WEB_APP_USER_AGENT = "FoxHole/${BuildConfig.VERSION_NAME}"

private const val WEB_APP_PAGE_MAX_BYTES = 512L * 1024L
private const val WEB_APP_MANIFEST_MAX_BYTES = 256L * 1024L
private const val WEB_APP_ICON_MAX_BYTES = 512L * 1024L
private const val WEB_APP_ICON_CANDIDATE_LIMIT = 5
