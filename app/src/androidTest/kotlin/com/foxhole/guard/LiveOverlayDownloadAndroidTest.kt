package com.foxhole.guard

import android.net.Network
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.networkUp
import com.foxhole.core.runtime.I2pdSocksProxy
import com.foxhole.core.runtime.I2pdSocksProxyEndpoint
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.TOR_CHECK_IP_INFO_ENDPOINT
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pEngaged
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
internal class LiveOverlayDownloadAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualTorDownloadDeliversTheFileByteForByte() {
        assumeTrue(
            "live tor download skipped: pass -e foxhole.liveTorDownload 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveTorDownload") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live Tor download gate", false)
                return@runBlocking
            }
            val sourceUrl = downloadUrlArgument()
            val settings = app.container.settingsRepository
            val previous = settings.current()
            try {
                resetRelevantSettings(app)
                baselineRuntimeSettings(app)

                val direct =
                    withContext(Dispatchers.IO) {
                        overlayHttpGet(url = sourceUrl, timeoutMs = DIRECT_FETCH_TIMEOUT_MS)
                    }
                assertEquals("the source URL did not answer off the tunnel: $sourceUrl", HTTP_OK, direct.statusCode)
                assertTrue("the source URL answered with an empty body off the tunnel", direct.body.isNotEmpty())
                val expectedDigest = direct.body.sha256Hex()
                Log.d(TEST_TAG, "liveTorDownload direct bytes=${direct.body.size} sha256=$expectedDigest")

                // Web apps keep this test process inside the Tor-only tunnel.
                settings.updateWebAppsEnabled(true)
                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                settings.updatePrivacyRouteBypassVpnTunnel(true)

                app.container.connectionController.connectTorOnly()
                val state = waitForTerminalState(app)
                assertEquals("the Tor-only runtime did not connect: $state", ConnectionState.CONNECTED, state)
                val vpnNetwork =
                    app.container.connectionController.currentVpnNetwork()
                        ?: error("the Tor-only runtime published no VPN network")

                assertTrue(
                    "check.torproject.org did not confirm a Tor exit on the download path",
                    awaitTorExitConfirmation(vpnNetwork),
                )

                val overlay =
                    withContext(Dispatchers.IO) {
                        overlayHttpGet(
                            url = sourceUrl,
                            timeoutMs = OVERLAY_FETCH_TIMEOUT_MS,
                            network = vpnNetwork,
                        )
                    }
                val overlayDigest = overlay.body.sha256Hex()
                Log.d(TEST_TAG, "liveTorDownload tor bytes=${overlay.body.size} sha256=$overlayDigest")
                assertEquals("the Tor download did not answer 200", HTTP_OK, overlay.statusCode)
                assertEquals(
                    "the Tor download returned a different size than the direct fetch",
                    direct.body.size,
                    overlay.body.size,
                )
                assertEquals(
                    "the Tor download returned different bytes than the direct fetch",
                    expectedDigest,
                    overlayDigest,
                )
            } finally {
                app.container.connectionController.disconnectTorOnly()
                waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
                    app.container.connectionController.snapshot.value.state == ConnectionState.IDLE
                }
                restoreSettings(settings, previous)
            }
        }
    }

    @Test
    fun manualI2pDownloadDeliversTheFileWithItsDeclaredLength() {
        assumeTrue(
            "live i2p download skipped: pass -e foxhole.liveI2pDownload 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveI2pDownload") == "1",
        )
        val target = InstrumentationRegistry.getArguments().getString("foxhole.i2pDownloadUrl")?.trim()
        if (target.isNullOrEmpty()) {
            assertTrue(
                "foxhole.i2pDownloadUrl is required for the live I2P download gate: pass a " +
                    "http://<base32>.b32.i2p/... URL (plain .i2p names need an addressbook entry)",
                false,
            )
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live I2P download gate", false)
                return@runBlocking
            }
            val settings = app.container.settingsRepository
            val previous = settings.current()
            try {
                resetRelevantSettings(app)
                baselineRuntimeSettings(app)
                settings.updateI2pEnabled(true)
                settings.updateI2pEngaged(true)
                assertTrue(
                    "enabling I2P did not ask for the transparent guard runtime",
                    settings.current().localGuardModeOrNull() != null,
                )
                app.container.connectionController.syncLocalGuard()
                assertTrue(
                    "the I2P carrier guard did not come up",
                    waitUntil(timeoutMs = GUARD_START_TIMEOUT_MS) {
                        val snapshot = app.container.connectionController.snapshot.value
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == LOCAL_GUARD_PROFILE_ID
                    },
                )
                val routerBudgetMs = longArgument("foxhole.i2pBudgetMs", I2P_NETWORK_BUDGET_MS)
                assertTrue(
                    "the I2P router never started building tunnels within ${routerBudgetMs}ms",
                    waitUntil(timeoutMs = routerBudgetMs) {
                        app.container.connectionController.i2pPhase.value.phase.networkUp
                    },
                )
                assertTrue(
                    "the i2pd SOCKS proxy never accepted the per-start credentials",
                    app.container.i2pdManager.awaitReady(I2P_SOCKS_READY_BUDGET_MS),
                )
                val socks = I2pdSocksProxy.endpoint ?: error("i2pd published no SOCKS endpoint")

                val response =
                    withContext(Dispatchers.IO) {
                        i2pSocksHttpGet(endpoint = socks, url = target, timeoutMs = I2P_FETCH_TIMEOUT_MS)
                    }
                val digest = response.body.sha256Hex()
                Log.d(
                    TEST_TAG,
                    "liveI2pDownload status=${response.statusCode} bytes=${response.body.size} " +
                        "declared=${response.declaredContentLength} sha256=$digest",
                )
                assertEquals("the eepsite did not answer 200 through the I2P SOCKS proxy", HTTP_OK, response.statusCode)
                assertTrue("the eepsite answered with an empty body", response.body.isNotEmpty())
                response.declaredContentLength?.let { declared ->
                    assertEquals(
                        "the I2P download is shorter or longer than the Content-Length the eepsite declared",
                        declared,
                        response.body.size.toLong(),
                    )
                }
                InstrumentationRegistry.getArguments().getString("foxhole.i2pDownloadSha256")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { expected ->
                        assertEquals("the I2P download does not match the expected SHA-256", expected, digest)
                    }
            } finally {
                settings.updateI2pEnabled(false)
                stopRuntimeServices(app)
                restoreSettings(settings, previous)
            }
        }
    }

    private suspend fun awaitTorExitConfirmation(network: Network): Boolean =
        waitUntil(timeoutMs = TOR_EXIT_CONFIRMATION_BUDGET_MS) {
            val body =
                withContext(Dispatchers.IO) {
                    runCatching {
                        overlayHttpGet(
                            url = TOR_CHECK_IP_INFO_ENDPOINT,
                            timeoutMs = DIRECT_FETCH_TIMEOUT_MS,
                            network = network,
                        ).body.toString(Charsets.UTF_8)
                    }.getOrElse { error -> "fail:${error.javaClass.simpleName}" }
                }
            Log.d(TEST_TAG, "liveTorDownload torCheck=${body.take(160)}")
            body.replace(" ", "").contains("\"IsTor\":true", ignoreCase = true)
        }

    private suspend fun stopRuntimeServices(app: FoxholeApplication) {
        FoxholeConnectionServiceContract.startForegroundService(
            context = app,
            mode = com.foxhole.core.model.TrafficMode.TUNNEL,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
            app.container.connectionController.snapshot.value.state == ConnectionState.IDLE
        }
        delay(2_000)
    }

    private fun downloadUrlArgument(): String =
        InstrumentationRegistry.getArguments()
            .getString("foxhole.downloadUrl")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_DOWNLOAD_URL

    private companion object {
        const val DEFAULT_DOWNLOAD_URL = "https://www.rfc-editor.org/rfc/rfc7686.txt"
        const val DIRECT_FETCH_TIMEOUT_MS = 30_000
        const val OVERLAY_FETCH_TIMEOUT_MS = 120_000
        const val I2P_FETCH_TIMEOUT_MS = 180_000
        const val TOR_EXIT_CONFIRMATION_BUDGET_MS = 240_000L
        const val GUARD_START_TIMEOUT_MS = 40_000L
        const val I2P_NETWORK_BUDGET_MS = 300_000L
        const val I2P_SOCKS_READY_BUDGET_MS = 120_000L
        const val RUNTIME_STOP_TIMEOUT_MS = 30_000L
    }
}


