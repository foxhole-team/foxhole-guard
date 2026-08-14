package com.foxhole.guard.core.sentinel.anomaly

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.core.content.getSystemService
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.TunnelAppTrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class UsageAccessState {
    GRANTED,
    DENIED,
    INDETERMINATE,
}

internal object UsageStatsAccess {
    fun state(context: Context): UsageAccessState {
        val appContext = context.applicationContext
        val appOps = appContext.getSystemService<AppOpsManager>() ?: return UsageAccessState.INDETERMINATE
        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        return usageAccessStateForMode(
            mode = mode,
            hasQueryableUsage = mode == AppOpsManager.MODE_DEFAULT && hasQueryableUsageStats(appContext),
        )
    }

    fun isGranted(context: Context): Boolean = state(context) == UsageAccessState.GRANTED

    private fun hasQueryableUsageStats(context: Context): Boolean {
        val usageStatsManager = context.getSystemService<UsageStatsManager>() ?: return false
        val now = System.currentTimeMillis()
        return runCatching {
            usageStatsManager
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - USAGE_STATS_PROBE_WINDOW_MS, now)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    private const val USAGE_STATS_PROBE_WINDOW_MS = 24L * 60L * 60L * 1000L
}

/**
 * MODE_DEFAULT is deliberately not denial. Android defines it as "use the default security
 * check", and an empty UsageStats query can also mean a freshly installed or idle device. Treating
 * that combination as revoked used to persist both app-traffic flags as false and erase privacy
 * data merely by opening the statistics sheet after an APK update.
 */
internal fun usageAccessStateForMode(
    mode: Int,
    hasQueryableUsage: Boolean,
): UsageAccessState =
    when (mode) {
        AppOpsManager.MODE_ALLOWED -> UsageAccessState.GRANTED
        AppOpsManager.MODE_IGNORED,
        AppOpsManager.MODE_ERRORED -> UsageAccessState.DENIED
        // When the op reads as its default the package-scoped app-op is inconclusive — this happens
        // on ROMs that grant Usage Access at the UID level, where checkOpNoThrow(uid, package)
        // returns MODE_DEFAULT even though access is granted. checkPermission(PACKAGE_USAGE_STATS)
        // is the wrong fallback (regular apps never hold that system permission), so it always read
        // as denied and the app-traffic feature could never enable. A non-empty real query
        // proves access; an empty query proves nothing and therefore stays indeterminate.
        AppOpsManager.MODE_DEFAULT ->
            if (hasQueryableUsage) UsageAccessState.GRANTED else UsageAccessState.INDETERMINATE
        else -> UsageAccessState.INDETERMINATE
    }

class AndroidNetworkTypeProvider(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager by lazy { appContext.getSystemService<ConnectivityManager>() }

    fun current(): NetworkType {
        val capabilities =
            connectivityManager
                ?.activeNetwork
                ?.let { network -> connectivityManager?.getNetworkCapabilities(network) }
                ?: return NetworkType.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            else -> NetworkType.UNKNOWN
        }
    }
}

