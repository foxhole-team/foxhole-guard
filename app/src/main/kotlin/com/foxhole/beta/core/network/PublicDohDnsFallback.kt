package com.foxhole.beta.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal fun interface PublicDnsFallback {
    fun lookup(hostname: String): List<InetAddress>
}

internal object PublicDohDnsFallback : PublicDnsFallback {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    override fun lookup(hostname: String): List<InetAddress> {
        val normalized = hostname.requirePublicRemoteHost(resolveHost = false).trim().trimEnd('.')
        val failures = mutableListOf<Throwable>()
        DOH_ENDPOINTS.forEach { endpoint ->
            DNS_QUERY_TYPES.forEach { type ->
                val result = runCatching { query(endpoint, normalized, type) }
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

    private fun query(
        endpoint: String,
        hostname: String,
        type: Int,
    ): List<InetAddress> {
        val encodedName = URLEncoder.encode(hostname, Charsets.UTF_8.name())
        val url = URL("$endpoint?name=$encodedName&type=$type")
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("accept", "application/dns-json")
            setRequestProperty("user-agent", "FoxHole/${com.foxhole.beta.BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("DoH resolver returned HTTP $code")
            }
            parseDohAddresses(connection.inputStream.bufferedReader().use { it.readText() }, type)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseDohAddresses(
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

    private const val DOH_TIMEOUT_MS = 750
    private val DNS_QUERY_TYPES = intArrayOf(1, 28)
    private val DOH_ENDPOINTS =
        listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/resolve",
        )
}
