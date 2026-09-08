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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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

internal class WebAppProxyLease internal constructor(val generation: Long) {
    private val valid = AtomicBoolean(true)
    val active: Boolean get() = valid.get()
    internal fun revoke() { valid.set(false) }
}

internal data class WebAppProxyActivation(
    val applied: Boolean,
    val credentials: WebAppProxyCredentials? = null,
    val lease: WebAppProxyLease? = null,
)

internal data class WebAppForegroundSession(
    val app: com.foxhole.guard.core.data.WebAppEntity,
    val activation: WebAppProxyActivation,
)

internal fun WebAppProxyPlan.mustBlockServiceWorkerNetwork(): Boolean = this is WebAppProxyPlan.Http

internal class WebAppProxyController(
    context: Context,
    private val planProvider: suspend (WebAppRoute, Boolean) -> WebAppProxyPlan?,
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val switchMutex = Mutex()
    private var generation = 0L
    private var lease: WebAppProxyLease? = null

    @Volatile
    var activeCredentials: WebAppProxyCredentials? = null
        private set

    suspend fun activate(
        route: WebAppRoute,
        blockWithoutTunnel: Boolean,
    ): WebAppProxyActivation =
        switchMutex.withLock {
            if (lease != null) return@withLock WebAppProxyActivation(applied = false)
            val plan = planProvider(route, blockWithoutTunnel)
                ?: return@withLock WebAppProxyActivation(applied = false)
            when (plan) {
                WebAppProxyPlan.Direct -> {
                    if (!clearOverride() || !setServiceWorkerNetworkBlocked(plan.mustBlockServiceWorkerNetwork())) {
                        return@withLock WebAppProxyActivation(applied = false)
                    }
                    activeCredentials = null
                    activation(null)
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
                    activation(credentials)
                }
            }
        }

    private fun activation(credentials: WebAppProxyCredentials?): WebAppProxyActivation {
        val acquired = WebAppProxyLease(++generation)
        lease = acquired
        return WebAppProxyActivation(applied = true, credentials = credentials, lease = acquired)
    }

    suspend fun clear(expected: WebAppProxyLease): Boolean =
        switchMutex.withLock {
            if (lease !== expected) return@withLock true
            expected.revoke()
            val overrideCleared = clearOverride()
            val workerNetworkRestored = overrideCleared && setServiceWorkerNetworkBlocked(false)
            (overrideCleared && workerNetworkRestored).also { cleared ->
                if (cleared) {
                    activeCredentials = null
                    lease = null
                }
            }
        }

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
            withContext(NonCancellable) {
                suspendCancellableCoroutine { continuation ->
                    ProxyController.getInstance().setProxyOverride(config, executor) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }.isSuccess
    }

    private suspend fun clearOverride(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return activeCredentials == null
        }
        return runCatching {
            withContext(NonCancellable) {
                suspendCancellableCoroutine { continuation ->
                    ProxyController.getInstance().clearProxyOverride(executor) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
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