class AppTrafficSampler(
    context: Context,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val networkStatsManager by lazy { appContext.getSystemService<NetworkStatsManager>() }
    private val networkTypeProvider = AndroidNetworkTypeProvider(appContext)
    private val installedApplicationsLock = Any()
    private var installedApplicationsCachedAtMs: Long = 0L
    private var installedApplicationsCache: List<ApplicationInfo> = emptyList()
    private val uidTrafficStatsLock = Any()
    private val uidTrafficStatsBaseline = mutableMapOf<Int, UidTrafficUsage>()
    private val sampleWatermarkLock = Any()
    private var lastSampleAt: Long = 0L

    fun hasUsageAccess(): Boolean = UsageStatsAccess.isGranted(appContext)

    suspend fun sampleWindows(minDurationMs: Long = DEFAULT_SAMPLE_WINDOW_MS): List<AppTrafficWindow> =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) {
                return@withContext emptyList()
            }
            val now = nowProvider()
            val startAt =
                synchronized(sampleWatermarkLock) {
                    val reservedStartAt =
                        (lastSampleAt.takeIf { it > 0L } ?: (now - minDurationMs)).coerceAtMost(now - 1L)
                    lastSampleAt = maxOf(lastSampleAt, now)
                    reservedStartAt
                }
            val durationMs = (now - startAt).coerceAtLeast(1L)
            val manager = networkStatsManager
            val usageSummaryByUid =
                manager
                    ?.queryUidUsageSummary(startAt, now)
                    .orEmpty()
            val networkType = networkTypeProvider.current()
            // Tunneled per-app bytes accumulated from FoxCore flows: the platform never
            // attributes them to WIFI/MOBILE (they ride the VPN network ident), so they must be
            // merged in per package — otherwise per-app stats only ever see the untunneled trickle.
            val tunnelDeltas = TunnelAppTrafficStats.drain()
            val installed = cachedInstalledApplications(now)
            val installedPackages = installed.mapTo(mutableSetOf(), ApplicationInfo::packageName)
            val durationSec = (durationMs / 1000L).toInt().coerceAtLeast(1)
            val platformWindows =
                installed
                    .asSequence()
                    .filterNot { app -> app.packageName == appContext.packageName }
                    .mapNotNull { app ->
                        val platformUsage =
                            uidTrafficStatsDelta(app.uid)
                                .takeIf(UidTrafficUsage::hasTraffic)
                                ?: usageSummaryByUid[app.uid]
                                    ?.takeIf(UidTrafficUsage::hasTraffic)
                                ?: UidTrafficUsage()
                        val tunnel = tunnelDeltas[app.packageName]
                        val rx = platformUsage.rxBytes + (tunnel?.rxBytes ?: 0L)
                        val tx = platformUsage.txBytes + (tunnel?.txBytes ?: 0L)
                        if (rx <= 0L && tx <= 0L) {
                            null
                        } else {
                            AppTrafficWindow(
                                packageName = app.packageName,
                                uid = app.uid,
                                startedAtMs = startAt,
                                durationSec = durationSec,
                                rxBytes = rx,
                                txBytes = tx,
                                foreground = platformUsage.foreground,
                                networkType = networkType,
                            )
                        }
                    }
                    .toList()
            // Tunnel-only packages (present in connections but with no platform delta this pass,
            // e.g. a package resolved from process info but filtered out of the installed list).
            val tunnelOnlyWindows =
                tunnelDeltas
                    .asSequence()
                    .filter { (packageName, _) ->
                        packageName != appContext.packageName && packageName !in installedPackages
                    }
                    .map { (packageName, delta) ->
                        AppTrafficWindow(
                            packageName = packageName,
                            uid = 0,
                            startedAtMs = startAt,
                            durationSec = durationSec,
                            rxBytes = delta.rxBytes,
                            txBytes = delta.txBytes,
                            foreground = null,
                            networkType = networkType,
                        )
                    }
            (platformWindows.asSequence() + tunnelOnlyWindows)
                .distinctBy(AppTrafficWindow::packageName)
                .sortedWith(
                    compareByDescending<AppTrafficWindow> { window -> window.rxBytes + window.txBytes }
                        .thenBy(AppTrafficWindow::packageName),
                )
                .take(MAX_APP_TRAFFIC_WINDOWS_PER_SAMPLE)
                .toList()
        }

    private fun cachedInstalledApplications(now: Long): List<ApplicationInfo> =
        synchronized(installedApplicationsLock) {
            installedApplicationsCache
                .takeIf { apps ->
                    apps.isNotEmpty() && now - installedApplicationsCachedAtMs <= INSTALLED_APPS_CACHE_TTL_MS
                } ?: installedApplications()
                .also { apps ->
                    installedApplicationsCache = apps
                    installedApplicationsCachedAtMs = now
                }
        }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private fun NetworkStatsManager.queryUidUsageSummary(
        startAt: Long,
        endAt: Long,
    ): Map<Int, UidTrafficUsage> {
        val totals = mutableMapOf<Int, UidTrafficUsage>()
        networkStatsSummaryTypes().forEach { networkType ->
            runCatching {
                val bucket = NetworkStats.Bucket()
                querySummary(networkType, null, startAt, endAt).use { stats ->
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        val uid = bucket.uid
                        val rx = bucket.rxBytes.coerceAtLeast(0L)
                        val tx = bucket.txBytes.coerceAtLeast(0L)
                        if (uid > 0 && (rx > 0L || tx > 0L)) {
                            val foreground =
                                when (bucket.state) {
                                    NetworkStats.Bucket.STATE_FOREGROUND -> true
                                    else -> false
                                }
                            val previous = totals[uid] ?: UidTrafficUsage()
                            totals[uid] = previous + UidTrafficUsage(rxBytes = rx, txBytes = tx, foreground = foreground)
                        }
                    }
                }
            }
        }
        return totals
    }

    @Suppress("DEPRECATION")
    private fun networkStatsSummaryTypes(): List<Int> =
        // NetworkStatsManager.querySummary still accepts legacy ConnectivityManager type ids.
        listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)

    private fun uidTrafficStatsDelta(uid: Int): UidTrafficUsage {
        val validUid = uid > 0
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        val supported = rx != TrafficStats.UNSUPPORTED.toLong() || tx != TrafficStats.UNSUPPORTED.toLong()
        if (!validUid || !supported) return UidTrafficUsage()
        val current =
            UidTrafficUsage(
                rxBytes = rx.coerceAtLeast(0L),
                txBytes = tx.coerceAtLeast(0L),
            )
        return synchronized(uidTrafficStatsLock) {
            val previous = uidTrafficStatsBaseline.put(uid, current) ?: return@synchronized UidTrafficUsage()
            if (current.rxBytes < previous.rxBytes || current.txBytes < previous.txBytes) {
                UidTrafficUsage()
            } else {
                UidTrafficUsage(
                    rxBytes = current.rxBytes - previous.rxBytes,
                    txBytes = current.txBytes - previous.txBytes,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_WINDOW_MS = 60_000L
        const val MAX_APP_TRAFFIC_WINDOWS_PER_SAMPLE = 30
        const val INSTALLED_APPS_CACHE_TTL_MS = 5 * 60_000L
    }
}

private data class UidTrafficUsage(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val foreground: Boolean? = null,
) {
    fun hasTraffic(): Boolean = rxBytes > 0L || txBytes > 0L

    operator fun plus(other: UidTrafficUsage): UidTrafficUsage {
        val foreground =
            when {
                this.foreground == true || other.foreground == true -> true
                this.foreground == false || other.foreground == false -> false
                else -> null
            }
        return UidTrafficUsage(
            rxBytes = rxBytes + other.rxBytes,
            txBytes = txBytes + other.txBytes,
            foreground = foreground,
        )
    }
}
