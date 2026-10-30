package com.foxhole.core.runtime.network

import android.net.Network
import com.foxhole.core.network.isPrivateOrLocalAddress
import com.foxhole.core.network.requirePublicRemoteHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

fun interface PublicDnsFallback {
    fun lookup(hostname: String): List<InetAddress>
}

/** DoH bootstrap that follows the latest physical Android network instead of a stale VPN. */
class NetworkBoundPublicDnsFallback(
    private val currentNetwork: () -> Network?,
) : PublicDnsFallback {
    constructor(network: Network) : this({ network })

    override fun lookup(hostname: String): List<InetAddress> {
        val network = currentNetwork()
        return PublicDohDnsFallback.lookupWithConnectionFactory(hostname) { url ->
            (network?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
        }
    }
}

internal object PublicDohDnsFallback : PublicDnsFallback {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    override fun lookup(hostname: String): List<InetAddress> =
        lookupWithConnectionFactory(hostname) { url ->
            url.openConnection() as HttpsURLConnection
        }

    fun lookupWithConnectionFactory(
        hostname: String,
        connectionFactory: (URL) -> HttpsURLConnection,
    ): List<InetAddress> {
        val normalized = hostname.requirePublicRemoteHost(resolveHost = false).trim().trimEnd('.')
        val failures = mutableListOf<Throwable>()
        DOH_ENDPOINTS.forEach { endpoint ->
            DNS_QUERY_TYPES.forEach { type ->
                val result = runCatching { query(endpoint, normalized, type, connectionFactory) }
                val addresses = result.getOrNull().orEmpty().filterNot(InetAddress::isPrivateOrLocalAddress)
                if (addresses.isNotEmpty()) {
                    return addresses.preferIpv4()
                }
                result.exceptionOrNull()?.let(failures::add)
            }
        }
        val failure = failures.firstOrNull()
        throw UnknownHostException("public DoH fallback could not resolve remote host: $normalized").apply {
            failure?.let(::initCause)
        }
    }

    fun lookupTxt(hostname: String): List<String> {
        val normalized = hostname.requirePublicRemoteHost(resolveHost = false).trim().trimEnd('.')
        val failures = mutableListOf<Throwable>()
        DOH_ENDPOINTS.forEach { endpoint ->
            val result = runCatching { queryTxt(endpoint, normalized) }
            val records = result.getOrNull().orEmpty()
            if (records.isNotEmpty()) {
                return records
            }
            result.exceptionOrNull()?.let(failures::add)
        }
        val failure = failures.firstOrNull()
        throw UnknownHostException("public DoH fallback could not resolve TXT record: $normalized").apply {
            failure?.let(::initCause)
        }
    }

    private fun query(
        endpoint: String,
        hostname: String,
        type: Int,
        connectionFactory: (URL) -> HttpsURLConnection,
    ): List<InetAddress> {
        val encodedName = URLEncoder.encode(hostname, Charsets.UTF_8.name())
        val url = URL("$endpoint?name=$encodedName&type=$type")
        val connection = connectionFactory(url).apply {
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("accept", "application/dns-json")
            setRequestProperty("user-agent", "FoxHole/${com.foxhole.core.runtime.BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("DoH resolver returned HTTP $code")
            }
            parseDohAddresses(connection.inputStream.use { it.readDohBodyCapped() }, type)
        } finally {
            connection.disconnect()
        }
    }

    private fun queryTxt(
        endpoint: String,
        hostname: String,
    ): List<String> {
        val encodedName = URLEncoder.encode(hostname, Charsets.UTF_8.name())
        val url = URL("$endpoint?name=$encodedName&type=$DNS_QUERY_TYPE_TXT")
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("accept", "application/dns-json")
            setRequestProperty("user-agent", "FoxHole/${com.foxhole.core.runtime.BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("DoH resolver returned HTTP $code")
            }
            parseDohTxtRecords(connection.inputStream.use { it.readDohBodyCapped() })
        } finally {
            connection.disconnect()
        }
    }

    fun parseDohAddresses(
        body: String,
        type: Int,
    ): List<InetAddress> {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["Status"]?.jsonPrimitive?.intOrNull
        if (status != 0) {
            return emptyList()
        }
        return root["Answer"]
            ?.jsonArray
            ?.mapNotNull { answer ->
                val objectValue = answer.jsonObject
                val answerType = objectValue["type"]?.jsonPrimitive?.intOrNull
                val data = objectValue["data"]?.jsonPrimitive?.content
                if (answerType == type && !data.isNullOrBlank()) {
                    runCatching { InetAddress.getByName(data) }.getOrNull()
                } else {
                    null
                }
            }.orEmpty()
    }

    fun parseDohTxtRecords(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["Status"]?.jsonPrimitive?.intOrNull
        if (status != 0) {
            return emptyList()
        }
        return root["Answer"]
            ?.jsonArray
            ?.mapNotNull { answer ->
                val objectValue = answer.jsonObject
                val answerType = objectValue["type"]?.jsonPrimitive?.intOrNull
                val data = objectValue["data"]?.jsonPrimitive?.content
                if (answerType == DNS_QUERY_TYPE_TXT && !data.isNullOrBlank()) {
                    data.decodeDnsTxtRecord()
                } else {
                    null
                }
            }.orEmpty()
    }

    private fun String.decodeDnsTxtRecord(): String =
        trim()
            .removeSurrounding("\"")
            .replace("\" \"", "")
            .trim()

    private const val DOH_TIMEOUT_MS = 750
    private const val DNS_QUERY_TYPE_TXT = 16
    private val DNS_QUERY_TYPES = intArrayOf(1, 28)
    private val DOH_ENDPOINTS =
        listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/resolve",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/resolve",
        )
}

internal fun InputStream.readDohBodyCapped(
    nowNanos: () -> Long = System::nanoTime,
): String {
    val started = nowNanos()
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(8_192)
    while (true) {
        requireDohBodyTime(started, nowNanos())
        val remaining = DOH_MAX_BODY_BYTES + 1 - output.size()
        if (remaining <= 0) throw IOException("DoH response body too large")
        val count = read(chunk, 0, minOf(chunk.size, remaining))
        requireDohBodyTime(started, nowNanos())
        if (count < 0) return output.toString(Charsets.UTF_8.name())
        output.write(chunk, 0, count)
        if (output.size() > DOH_MAX_BODY_BYTES) throw IOException("DoH response body too large")
    }
}

private fun requireDohBodyTime(started: Long, now: Long) {
    if (now - started >= TimeUnit.MILLISECONDS.toNanos(DOH_BODY_BUDGET_MS)) {
        throw SocketTimeoutException("DoH response body timed out")
    }
}

private const val DOH_MAX_BODY_BYTES = 64 * 1024
private const val DOH_BODY_BUDGET_MS = 1_500L
