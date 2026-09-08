package com.foxhole.guard.core.webapps

import android.content.Context
import android.graphics.Bitmap
import android.webkit.HttpAuthHandler
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
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.WebAppRoute
import com.foxhole.core.model.networkUp
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.WatchdogNames
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.data.WebAppsRepository
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.runtime.RuntimeSessionTicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

internal class WebAppsWatchdog(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val webAppsRepository: WebAppsRepository,
    private val scope: CoroutineScope,
    private val routeReady: suspend (WebAppRoute) -> Boolean,
    private val proxyController: WebAppProxyController,
    private val recordDiagnostic: (String) -> Unit = {},
    @Volatile var onBadgeIncreased: (WebAppEntity, Int, WebAppNotificationContent?) -> Unit = { _, _, _ -> },
) {
    private val ticker = RuntimeSessionTicker(scope = scope)
    private val pollMutex = Mutex()
    private val dataCleaner = WebAppsDataCleaner(context)

    @Volatile
    var foregroundWebAppId: Long? = null
        private set
    private var foregroundActivation: WebAppProxyActivation? = null
    private var foregroundView: WebView? = null
    private var webView: WebView? = null

    @Volatile
    private var shimBadge: Int? = null

    @Volatile
    private var shimNotification: WebAppNotificationContent? = null

    @Volatile
    private var pageFinished: CompletableDeferred<Unit>? = null

    @Volatile
    private var activeAppUrl: String? = null

    fun start() {
        scope.launch {
            settingsRepository.settings
                .map { it.webApps.enabled && it.webApps.pushServiceEnabled }
                .distinctUntilChanged()
                .collect { pushOn ->
                    applyWorkerSchedule(enabled = pushOn)
                    if (pushOn) {
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
                        pollMutex.withLock { withContext(Dispatchers.Main) { releaseWebView() } }
                    }
                }
        }

        scope.launch {
            combine(
                FoxholeVpnRuntimeBridge.snapshot,
                FoxholeVpnRuntimeBridge.i2pPhase,
            ) { snapshot, i2p -> snapshot to i2p.phase.networkUp }
                .distinctUntilChanged()
                .collect {
                    val webApps = settingsRepository.settings.value.webApps
                    if (webApps.enabled && webApps.pushServiceEnabled) {
                        pollOnce()
                    }
                }
        }
    }

    suspend fun pollOnce() {
        if (!pollMutex.tryLock()) {
            return
        }
        try {
            if (foregroundWebAppId != null) return
            val webApps = settingsRepository.settings.value.webApps
            val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
            val i2pReady = FoxholeVpnRuntimeBridge.i2pPhase.value.phase.networkUp
            val pollingEnabled = webApps.enabled && webApps.pushServiceEnabled
            if (!pollingEnabled) return
            for (app in webAppsRepository.listWebApps()) {
                pollWebApp(
                    app = app,
                    isolationEnabled = webApps.isolationEnabled,
                    snapshot = snapshot,
                    i2pReady = i2pReady
                )
            }
        } finally {
            pollMutex.unlock()
        }
    }

    private suspend fun pollWebApp(
        app: WebAppEntity,
        isolationEnabled: Boolean,
        snapshot: ConnectionSnapshot,
        i2pReady: Boolean,
    ) {
        if (!dataCleaner.prepareForLoad(app.id)) return
        val route = app.webAppRoute()
        if (
            !webAppRouteSatisfied(
                route = route,
                blockWithoutTunnel = isolationEnabled,
                snapshot = snapshot,
                i2pReady = i2pReady,
            )
        ) {
            return
        }
        val needsRuntimeRoute =
            route == WebAppRoute.VPN ||
                route == WebAppRoute.TOR ||
                route == WebAppRoute.I2P ||
                (route == WebAppRoute.DEFAULT && isolationEnabled)
        if (needsRuntimeRoute && !runCatching { routeReady(route) }.getOrDefault(false)) {
            return
        }
        var acquired: WebAppProxyActivation? = null
        val result =
            try {
                withContext(NonCancellable) {
                    acquired = proxyController.activate(route = route, blockWithoutTunnel = isolationEnabled)
                }
                currentCoroutineContext().ensureActive()
                val activation = acquired?.takeIf { it.applied } ?: return
                withContext(Dispatchers.Main) { pollApp(app, activation.credentials) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { releaseWebView() }
                acquired?.lease?.let { lease ->
                    check(withContext(NonCancellable) { proxyController.clear(lease) }) {
                        "WebView proxy override could not be cleared"
                    }
                }
            }
        val newCount = computeBadge(
            shimCount = result.shimBadge,
            title = result.pageTitle,
            previous = app.badgeCount,
        )
        if (
            shouldNotifyBadgeIncrease(
                previous = app.badgeCount,
                updated = newCount,
                appId = app.id,
                foregroundAppId = foregroundWebAppId,
            )
        ) {
            onBadgeIncreased(app, newCount, result.notification)
        }
        webAppsRepository.setBadge(app.id, newCount, System.currentTimeMillis())
    }

    suspend fun acquireForegroundProxy(
        appId: Long,
        route: WebAppRoute,
        blockWithoutTunnel: Boolean,
        requestCurrent: () -> Boolean = { true },
    ): WebAppProxyActivation = pollMutex.withLock {
        withContext(NonCancellable) {
            if (!requestCurrent() || webAppsRepository.webApp(appId)?.webAppRoute() != route) {
                return@withContext WebAppProxyActivation(applied = false)
            }
            withContext(Dispatchers.Main) {
                releaseWebView()
                destroyForegroundView()
            }
            val previous = foregroundActivation?.lease
            if (previous != null && !proxyController.clear(previous)) {
                return@withContext WebAppProxyActivation(applied = false)
            }
            foregroundActivation = null
            foregroundWebAppId = null
            if (!dataCleaner.prepareForLoad(appId) || !requestCurrent()) {
                return@withContext WebAppProxyActivation(applied = false)
            }
            foregroundWebAppId = appId
            proxyController.activate(route, blockWithoutTunnel).also { activation ->
                foregroundActivation = activation.takeIf { it.applied }
                if (!activation.applied) foregroundWebAppId = null
            }
        }
    }

    fun attachForegroundView(lease: WebAppProxyLease, view: WebView): Boolean {
        if (foregroundActivation?.lease !== lease || !lease.active) {
            view.settings.blockNetworkLoads = true
            view.destroy()
            return false
        }
        foregroundView = view
        return true
    }

    fun releaseForegroundProxyAsync(lease: WebAppProxyLease) {
        scope.launch { releaseForegroundProxy(lease) }
    }

    suspend fun releaseForegroundProxy(lease: WebAppProxyLease) = pollMutex.withLock {
        withContext(NonCancellable) {
            if (foregroundActivation?.lease !== lease) return@withContext
            withContext(Dispatchers.Main) { destroyForegroundView() }
            if (proxyController.clear(lease)) {
                foregroundActivation = null
                foregroundWebAppId = null
            } else {
                recordDiagnostic("WebView proxy release failed; polling remains suspended")
            }
        }
    }

    private fun destroyForegroundView() {
        foregroundActivation?.lease?.revoke()
        foregroundView?.let { view ->
            view.settings.blockNetworkLoads = true
            view.stopLoading()
            runCatching { WebAppProfiles.cookieManager(view).flush() }
            view.destroy()
        }
        foregroundView = null
    }

    suspend fun withPollingPaused(block: suspend () -> Unit) {
        pollMutex.withLock {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    releaseWebView()
                    destroyForegroundView()
                }
                foregroundActivation?.lease?.let { lease ->
                    check(proxyController.clear(lease)) { "WebView proxy release failed; mutation refused" }
                }
                foregroundActivation = null
                foregroundWebAppId = null
                block()
            }
        }
    }

    private suspend fun pollApp(
        app: WebAppEntity,
        proxyCredentials: WebAppProxyCredentials?,
    ): WebAppPollResult {
        if (!isAllowedWebAppUrl(app.url, app.url)) return WebAppPollResult()
        val view = createWebView(app, proxyCredentials)
        shimBadge = null
        shimNotification = null
        val finished = CompletableDeferred<Unit>()
        pageFinished = finished
        activeAppUrl = app.url
        view.loadUrl(app.url)
        withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { finished.await() }
        delay(webAppPostLoadGraceMs(app.url))
        return WebAppPollResult(
            shimBadge = shimBadge,
            pageTitle = view.title,
            notification = shimNotification,
        )
    }

    private fun createWebView(
        app: WebAppEntity,
        proxyCredentials: WebAppProxyCredentials?,
    ): WebView {
        releaseWebView()
        var documentStartScriptInstalled = false
        val shimScript = webAppShimJs(app.badgeCount)
        val view = WebView(context).apply {
            val isolated = runCatching {
                if (WebAppProfiles.supported) check(WebAppProfiles.install(this, app.id))
                WebAppProfiles.cookieManager(this)
            }.isSuccess
            if (!isolated) {
                settings.blockNetworkLoads = true
                destroy()
                error("WebApp storage isolation failed")
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)

            settings.blockNetworkImage = true
            WebAppProfiles.cookieManager(this).also { cookies ->
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, false)
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    host: String,
                    realm: String?,
                ) {
                    if (proxyCredentials != null && host == proxyCredentials.host) {
                        handler.proceed(proxyCredentials.username, proxyCredentials.password)
                    } else {
                        handler.cancel()
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url == BLANK_URL) return
                    val configured = activeAppUrl
                    if (configured == null || !isAllowedWebAppUrl(configured, url)) {
                        view.stopLoading()
                        return
                    }
                    if (!documentStartScriptInstalled) {
                        view.evaluateJavascript(shimScript, null)
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
                    parseShimSignal(message.data)?.let { signal ->
                        signal.badge?.let { badge -> shimBadge = badge }
                        signal.notification?.let { notification -> shimNotification = notification }
                        signal.installError?.let { error ->
                            recordDiagnostic("web app shim override failed: $error")
                        }
                    }
                }
            }
        } else {
            recordDiagnostic("web app shim bridge unavailable: WEB_MESSAGE_LISTENER unsupported")
        }
        documentStartScriptInstalled =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, shimScript, setOf("*"))
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
        const val TICKER_TASK_ID = WatchdogNames.WEB_ID
        const val PAGE_LOAD_TIMEOUT_MS = 20_000L
        const val BLANK_URL = "about:blank"
    }
}

internal data class WebAppPollResult(
    val shimBadge: Int? = null,
    val pageTitle: String? = null,
    val notification: WebAppNotificationContent? = null,
)

internal fun webAppPostLoadGraceMs(url: String): Long =
    if (url.toHttpUrlOrNull()?.host.equals(TELEGRAM_WEB_HOST, ignoreCase = true)) {
        TELEGRAM_POST_LOAD_GRACE_MS
    } else {
        DEFAULT_POST_LOAD_GRACE_MS
    }

internal const val DEFAULT_POST_LOAD_GRACE_MS = 5_000L
internal const val TELEGRAM_POST_LOAD_GRACE_MS = 12_000L
private const val TELEGRAM_WEB_HOST = "web.telegram.org"
