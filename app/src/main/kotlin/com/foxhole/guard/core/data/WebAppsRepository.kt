package com.foxhole.guard.core.data

import android.content.Context
import com.foxhole.guard.BuildConfig
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

/** Preview of an app being added: normalised https URL, name and optionally icon bytes. */
class WebAppPreview(
    val url: String,
    val name: String,
    val iconBytes: ByteArray?,
)

/**
 * The single source of the app list and badges for the screen, watchdog, notifications and widget.
 * Site metadata comes through the bounded fetch (SSRF guard, body cap) and icons are cached on disk
 * so they survive offline.
 */
class WebAppsRepository(
    private val context: Context,
    private val daoProvider: () -> WebAppDao,
    httpClient: OkHttpClient,
    private val awaitDatabaseReady: suspend () -> Unit,
    // Cleanup hook for the app's vault credential, so deletion leaves no tail.
    private val onWebAppRemoved: (Long) -> Unit = {},
) {
    private val fetchClient = httpClient.withBoundedRemoteFetchTimeouts()

    private val changesMutable =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Change pulse for CRUD and badges; the widget refreshes on it. */
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

    /** Fetch the site and build a preview: manifest, then og/title, then host; first icon wins. */
    suspend fun preview(rawUrl: String): Result<WebAppPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = requireNotNull(normalizeWebAppInputUrl(rawUrl)) { "invalid url" }
                // Only the URL has to be valid. A site that refuses the probe — 403 behind a CDN, a
                // redirect chain that leaves public space, no route while the tunnel settles — still
                // yields a usable app named after its host, so the add never fails on metadata.
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

    suspend fun remove(id: Long) {
        awaitDatabaseReady()
        val entity = daoProvider().byId(id) ?: return
        daoProvider().delete(id)
        entity.iconPath?.let { path ->
            withContext(Dispatchers.IO) { File(context.filesDir, path).delete() }
        }
        withContext(Dispatchers.IO) { runCatching { onWebAppRemoved(id) } }
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
        entity.iconPath
            ?.let { path -> File(context.filesDir, path) }
            ?.takeIf(File::exists)

    private fun fetchText(url: String, maxBytes: Long): String? {
        val response =
            executeBoundedPublicGet(
                client = fetchClient,
                initialUrl = url.toHttpUrl(),
                allowHttp = false,
                maxBytes = maxBytes,
                // Large sites exceed the body limit, so take the head of the document for <head>.
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

private const val WEB_APP_ICON_DIR = "webapps/icons"

// The default okhttp agent is rejected outright by common CDNs, which cost every probe its metadata.
private const val WEB_APP_USER_AGENT = "FoxHole/${BuildConfig.VERSION_NAME}"

private const val WEB_APP_PAGE_MAX_BYTES = 512L * 1024L
private const val WEB_APP_MANIFEST_MAX_BYTES = 256L * 1024L
private const val WEB_APP_ICON_MAX_BYTES = 512L * 1024L
private const val WEB_APP_ICON_CANDIDATE_LIMIT = 5