internal class OverlayHttpResponse(
    val statusCode: Int,
    val declaredContentLength: Long?,
    val body: ByteArray,
)

internal fun overlayHttpGet(
    url: String,
    timeoutMs: Int,
    network: Network? = null,
    basicAuth: Pair<String, String>? = null,
): OverlayHttpResponse {
    val target = URL(url)
    val connection = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
    connection.connectTimeout = timeoutMs
    connection.readTimeout = timeoutMs
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("Accept", "*/*")
    basicAuth?.let { (username, password) ->
        connection.setRequestProperty("Authorization", basicAuthorizationHeader(username, password))
    }
    try {
        val statusCode = connection.responseCode
        val stream = if (statusCode in HTTP_OK..HTTP_LAST_SUCCESS) connection.inputStream else connection.errorStream
        val body = stream?.use { input -> input.readBytes() } ?: ByteArray(0)
        return OverlayHttpResponse(
            statusCode = statusCode,
            declaredContentLength = connection.getHeaderField("Content-Length")?.toLongOrNull(),
            body = body,
        )
    } finally {
        connection.disconnect()
    }
}

internal fun i2pSocksHttpGet(
    endpoint: I2pdSocksProxyEndpoint,
    url: String,
    timeoutMs: Int,
): OverlayHttpResponse {
    val target = parseOverlayTarget(url)
    Socket().use { socket ->
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(LOOPBACK_HOST, endpoint.port), SOCKS_CONNECT_TIMEOUT_MS)
        val output = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())
        negotiateSocksAuth(input, output, endpoint)
        requestSocksConnect(input, output, target)
        writeOverlayHttpRequest(output, target, basicAuth = null)
        return readOverlayHttpResponse(input)
    }
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun basicAuthorizationHeader(
    username: String,
    password: String,
): String =
    "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

