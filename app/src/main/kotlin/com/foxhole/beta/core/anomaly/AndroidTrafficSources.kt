package com.foxhole.beta.core.anomaly

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.core.content.getSystemService
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UsageStatsAccess {
    fun isGranted(context: Context): Boolean {
        val appContext = context.applicationContext
        val appOps = appContext.getSystemService<AppOpsManager>() ?: return false
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName,
                )
            }
        return mode == AppOpsManager.MODE_ALLOWED
    }
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

    fun hasUsageAccess(): Boolean = UsageStatsAccess.isGranted(appContext)

    suspend fun sampleWindows(minDurationMs: Long = DEFAULT_SAMPLE_WINDOW_MS): List<AppTrafficWindow> =
        withContext(Dispatchers.IO) {
            val now = nowProvider()
            val startAt =
                synchronized(SampleWatermarkLock) {
                    val reservedStartAt =
                        (lastSampleAt.takeIf { it > 0L } ?: (now - minDurationMs)).coerceAtMost(now - 1L)
                    lastSampleAt = maxOf(lastSampleAt, now)
                    reservedStartAt
                }
            val durationMs = (now - startAt).coerceAtLeast(1L)
            if (!hasUsageAccess()) {
                return@withContext emptyList()
            }
            val manager = networkStatsManager
            val usageSummaryByUid =
                manager
                    ?.queryUidUsageSummary(startAt, now)
                    .orEmpty()
            val networkType = networkTypeProvider.current()
            val windows =
                cachedInstalledApplications(now)
                    .asSequence()
                    .filterNot { app -> app.packageName == appContext.packageName }
                    .mapNotNull { app ->
                        val usage =
                            uidTrafficStatsDelta(app.uid)
                                .takeIf(UidTrafficUsage::hasTraffic)
                                ?: usageSummaryByUid[app.uid]
                                    ?.takeIf(UidTrafficUsage::hasTraffic)
                                ?: UidTrafficUsage()
                        if (usage.rxBytes <= 0L && usage.txBytes <= 0L) {
                            null
                        } else {
                            AppTrafficWindow(
                                packageName = app.packageName,
                                uid = app.uid,
                                startedAtMs = startAt,
                                durationSec = (durationMs / 1000L).toInt().coerceAtLeast(1),
                                rxBytes = usage.rxBytes,
                                txBytes = usage.txBytes,
                                foreground = usage.foreground,
                                networkType = networkType,
                            )
                        }
                    }
                    .distinctBy(AppTrafficWindow::packageName)
                    .toList()
            windows
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
        listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE).forEach { networkType ->
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
        const val INSTALLED_APPS_CACHE_TTL_MS = 5 * 60_000L
        private val SampleWatermarkLock = Any()
        private var lastSampleAt: Long = 0L
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
