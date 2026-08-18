package com.foxhole.guard

import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.core.settings.updateWebAppsPushService
import com.foxhole.guard.ui.cli.webapps.buildWebAppWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class LiveWebAppsAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualWebAppLoadsThroughTheTunnel() {
        assumeTrue(
            "live web apps skipped: pass -e foxhole.liveWebApps 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveWebApps") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            val subscription = smartSubscriptionInput()
            if (subscription == null) {
                assertTrue("subscription input missing for the live web apps gate", false)
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live web apps gate", false)
                return@runBlocking
            }
            val webAppUrl = webAppUrlArgument()
            val settings = app.container.settingsRepository
            val previous = settings.current()
            var webAppId: Long? = null
            try {
                resetRelevantSettings(app)
                clearProfiles(app)
                baselineRuntimeSettings(app)

                val directIdentity =
                    withContext(Dispatchers.IO) {
                        overlayHttpGet(url = webAppUrl, timeoutMs = DIRECT_FETCH_TIMEOUT_MS)
                    }.body.toString(Charsets.UTF_8).trim()
                Log.d(TEST_TAG, "liveWebApps directIdentity=$directIdentity")
                assertTrue(
                    "foxhole.webAppUrl must answer with the caller's IP address; got: ${directIdentity.take(120)}",
                    IP_LITERAL.containsMatchIn(directIdentity),
                )

                settings.updateWebAppsEnabled(true)
                settings.updateWebAppsPushService(true)

                val profile =
                    app.container.profileRepository.importProfile(
                        rawInput = subscription,
                        preferredName = "Live Web Apps",
                    )
                app.container.connectionController.setActiveProfile(profile.id)
                val target =
                    probeTargetFor(app, profile.id, setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN))
                assertTrue("the subscription exposed no connectable option", target != null)
                app.container.connectionController.connect(profile.id, protocolOptionId = target?.optionId)
                val state = waitForTerminalState(app)
                assertEquals("the tunnel for the web apps gate did not connect: $state", ConnectionState.CONNECTED, state)
                Log.d(TEST_TAG, "liveWebApps tunnelExit=${runVpnBoundIpRefresh(app)}")
                assertTrue(
                    "the web apps transport gate stayed closed on a connected tunnel",
                    app.container.connectionController.isConnectedRuntimeCurrent(),
                )

                val preview = app.container.webAppsRepository.preview(webAppUrl)
                assertTrue(
                    "the web app preview fetch failed on a live tunnel: ${preview.exceptionOrNull()}",
                    preview.isSuccess,
                )
                val id = app.container.webAppsRepository.add(preview.getOrThrow())
                webAppId = id
                val stored = app.container.webAppsRepository.webApp(id)
                assertTrue("the created web app was not persisted", stored != null)
                assertEquals("the stored web app points at a different URL", preview.getOrThrow().url, stored?.url)

                val frameIdentity = loadWebAppIdentity(app, id, stored!!.url)
                Log.d(TEST_TAG, "liveWebApps frameIdentity=$frameIdentity")
                assertTrue(
                    "the web app frame produced no readable page text within ${WEB_VIEW_LOAD_TIMEOUT_MS}ms",
                    frameIdentity.isNotBlank(),
                )
                assertTrue(
                    "the web app frame did not render an IP address: ${frameIdentity.take(120)}",
                    IP_LITERAL.containsMatchIn(frameIdentity),
                )
                assertNotEquals(
                    "the web app frame kept the untunnelled exit address: its traffic bypassed the tunnel",
                    directIdentity,
                    frameIdentity,
                )

                app.container.webAppsWatchdog.pollOnce()
                assertTrue(
                    "the web apps watchdog never completed a pass over the tunnel",
                    waitUntil(timeoutMs = WATCHDOG_TIMEOUT_MS) {
                        app.container.webAppsRepository.webApp(id)?.lastPolledAtMs != null
                    },
                )

                app.container.webAppsRepository.remove(id)
                webAppId = null
                assertNull("the deleted web app is still in the database", app.container.webAppsRepository.webApp(id))
            } finally {
                webAppId?.let { id -> runCatching { app.container.webAppsRepository.remove(id) } }
                disconnectAndWaitForIdle(app)
                restoreSettings(settings, previous)
            }
        }
    }

    private suspend fun loadWebAppIdentity(
        app: FoxholeApplication,
        appId: Long,
        url: String,
    ): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blocked = mutableListOf<String>()
        var created: WebView? = null
        instrumentation.runOnMainSync {
            created =
                buildWebAppWebView(app, appId, url, proxyCredentials = null) { host -> blocked += host }
                    .also { view -> view.loadUrl(url) }
        }
        val webView = created ?: error("the production web app WebView was not created")
        try {
            var text = ""
            waitUntil(timeoutMs = WEB_VIEW_LOAD_TIMEOUT_MS) {
                text = readWebViewText(webView)
                IP_LITERAL.containsMatchIn(text)
            }
            if (blocked.isNotEmpty()) {
                Log.d(TEST_TAG, "liveWebApps blockedNavigations=$blocked")
            }
            return text.trim()
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private suspend fun readWebViewText(webView: WebView): String {
        val result = CompletableDeferred<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(PAGE_TEXT_SCRIPT) { value -> result.complete(value.orEmpty()) }
        }
        val raw = withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { result.await() }.orEmpty()
        return decodeJavascriptString(raw)
    }

    private fun decodeJavascriptString(raw: String): String {
        if (raw.isEmpty() || raw == "null") {
            return ""
        }
        val unquoted =
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                raw.substring(1, raw.length - 1)
            } else {
                raw
            }
        return unquoted
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }

    private fun webAppUrlArgument(): String =
        InstrumentationRegistry.getArguments()
            .getString("foxhole.webAppUrl")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_WEB_APP_URL

    private companion object {
        const val DEFAULT_WEB_APP_URL = "https://api.ipify.org"
        const val DIRECT_FETCH_TIMEOUT_MS = 30_000
        const val WEB_VIEW_LOAD_TIMEOUT_MS = 60_000L
        const val WATCHDOG_TIMEOUT_MS = 60_000L
        const val SCRIPT_TIMEOUT_MS = 5_000L
        const val PAGE_TEXT_SCRIPT = "(document.body ? document.body.innerText : '')"
        val IP_LITERAL = Regex("""(?:\d{1,3}\.){3}\d{1,3}|[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}""")
    }
}
