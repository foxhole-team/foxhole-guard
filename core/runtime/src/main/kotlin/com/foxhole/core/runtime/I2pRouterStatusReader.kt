package com.foxhole.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64

/**
 * One read of the loopback i2pd webconsole, parsed into [I2pRouterStatus].
 *
 * This is the only place production code talks to the console. It exists because i2pd's transit
 * accounting lives nowhere else: the bytes a relay forwards for other routers never cross our TUN
 * and never appear as a FoxCore connection, so the runtime's own lane counters cannot see them.
 *
 * Everything about the call is deliberately narrow: plain HTTP to 127.0.0.1 with the per-start
 * credentials i2pd was configured with, no proxy (a system proxy would push a loopback request off
 * the device), short timeouts, and a bounded body read. A failure is not an error condition — the
 * router may simply be down — so it returns null and the caller skips that sample.
 */
suspend fun readI2pdRouterStatus(
    endpoint: I2pdWebConsoleEndpoint? = I2pdWebConsole.endpoint,
    timeoutMs: Int = I2PD_WEB_CONSOLE_TIMEOUT_MS,
): I2pRouterStatus? {
    val console = endpoint ?: return null
    val html =
        withContext(Dispatchers.IO) {
            runCatching { fetchI2pdWebConsolePage(console, timeoutMs) }.getOrNull()
        } ?: return null
    return parseI2pdWebConsoleStatus(html)
}

private fun fetchI2pdWebConsolePage(
    endpoint: I2pdWebConsoleEndpoint,
    timeoutMs: Int,
): String? {
    val credentials = "$I2PD_WEB_CONSOLE_USER:${endpoint.password}"
    val authorization = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
    val connection =
        URL("http://127.0.0.1:${endpoint.port}/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
    return try {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
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

/** The console page is a few KiB; the cap is there so a wedged reader cannot exhaust memory. */
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

// Loopback: a request that needs more than this is a wedged router, not a slow network.
internal const val I2PD_WEB_CONSOLE_TIMEOUT_MS = 2_000
private const val I2PD_WEB_CONSOLE_MAX_BYTES = 256 * 1024