internal class OverlayTarget(
    val host: String,
    val port: Int,
    val requestTarget: String,
)

internal fun parseOverlayTarget(url: String): OverlayTarget {
    val parsed = URI(url)
    require(parsed.scheme.equals("http", ignoreCase = true)) {
        "only plain http targets can be carried over a raw overlay socket: $url"
    }
    val host = requireNotNull(parsed.host) { "no host in $url" }
    val path = parsed.rawPath.orEmpty().ifEmpty { "/" }
    val query = parsed.rawQuery?.let { raw -> "?$raw" }.orEmpty()
    return OverlayTarget(
        host = host,
        port = if (parsed.port > 0) parsed.port else DEFAULT_HTTP_PORT,
        requestTarget = path + query,
    )
}

private fun negotiateSocksAuth(
    input: InputStream,
    output: OutputStream,
    endpoint: I2pdSocksProxyEndpoint,
) {
    output.write(byteArrayOf(SOCKS_VERSION, 1, SOCKS_AUTH_USERNAME_PASSWORD))
    output.flush()
    require(input.read() == SOCKS_VERSION.toInt()) { "the i2pd proxy did not answer SOCKS5" }
    require(input.read() == SOCKS_AUTH_USERNAME_PASSWORD.toInt()) {
        "the i2pd proxy refused username/password authentication"
    }
    val username = endpoint.username.toByteArray(Charsets.UTF_8)
    val password = endpoint.password.toByteArray(Charsets.UTF_8)
    output.write(1)
    output.write(username.size)
    output.write(username)
    output.write(password.size)
    output.write(password)
    output.flush()
    require(input.read() == 1 && input.read() == 0) { "the i2pd proxy rejected the per-start credentials" }
}

