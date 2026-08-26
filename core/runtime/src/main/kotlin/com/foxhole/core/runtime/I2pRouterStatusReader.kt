package com.foxhole.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64

suspend fun readI2pdRouterStatus(
    endpoint: I2pdWebConsoleEndpoint? = I2pdWebConsole.endpoint,
    timeoutMs: Int = I2PD_WEB_CONSOLE_TIMEOUT_MS,
): I2pRouterStatus? {
    val console = endpoint ?: return null
    val body =
        withContext(Dispatchers.IO) {
            runCatching { fetchI2pdStatus(console, timeoutMs) }.getOrNull()
        } ?: return null
    return parseI2pdFoxHoleStatus(body)
}

private fun fetchI2pdStatus(
    endpoint: I2pdWebConsoleEndpoint,
    timeoutMs: Int,
): String? {
    val credentials = "$I2PD_WEB_CONSOLE_USER:${endpoint.password}"
    val authorization = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    val connection =
        URL("http://127.0.0.1:${endpoint.port}/foxhole-status")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/plain")
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("Authorization", "Basic $authorization")
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.readBoundedText()
        } else {
            null
        }
    } finally {
        connection.disconnect()
    }
}

private fun InputStream.readBoundedText(): String =
    use { stream ->
        val buffer = ByteArray(I2PD_WEB_CONSOLE_MAX_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read < 0) {
                break
            }
            filled += read
        }
        String(buffer, 0, filled, Charsets.UTF_8)
    }

internal const val I2PD_WEB_CONSOLE_TIMEOUT_MS = 2_000
private const val I2PD_WEB_CONSOLE_MAX_BYTES = 16 * 1024
