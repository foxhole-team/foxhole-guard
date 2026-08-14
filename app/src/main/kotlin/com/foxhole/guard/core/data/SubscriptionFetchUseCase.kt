package com.foxhole.guard.core.data

import com.foxhole.core.importer.SubscriptionMetadataParser
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.guard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.SocketFactory

internal data class SubscriptionResponse(
    val body: String? = null,
    val etag: String? = null,
    val metadataTitle: String? = null,
    val subscriptionExpiresAt: Long? = null,
    val notModified: Boolean = false,
)

internal class SubscriptionFetchUseCase(
    private val httpClient: OkHttpClient,
    private val resolver: RemoteHostResolver? = null,
    // Read for every fetch: a local firewall may capture FoxHole's own UID when WebView routing is
    // enabled, while subscription refresh is control-plane traffic that must use the current
    // physical network. A provider also avoids pinning a stale Wi-Fi/cellular Network in a cached
    // OkHttpClient after handover.
    private val underlyingSocketFactory: () -> SocketFactory? = { null },
) {
    private val defaultClient: OkHttpClient by lazy {
        httpClient.withBoundedRemoteFetchTimeouts()
    }

    suspend fun fetchSubscriptionResponse(
        sourceUrl: String,
        safeUrl: HttpUrl,
        lastEtag: String?,
        allowHttp: Boolean,
        callTimeoutMs: Long? = null,
    ): SubscriptionResponse =
        withContext(Dispatchers.IO) {
            executeSubscriptionRequest(
                client = subscriptionHttpClientFor(callTimeoutMs),
                safeUrl = safeUrl,
                sourceUrl = sourceUrl,
                lastEtag = lastEtag,
                allowHttp = allowHttp,
            )
        }

    private fun subscriptionHttpClientFor(callTimeoutMs: Long?): OkHttpClient {
        val timeoutMs = callTimeoutMs?.coerceAtLeast(1L)
        val timeoutClient =
            if (timeoutMs == null) {
                defaultClient
            } else {
                httpClient.withBoundedRemoteFetchTimeouts(
                    connectTimeoutMs = timeoutMs,
                    readTimeoutMs = timeoutMs,
                    callTimeoutMs = timeoutMs,
                )
            }
        return timeoutClient.withUnderlyingSocketFactory(underlyingSocketFactory())
    }

    private fun buildSubscriptionRequest(
        safeUrl: HttpUrl,
        lastEtag: String?,
    ): Request =
        Request.Builder()
            .url(safeUrl)
            .get()
            .header("User-Agent", "FoxHole/${BuildConfig.VERSION_NAME}")
            .apply {
                lastEtag?.takeIf(String::isNotBlank)?.let { header("If-None-Match", it) }
            }.build()

    private suspend fun executeSubscriptionRequest(
        client: OkHttpClient,
        safeUrl: HttpUrl,
        sourceUrl: String,
        lastEtag: String?,
        allowHttp: Boolean,
    ): SubscriptionResponse {
        val response =
            executeBoundedPublicGetCancellable(
                client = client,
                initialUrl = safeUrl,
                allowHttp = allowHttp,
                maxBytes = MAX_SUBSCRIPTION_BYTES,
                resolver = resolver,
            ) { url ->
                buildSubscriptionRequest(safeUrl = url, lastEtag = lastEtag)
            }
        if (response.code == 304) {
            return SubscriptionResponse(notModified = true)
        }
        if (!response.isSuccessful) {
            error(
                describeSubscriptionHttpFailure(
                    code = response.code,
                    serverHeader = response.headers["Server"],
                    responseBody = response.body.orEmpty(),
                ),
            )
        }
        return SubscriptionResponse(
            body = response.body.orEmpty(),
            etag = response.headers["ETag"],
            metadataTitle = response.headers["profile-title"].decodeSubscriptionMetadataHeader(),
            subscriptionExpiresAt =
            SubscriptionMetadataParser
                .expirationFromSubscriptionUserinfo(response.headers["subscription-userinfo"])
                ?: SubscriptionMetadataParser.expirationFromSubscriptionUrl(sourceUrl),
        )
    }
}

internal fun OkHttpClient.withUnderlyingSocketFactory(socketFactory: SocketFactory?): OkHttpClient =
    if (socketFactory == null || socketFactory === this.socketFactory) {
        this
    } else {
        newBuilder().socketFactory(socketFactory).build()
    }

private fun String?.decodeSubscriptionMetadataHeader(): String? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val value =
        if (raw.startsWith("base64:", ignoreCase = true)) {
            runCatching {
                String(
                    Base64.getDecoder().decode(raw.removePrefix("base64:").removePrefix("BASE64:")),
                    StandardCharsets.UTF_8,
                )
            }.getOrNull()
        } else {
            raw
        }
    return value?.trim()?.takeIf { it.isNotBlank() }
}