private fun requestSocksConnect(
    input: InputStream,
    output: OutputStream,
    target: OverlayTarget,
) {
    val host = target.host.toByteArray(Charsets.UTF_8)
    output.write(byteArrayOf(SOCKS_VERSION, SOCKS_COMMAND_CONNECT, 0, SOCKS_ADDRESS_DOMAIN))
    output.write(host.size)
    output.write(host)
    output.write((target.port shr 8) and 0xff)
    output.write(target.port and 0xff)
    output.flush()
    require(input.read() == SOCKS_VERSION.toInt()) { "malformed SOCKS5 connect reply" }
    val reply = input.read()
    require(reply == 0) { "the overlay refused to reach ${target.host}: socks reply=$reply" }
    input.read()
    when (val addressType = input.read()) {
        SOCKS_ADDRESS_IPV4.toInt() -> skipExactly(input, 4)
        SOCKS_ADDRESS_DOMAIN.toInt() -> skipExactly(input, input.read().coerceAtLeast(0))
        SOCKS_ADDRESS_IPV6.toInt() -> skipExactly(input, 16)
        else -> error("unknown SOCKS5 bound-address type: $addressType")
    }
    skipExactly(input, 2)
}

private fun writeOverlayHttpRequest(
    output: OutputStream,
    target: OverlayTarget,
    basicAuth: Pair<String, String>?,
) {
    val host = if (target.port == DEFAULT_HTTP_PORT) target.host else "${target.host}:${target.port}"
    val request =
        buildString {
            append("GET ${target.requestTarget} HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Accept: */*\r\n")
            basicAuth?.let { (username, password) ->
                append("Authorization: ${basicAuthorizationHeader(username, password)}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
    output.write(request.toByteArray(Charsets.ISO_8859_1))
    output.flush()
}

private fun readOverlayHttpResponse(input: InputStream): OverlayHttpResponse {
    val statusLine = readHeaderLine(input) ?: error("the overlay returned an empty response")
    val statusCode =
        statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("malformed HTTP status line: $statusLine")
    var declaredContentLength: Long? = null
    var chunked = false
    while (true) {
        val line = readHeaderLine(input)
        if (line.isNullOrEmpty()) {
            break
        }
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
            declaredContentLength = line.substringAfter(':').trim().toLongOrNull()
        }
        if (line.startsWith("Transfer-Encoding:", ignoreCase = true) && line.contains("chunked", ignoreCase = true)) {
            chunked = true
        }
    }
    val body =
        when {
            chunked -> readChunkedBody(input)
            declaredContentLength != null -> readExactly(input, declaredContentLength.toInt())
            else -> input.readBytes()
        }
    return OverlayHttpResponse(
        statusCode = statusCode,
        declaredContentLength = declaredContentLength,
        body = body,
    )
}

private fun readChunkedBody(input: InputStream): ByteArray {
    val sink = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readHeaderLine(input)?.substringBefore(';')?.trim().orEmpty()
        if (sizeLine.isEmpty()) {
            break
        }
        val size = sizeLine.toIntOrNull(radix = 16) ?: error("malformed chunk size: $sizeLine")
        if (size == 0) {
            break
        }
        sink.write(readExactly(input, size))
        readHeaderLine(input)
    }
    return sink.toByteArray()
}

private fun readHeaderLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val value = input.read()
        if (value < 0) {
            return line.toString().takeIf { it.isNotEmpty() }
        }
        if (value == '\n'.code) {
            return line.toString().removeSuffix("\r")
        }
        line.append(value.toChar())
    }
}

private fun readExactly(
    input: InputStream,
    count: Int,
): ByteArray {
    val body = ByteArray(count)
    var read = 0
    while (read < count) {
        val step = input.read(body, read, count - read)
        if (step < 0) {
            error("the overlay closed the connection after $read of $count bytes")
        }
        read += step
    }
    return body
}

private fun skipExactly(
    input: InputStream,
    count: Int,
) {
    repeat(count) {
        require(input.read() >= 0) { "the overlay closed the connection mid-handshake" }
    }
}

internal const val HTTP_OK = 200
private const val HTTP_LAST_SUCCESS = 299
private const val LOOPBACK_HOST = "127.0.0.1"
private const val DEFAULT_HTTP_PORT = 80
private const val SOCKS_CONNECT_TIMEOUT_MS = 5_000
private const val SOCKS_VERSION: Byte = 5
private const val SOCKS_COMMAND_CONNECT: Byte = 1
private const val SOCKS_AUTH_USERNAME_PASSWORD: Byte = 2
private const val SOCKS_ADDRESS_IPV4: Byte = 1
private const val SOCKS_ADDRESS_DOMAIN: Byte = 3
private const val SOCKS_ADDRESS_IPV6: Byte = 4
