package com.foxhole.guard.core.webapps

import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.data.WebAppsRepository
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.runtime.RuntimeSessionTicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Push watchdog for web apps. Android WebView has no real Web Push, so a single hidden WebView
 * walks the sites on a schedule using the JS shim ([WEB_APP_SHIM_JS]) and a `(N)` title heuristic.
 * It runs only when push is on *and* the tunnel or guard is up, so polling always goes through the
 * tunnel. Cookies are shared with the full-screen frame (one process, one WebView profile), so
 * logged-in sessions are visible to the watchdog too.
 *
 * Cadence: a [RuntimeSessionTicker] in the app process, plus a WorkManager pass every 15 minutes
 * in case the process dies. Concurrent passes collapse on a mutex.
 */
internal class WebAppsWatchdog(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val webAppsRepository: WebAppsRepository,
    private val scope: CoroutineScope,
    private val routeReady: suspend () -> Boolean,
    @Volatile var onBadgeIncreased: (WebAppEntity, Int) -> Unit = { _, _ -> },
) {
    private val ticker = RuntimeSessionTicker(scope = scope)
    private val pollMutex = Mutex()
    private var webView: WebView? = null
    private var documentStartScriptInstalled = false

    @Volatile
    private var shimBadge: Int? = null

    @Volatile
    private var pageFinished: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeAppUrl: String? = null

    fun start() {
        scope.launch {
            combine(
                settingsRepository.settings
                    .map { it.webApps.enabled && it.webApps.pushServiceEnabled }
                    .distinctUntilChanged(),
                FoxholeVpnRuntimeBridge.snapshot
                    .map { it.state == ConnectionState.CONNECTED }
                    .distinctUntilChanged(),
            ) { pushOn, tunnelUp -> pushOn to tunnelUp }
                .collect { (pushOn, tunnelUp) ->
                    applyWorkerSchedule(enabled = pushOn)
                    if (pushOn && tunnelUp) {
                        ticker.register(
                            id = TICKER_TASK_ID,
                            fireImmediately = true,
                            intervalMs = {
                                settingsRepository.settings.value.webApps.pollIntervalMinutes * 60_000L
                            },
                            runOn = Dispatchers.Default,
                        ) { pollOnce() }
                    } else {
                        ticker.unregister(TICKER_TASK_ID)
                        withContext(Dispatchers.Main) { releaseWebView() }
                    }
                }
        }
    }

    /** One strict pass over every app; concurrent calls collapse. */
    suspend fun pollOnce() {
        if (!pollMutex.tryLock()) {
            return
        }
        try {
            val webApps = settingsRepository.settings.value.webApps
            val tunnelUp = FoxholeVpnRuntimeBridge.snapshot.value.state == ConnectionState.CONNECTED
            val pollingEnabled = webApps.enabled && webApps.pushServiceEnabled
            if (!pollingEnabled || !tunnelUp) return
            if (!runCatching { routeReady() }.getOrDefault(false)) return
            for (app in webAppsRepository.listWebApps()) {
                val (shim, title) = withContext(Dispatchers.Main) { pollApp(app) }
                val newCount = computeBadge(shimCount = shim, title = title, previous = app.badgeCount)
                if (newCount > app.badgeCount) {
                    onBadgeIncreased(app, newCount)
                }
                webAppsRepository.setBadge(app.id, newCount, System.currentTimeMillis())
            }
        } finally {
            pollMutex.unlock()
        }
    }

    private suspend fun pollApp(app: WebAppEntity): Pair<Int?, String?> {
        val view = ensureWebView()
        if (!isAllowedWebAppUrl(app.url, app.url)) return null to null
        shimBadge = null
        val finished = CompletableDeferred<Unit>()
        pageFinished = finished
        activeAppUrl = app.url
        return try {
            view.loadUrl(app.url)
            withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { finished.await() }
            // Grace after onPageFinished so the site's post-load JS can run.
            delay(POST_LOAD_GRACE_MS)
            shimBadge to view.title
        } finally {
            activeAppUrl = null
            pageFinished = null
            view.loadUrl(BLANK_URL)
        }
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            // The watchdog needs no images: saves traffic and load time.
            settings.blockNetworkImage = true
            CookieManager.getInstance().also { cookies ->
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, false)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url == BLANK_URL) return
                    val configured = activeAppUrl
                    if (configured == null || !isAllowedWebAppUrl(configured, url)) {
                        view.stopLoading()
                        return
                    }
                    if (!documentStartScriptInstalled) {
                        view.evaluateJavascript(WEB_APP_SHIM_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // A late finish from about:blank must not complete the next app's load.
                    val configured = activeAppUrl
                    if (
                        configured != null &&
                        url == view.url &&
                        isAllowedWebAppUrl(configured, url)
                    ) {
                        pageFinished?.complete(Unit)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val configured = activeAppUrl ?: return true
                    return !isAllowedWebAppUrl(configured, request.url.toString())
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                view,
                WEB_APP_SHIM_BRIDGE_NAME,
                setOf("*"),
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                val configured = activeAppUrl
                if (
                    isMainFrame &&
                    configured != null &&
                    isAllowedWebAppUrl(configured, sourceOrigin.toString())
                ) {
                    parseShimBadgeMessage(message.data)?.let { badge -> shimBadge = badge }
                }
            }
        }
        documentStartScriptInstalled =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, WEB_APP_SHIM_JS, setOf("*"))
            }.isSuccess
        webView = view
        return view
    }

    private fun releaseWebView() {
        pageFinished?.complete(Unit)
        pageFinished = null
        activeAppUrl = null
        webView?.destroy()
        webView = null
        documentStartScriptInstalled = false
    }

    private fun applyWorkerSchedule(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (enabled) {
            workManager.enqueueUniquePeriodicWork(
                WebAppsWatchdogWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WebAppsWatchdogWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        } else {
            workManager.cancelUniqueWork(WebAppsWatchdogWorker.WORK_NAME)
        }
    }

    private companion object {
        const val TICKER_TASK_ID = "webapps-watchdog"
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val POST_LOAD_GRACE_MS = 5_000L
        const val BLANK_URL = "about:blank"
    }
}
