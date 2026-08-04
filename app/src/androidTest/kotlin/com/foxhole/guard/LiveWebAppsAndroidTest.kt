package com.foxhole.guard

import android.util.Log
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.guard.core.security.vault.WebAppCredentials
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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live web apps on hardware: a web app created on a running tunnel, loaded in the production
 * frame, and shown to have left through that tunnel.
 *
 * The feature has three device-only halves and none of them had coverage: the metadata fetch
 * that turns a URL into an app, the WebView that renders it, and the encrypted credential
 * entry that must disappear with it. The WebView half is the one that cannot be reasoned
 * about off-device at all — it has its own network stack inside the app process, and whether
 * that stack follows the VPN split is an Android runtime property, not a code property.
 *
 * **So the assertion is the address the site sees, not the connection state.** The test reads
 * the client IP from the configured echo endpoint twice: once directly with the tunnel down,
 * and once out of the production `buildWebAppWebView` with the tunnel up. Equal addresses fail
 * the test — that is a WebView rendering the user's web app straight onto the underlying
 * network while every indicator in the app says the tunnel is carrying it, which is precisely
 * the leak this feature exists to prevent.
 *
 * Two further facts are asserted around it: the production push watchdog completes a real pass
 * over the tunnel (`lastPolledAtMs` moves, through the same WebView path it uses in the field),
 * and a vault credential saved for the app is gone from the Keystore-backed store once the app
 * is deleted.
 *
 * **What this does not prove:** which route rule carried the packets. The observable is the
 * egress identity, so a configuration that tunnels the WebView through the wrong outbound —
 * but still a tunnelled one — would pass. Proving the rule itself needs per-flow attribution
 * the app does not expose to a test.
 *
 * Manual gate: `-Pandroid.testInstrumentationRunnerArguments.foxhole.liveWebApps=1`. Needs a
 * subscription (same inputs as the other live suites) and, optionally,
 * `foxhole.webAppUrl=<https url that echoes the caller's IP>`.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveWebAppsAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualWebAppLoadsThroughTheTunnelAndForgetsItsCredentials() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveWebApps") != "1") {
            Log.d(TEST_TAG, "live web apps skipped")
            return
        }
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

                // Leg one: who the site thinks we are with no tunnel. Everything below is a
                // comparison against this string, so it is taken before anything is started.
                val directIdentity =
                    withContext(Dispatchers.IO) {
                        overlayHttpGet(url = webAppUrl, timeoutMs = DIRECT_FETCH_TIMEOUT_MS)
                    }.body.toString(Charsets.UTF_8).trim()
                Log.d(TEST_TAG, "liveWebApps directIdentity=$directIdentity")
                assertTrue(
                    "foxhole.webAppUrl must answer with the caller's IP address; got: ${directIdentity.take(120)}",
                    IP_LITERAL.containsMatchIn(directIdentity),
                )

                // Web apps are configured BEFORE connecting on purpose: the runtime signature the
                // web-apps transport gate compares against is derived from settings, so flipping
                // them under a live session would leave the gate permanently closed.
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

                // Creating the app is itself an on-tunnel network operation: the preview fetch
                // is the app's own bounded metadata client, and it has to reach the site.
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

                // Leg two: the production frame's WebView, on the same endpoint.
                val frameIdentity = loadWebAppIdentity(app, stored!!.url)
                Log.d(TEST_TAG, "liveWebApps frameIdentity=$frameIdentity")
                assertTrue(
                    "the web app frame produced no readable page text within ${WEB_VIEW_LOAD_TIMEOUT_MS}ms",
                    frameIdentity.isNotBlank(),
                )
                assertTrue(
                    "the web app frame did not render an IP address: ${frameIdentity.take(120)}",
                    IP_LITERAL.containsMatchIn(frameIdentity),
                )
                // The whole point: same address means the WebView went out beside the tunnel.
                assertNotEquals(
                    "the web app frame kept the untunnelled exit address: its traffic bypassed the tunnel",
                    directIdentity,
                    frameIdentity,
                )

                // The production push watchdog, on the same tunnel, through its own WebView.
                app.container.webAppsWatchdog.pollOnce()
                assertTrue(
                    "the web apps watchdog never completed a pass over the tunnel",
                    waitUntil(timeoutMs = WATCHDOG_TIMEOUT_MS) {
                        app.container.webAppsRepository.webApp(id)?.lastPolledAtMs != null
                    },
                )

                // The vault half: a credential saved for this app must not survive its deletion.
                val credentials = WebAppCredentials(login = "live-test", password = "live-test-secret", note = "device gate")
                app.container.webAppCredentialsStore.save(id, credentials)
                assertEquals(
                    "the encrypted web app credential did not survive a round trip",
                    credentials,
                    app.container.webAppCredentialsStore.load(id),
                )
                app.container.webAppsRepository.remove(id)
                webAppId = null
                assertNull(
                    "deleting the web app left its credential behind in the vault",
                    app.container.webAppCredentialsStore.load(id),
                )
                assertNull("the deleted web app is still in the database", app.container.webAppsRepository.webApp(id))
            } finally {
                webAppId?.let { id -> runCatching { app.container.webAppsRepository.remove(id) } }
                disconnectAndWaitForIdle(app)
                restoreSettings(settings, previous)
            }
        }
    }

    /**
     * Loads [url] in the production web app frame and returns what the page renders.
     *
     * The WebView is built by the shipped factory rather than a test-local one, so the frame's
     * own settings (mixed content off, file access off, the same-site navigation clamp) are the
     * ones under test. Its `WebViewClient` is left untouched — the page text is polled instead
     * of waiting on `onPageFinished`, because replacing the client to observe the load would
     * replace the policy this test is here to exercise.
     */
    private suspend fun loadWebAppIdentity(
        app: FoxholeApplication,
        url: String,
    ): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blocked = mutableListOf<String>()
        var created: WebView? = null
        instrumentation.runOnMainSync {
            created =
                buildWebAppWebView(app, url) { host -> blocked += host }
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

    /** `evaluateJavascript` hands back a JSON literal, so `"1.2.3.4\n"` has to be unwrapped. */
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
        /**
         * A plain-text client-IP echo, already one of the app's own IP-info fallbacks. HTTPS and
         * a dotted host are both required by the web app origin policy, so an endpoint that is
         * neither cannot be configured as a web app at all.
         */
        const val DEFAULT_WEB_APP_URL = "https://api.ipify.org"
        const val DIRECT_FETCH_TIMEOUT_MS = 30_000
        const val WEB_VIEW_LOAD_TIMEOUT_MS = 60_000L
        const val WATCHDOG_TIMEOUT_MS = 60_000L
        const val SCRIPT_TIMEOUT_MS = 5_000L
        const val PAGE_TEXT_SCRIPT = "(document.body ? document.body.innerText : '')"
        val IP_LITERAL = Regex("""(?:\d{1,3}\.){3}\d{1,3}|[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}""")
    }
}
