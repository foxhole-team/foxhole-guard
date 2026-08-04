package com.foxhole.guard.runtime

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicHttpsUrl
import com.foxhole.core.network.requirePublicHttpsUrl
import com.foxhole.core.runtime.TorBridgeMetadata
import com.foxhole.core.runtime.TorBridgeStore
import com.foxhole.core.runtime.network.PublicRemoteDns
import com.foxhole.guard.core.data.withBoundedRemoteFetchTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Downloads a fresh Tor bridge list. The default source is the Tor Project's built-in bridges
 * endpoint; enabling "Foxhole proxy" (a Foxhole-hosted GitHub mirror) switches the source. Both
 * return the same bridge-groups JSON shape ({"obfs4":[…],"snowflake":[…],…}, optionally wrapped in
 * {"bridges":{…}} with a "recommendedDefault"). The payload is validated by [TorBridgeStore.install]
 * before it can replace the active list.
 */
enum class TorBridgeUpdateStatus {
    UPDATED,
    UP_TO_DATE,
    FAILED,
}

data class TorBridgeUpdateResult(
    val status: TorBridgeUpdateStatus,
    val metadata: TorBridgeMetadata? = null,
    val reason: String? = null,
)

class TorBridgeUpdateClient(
    private val httpClient: OkHttpClient,
    private val resolver: RemoteHostResolver? = null,
) {
    suspend fun update(
        store: TorBridgeStore,
        useFoxholeSource: Boolean,
        onPhase: (RemoteUpdatePhase) -> Unit = {},
    ): TorBridgeUpdateResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = guardedClient()
                val sourceUrl = if (useFoxholeSource) TOR_BRIDGES_FOXHOLE_URL else TOR_BRIDGES_TOR_SITE_URL
                onPhase(RemoteUpdatePhase.CHECKING)
                val payload = client.getText(sourceUrl.asPublicHttpsUrl(), MAX_BRIDGE_BYTES, "tor bridge list")
                require(payload.isNotBlank()) { "empty bridge list" }
                if (payload.trim() == store.readGroupsJsonOrNull()?.trim()) {
                    return@runCatching TorBridgeUpdateResult(status = TorBridgeUpdateStatus.UP_TO_DATE)
                }
                onPhase(RemoteUpdatePhase.DOWNLOADING)
                onPhase(RemoteUpdatePhase.VERIFYING)
                val metadata = store.install(rawPayload = payload, source = sourceUrl)
                TorBridgeUpdateResult(status = TorBridgeUpdateStatus.UPDATED, metadata = metadata)
            }.getOrElse { error ->
                TorBridgeUpdateResult(
                    status = TorBridgeUpdateStatus.FAILED,
                    reason = error.message ?: error.javaClass.simpleName,
                )
            }
        }

    private fun guardedClient(): OkHttpClient =
        httpClient
            .withBoundedRemoteFetchTimeouts(
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                callTimeoutMs = CALL_TIMEOUT_MS,
            ).newBuilder()
            .dns(PublicRemoteDns(httpClient.dns::lookup))
            .build()

    private fun String.asPublicHttpsUrl(): HttpUrl =
        ensurePublicHttpsUrl(resolveHost = true, resolver = resolver)

    private fun OkHttpClient.getText(
        url: HttpUrl,
        maxBytes: Long,
        label: String,
    ): String {
        url.requirePublicHttpsUrl(resolveHost = true, resolver = resolver)
        newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$label request failed with HTTP ${response.code}")
            }
            return requireNotNull(response.body) { "$label response body is empty" }
                .readBytesCapped(maxBytes)
                .toString(Charsets.UTF_8)
        }
    }

    companion object {
        const val TOR_BRIDGES_TOR_SITE_URL = "https://bridges.torproject.org/moat/circumvention/builtin"

        // Foxhole-hosted mirror ("Foxhole proxy" opt-in). Same GitHub-pages pattern as the DNS list.
        const val TOR_BRIDGES_FOXHOLE_URL = "https://foxhole-team.github.io/foxhole-bridges/bridges.json"
        private const val MAX_BRIDGE_BYTES = 256L * 1024L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 60_000L
    }
}
