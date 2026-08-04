package com.foxhole.guard.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-shot DNS notices flowing from the runtime side into the open UI. The system notification
 * (which must work with the app closed) is posted by the emitter itself; this bus only feeds the
 * in-app banner when a dashboard is around to show it.
 */
internal sealed interface RuntimeDnsNotice {
    /** The connected VPN profile advertises no resolver of its own — the configured server is used. */
    data class ProviderDnsFallback(val server: String) : RuntimeDnsNotice

    /** The upstream network swapped its whole resolver set mid-life — possible DNS spoofing. */
    data class SystemDnsChanged(
        val previous: List<String>,
        val current: List<String>,
    ) : RuntimeDnsNotice
}

internal object RuntimeDnsNoticeBus {
    private val noticesMutable = MutableSharedFlow<RuntimeDnsNotice>(extraBufferCapacity = 8)

    val notices: SharedFlow<RuntimeDnsNotice> = noticesMutable

    fun tryEmit(notice: RuntimeDnsNotice) {
        noticesMutable.tryEmit(notice)
    }
}

/**
 * Watches the DNS servers of upstream (non-VPN) networks for the whole process lifetime. A network
 * that REPLACES its entire resolver set mid-life (e.g. 1.1.1.1 quietly becoming an unknown
 * address after a rogue DHCP renewal) is the classic on-path DNS substitution signal — that fires
 * a system notification and an in-app banner. Ordinary events stay silent: joining a network
 * (first observation is the baseline), losing one, or a partial change that still keeps at least
 * one of the previous resolvers (an IPv6 resolver arriving next to the IPv4 one is routine).
 */
internal class SystemDnsChangeMonitor(
    context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val baseAppContext = context.applicationContext

    // A getter rather than a snapshot: the notification must speak the app's current language even
    // after a settings change without a process restart.
    private val appContext get() = baseAppContext.withStoredAppLocale()
    private val notificationManager by lazy { appContext.getSystemService<NotificationManager>() }
    private val lock = Any()
    private val dnsByNetworkHandle = mutableMapOf<Long, List<String>>()
    private var lastAlertAtMs = 0L

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                onDnsServersObserved(
                    networkHandle = network.networkHandle,
                    servers = linkProperties.dnsServers.mapNotNull { address -> address.hostAddress },
                )
            }

            override fun onLost(network: Network) {
                synchronized(lock) { dnsByNetworkHandle.remove(network.networkHandle) }
            }
        }

    fun start() {
        val connectivity = appContext.getSystemService<ConnectivityManager>() ?: return
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
    }

    internal fun onDnsServersObserved(
        networkHandle: Long,
        servers: List<String>,
    ) {
        val normalized = servers.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) {
            // A transient empty list (network re-provisioning) is not a change signal; keep the
            // last known baseline so the real replacement is still caught.
            return
        }
        val alert: Pair<List<String>, List<String>>? =
            synchronized(lock) {
                val previous = dnsByNetworkHandle[networkHandle]
                dnsByNetworkHandle[networkHandle] = normalized
                when {
                    previous.isNullOrEmpty() -> null
                    previous == normalized -> null
                    // Partial overlap = ordinary reconfiguration; a FULL replacement is the alarm.
                    previous.intersect(normalized.toSet()).isNotEmpty() -> null
                    !consumeAlertBudgetLocked() -> null
                    else -> previous to normalized
                }
            }
        if (alert != null) {
            notifySystemDnsChanged(previous = alert.first, current = alert.second)
            RuntimeDnsNoticeBus.tryEmit(
                RuntimeDnsNotice.SystemDnsChanged(previous = alert.first, current = alert.second),
            )
        }
    }

    private fun consumeAlertBudgetLocked(): Boolean {
        val now = clock()
        if (now - lastAlertAtMs < ALERT_MIN_INTERVAL_MS) {
            return false
        }
        lastAlertAtMs = now
        return true
    }

    private fun notifySystemDnsChanged(
        previous: List<String>,
        current: List<String>,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val body =
            appContext.getString(
                R.string.dns_change_notification_body,
                previous.joinToString(", "),
                current.joinToString(", "),
            )
        val openAppIntent =
            PendingIntent.getActivity(
                appContext,
                REQUEST_CODE_OPEN,
                Intent(appContext, CliMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_dns)
                .setContentTitle(appContext.getString(R.string.dns_change_notification_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.dns_change_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                description = appContext.getString(R.string.dns_change_notification_channel_description)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "foxhole_dns_alerts"
        const val NOTIFICATION_ID = 9420
        const val REQUEST_CODE_OPEN = 212
        const val ALERT_MIN_INTERVAL_MS = 15L * 60L * 1_000L
    }
}
