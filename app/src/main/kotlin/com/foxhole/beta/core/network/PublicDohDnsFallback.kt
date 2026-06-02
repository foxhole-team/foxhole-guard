package com.foxhole.beta.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
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

    override fun lookup(hostname: String): List<InetAddress> =
        lookupWithConnectionFactory(hostname) { url ->
            url.openConnection() as HttpsURLConnection
        }

    internal fun lookupWithConnectionFactory(
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

    internal fun lookupTxt(hostname: String): List<String> {
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
            setRequestProperty("user-agent", "FoxHole/${com.foxhole.beta.BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("DoH resolver returned HTTP $code")
            }
            parseDohTxtRecords(connection.inputStream.bufferedReader().use { it.readText() })
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

    internal fun parseDohTxtRecords(body: String): List<String> {
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
