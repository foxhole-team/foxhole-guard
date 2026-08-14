package com.foxhole.guard.core.webapps

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.foxhole.core.model.WebAppRoute
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.ProxyAccessType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

internal sealed interface WebAppProxyPlan {
    data object Direct : WebAppProxyPlan

    data class Http(val access: HttpProxyAccess) : WebAppProxyPlan
}

internal data class WebAppProxyCredentials(
    val host: String,
    val username: String,
    val password: String,
)

internal data class WebAppProxyActivation(
    val applied: Boolean,
    val credentials: WebAppProxyCredentials? = null,
)

internal fun WebAppProxyPlan.mustBlockServiceWorkerNetwork(): Boolean = this is WebAppProxyPlan.Http

/**
 * Serial process-global WebView proxy switch. A strict route is activated before its WebView is
 * created; an unavailable proxy never falls through to WebView's default network.
 */
internal class WebAppProxyController(
    context: Context,
    private val planProvider: suspend (WebAppRoute, Boolean) -> WebAppProxyPlan?,
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val switchMutex = Mutex()

    @Volatile
    var activeCredentials: WebAppProxyCredentials? = null
        private set

    suspend fun activate(
        route: WebAppRoute,
        blockWithoutTunnel: Boolean,
    ): WebAppProxyActivation =
        switchMutex.withLock {
            val plan = planProvider(route, blockWithoutTunnel)
                ?: return@withLock WebAppProxyActivation(applied = false)
            when (plan) {
                WebAppProxyPlan.Direct -> {
                    if (!clearOverride() || !setServiceWorkerNetworkBlocked(plan.mustBlockServiceWorkerNetwork())) {
                        return@withLock WebAppProxyActivation(applied = false)
                    }
                    activeCredentials = null
                    WebAppProxyActivation(applied = true)
                }
                is WebAppProxyPlan.Http -> {
                    val access = plan.access
                    if (!access.isUsableLoopbackHttpProxy()) {
                        return@withLock WebAppProxyActivation(applied = false)
                    }
                    if (!setServiceWorkerNetworkBlocked(plan.mustBlockServiceWorkerNetwork())) {
                        return@withLock WebAppProxyActivation(applied = false)
                    }
                    if (!setHttpOverride(access.host, access.port)) {
                        setServiceWorkerNetworkBlocked(false)
                        return@withLock WebAppProxyActivation(applied = false)
                    }
                    val credentials =
                        WebAppProxyCredentials(
                            host = access.host,
                            username = requireNotNull(access.username),
                            password = requireNotNull(access.password),
                        )
                    activeCredentials = credentials
                    WebAppProxyActivation(applied = true, credentials = credentials)
                }
            }
        }

    suspend fun clear(): Boolean =
        switchMutex.withLock {
            val overrideCleared = clearOverride()
            val workerNetworkRestored = overrideCleared && setServiceWorkerNetworkBlocked(false)
            (overrideCleared && workerNetworkRestored).also { cleared ->
                if (cleared) {
                    activeCredentials = null
                }
            }
        }

    /**
     * Fail-closed gate for the process-global WebView override: every clause must hold before an
     * HTTP plan may be installed — the right type, the loopback host only, a routable port, and
     * both credentials present. A plan failing any one of them is not applied at all.
     */
    private fun HttpProxyAccess.isUsableLoopbackHttpProxy(): Boolean =
        type == ProxyAccessType.HTTP &&
            host == LOOPBACK_HOST &&
            port in 1..65_535 &&
            !username.isNullOrBlank() &&
            !password.isNullOrBlank()

    private suspend fun setHttpOverride(host: String, port: Int): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false
        return runCatching {
            val config =
                ProxyConfig.Builder()
                    .removeImplicitRules()
                    .addProxyRule("http://$host:$port")
                    .build()
            suspendCancellableCoroutine { continuation ->
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }.isSuccess
    }

    private suspend fun clearOverride(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // No override can have been installed through this class on an unsupported provider.
            return activeCredentials == null
        }
        return runCatching {
            suspendCancellableCoroutine { continuation ->
                ProxyController.getInstance().clearProxyOverride(executor) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }.isSuccess
    }

    private fun setServiceWorkerNetworkBlocked(blocked: Boolean): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            return !blocked
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
            return !blocked
        }
        return runCatching {
            ServiceWorkerControllerCompat.getInstance()
                .serviceWorkerWebSettings
                .blockNetworkLoads = blocked
        }.isSuccess
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
    }
}
