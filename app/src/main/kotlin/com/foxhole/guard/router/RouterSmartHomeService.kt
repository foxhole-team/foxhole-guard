package com.foxhole.guard.router

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.foxhole.guard.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

class RouterSmartHomeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitor get() = (application as com.foxhole.guard.FoxholeApplication).routerProfileMonitor.smartHome
    private var attached: Long? = null
    private var foreground = false
    private var observation: Job? = null
    private val lifetime = UUID.randomUUID().toString()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val epoch = intent?.getLongExtra(EXTRA_EPOCH, -1) ?: -1
        when (intent?.action) {
            ACTION_START -> {
                if (!monitor.canAttach(epoch) || !routerSmartNotificationsAvailable(this)) { monitor.foregroundStartFailed(epoch); finish(startId); return START_NOT_STICKY }
                try {
                    val manager = requireNotNull(getSystemService(NotificationManager::class.java))
                    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
                        getString(R.string.router_smart_title), NotificationManager.IMPORTANCE_LOW))
                    val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val stop = Intent(this, RouterSmartHomeService::class.java).setAction(ACTION_STOP)
                        .setData(identity(epoch)).putExtra(EXTRA_EPOCH, epoch)
                    val open = Intent(this, com.foxhole.guard.ui.cli.CliMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.lin_router)
                        .setContentTitle(getString(R.string.router_smart_title)).setContentText(getString(R.string.router_smart_notification))
                        .setContentIntent(PendingIntent.getActivity(this, 38414, open, immutable))
                        .addAction(R.drawable.lin_power, getString(R.string.router_smart_stop), PendingIntent.getService(this, 38415, stop, immutable))
                        .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).build()
                    ServiceCompat.startForeground(this, 38413, notification,
                        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                    foreground = true
                    if (!monitor.foregroundAttached(epoch)) { monitor.foregroundStartFailed(epoch); finish(startId); return START_NOT_STICKY }
                    attached = epoch
                    observation?.cancel()
                    observation = scope.launch { monitor.status.collect { state ->
                        if (attached == epoch && (!monitor.isCurrent(epoch) || state in setOf(RouterSmartMonitorState.OFF, RouterSmartMonitorState.FAILED))) finish(startId)
                    } }
                } catch (_: RuntimeException) { monitor.foregroundStartFailed(epoch); finish(startId) }
            }
            ACTION_STOP -> {
                if (attached != epoch || intent.data != null && intent.data != identity(epoch)) return START_NOT_STICKY
                if (monitor.isCurrent(epoch)) scope.launch { monitor.disableFromService(epoch); finish(startId) }
                else finish(startId)
            }
            else -> if (attached == null) finish(startId)
        }
        return START_NOT_STICKY
    }
    private fun identity(epoch: Long): Uri = Uri.parse("foxhole-router-smart://request/$lifetime/$epoch/stop")
    private fun finish(startId: Int) {
        if (!stopSelfResult(startId)) return
        attached?.let(monitor::foregroundStopped); attached = null
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }
    override fun onDestroy() {
        attached?.let(monitor::foregroundStopped); attached = null
        scope.cancel()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        internal const val ACTION_START = "com.foxhole.guard.action.ROUTER_SMART_START"
        internal const val ACTION_STOP = "com.foxhole.guard.action.ROUTER_SMART_STOP"
        internal const val EXTRA_EPOCH = "router_smart_epoch"
        internal const val CHANNEL = "router_smart_monitor_v1"
    }
}

internal fun routerSmartNotificationsAvailable(context: Context): Boolean = try {
    val manager = context.getSystemService(NotificationManager::class.java)
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 26 || (manager?.getNotificationChannel(RouterSmartHomeService.CHANNEL)?.importance
            ?: NotificationManager.IMPORTANCE_LOW) >= NotificationManager.IMPORTANCE_LOW)
} catch (_: RuntimeException) { false }
