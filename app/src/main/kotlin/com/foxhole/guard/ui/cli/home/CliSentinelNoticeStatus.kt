package com.foxhole.guard.ui.cli.home

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.InstalledAppSecurityNotifier
import com.foxhole.guard.core.sentinel.anomaly.SentinelDetectionNotifier
import com.foxhole.guard.core.sentinel.sentinelAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class CliNotificationAccess { ALLOWED, BLOCKED, NOT_CREATED, UNKNOWN }

internal enum class CliSentinelNoticeState {
    OFF, ALLOWED, APP_BLOCKED, CHANNEL_BLOCKED, CHANNEL_PENDING, PARTLY_BLOCKED, UNAVAILABLE,
}

internal data class CliSentinelNoticeAccess(
    val app: CliNotificationAccess = CliNotificationAccess.UNKNOWN,
    val detections: CliNotificationAccess = CliNotificationAccess.UNKNOWN,
    val installations: CliNotificationAccess = CliNotificationAccess.UNKNOWN,
)

internal data class CliSentinelNoticePolicy(
    val traffic: Boolean = false,
    val staticChecks: Boolean = false,
    val appChanges: Boolean = false,
)

internal fun cliSentinelNoticePolicy(settings: Settings) = CliSentinelNoticePolicy(
    traffic = settings.anomaly.enabled && settings.anomaly.notifyUnusualTraffic,
    staticChecks = settings.anomaly.enabled && settings.anomaly.apkScan.notifyFindings,
    appChanges = settings.expert.newAppQuarantineEnabled ||
        (settings.anomaly.enabled && settings.statistics.enabled && settings.statistics.appChangesEnabled),
)

internal fun cliSentinelNoticeSummary(
    policy: CliSentinelNoticePolicy,
    access: CliSentinelNoticeAccess,
): CliSentinelNoticeState {
    val channels = listOfNotNull(
        access.detections.takeIf { policy.traffic || policy.staticChecks },
        access.installations.takeIf { policy.appChanges },
    )
    return when {
        channels.isEmpty() -> CliSentinelNoticeState.OFF
        access.app == CliNotificationAccess.BLOCKED -> CliSentinelNoticeState.APP_BLOCKED
        access.app != CliNotificationAccess.ALLOWED || CliNotificationAccess.UNKNOWN in channels ->
            CliSentinelNoticeState.UNAVAILABLE
        channels.all { it == CliNotificationAccess.BLOCKED } -> CliSentinelNoticeState.CHANNEL_BLOCKED
        CliNotificationAccess.BLOCKED in channels -> CliSentinelNoticeState.PARTLY_BLOCKED
        CliNotificationAccess.NOT_CREATED in channels -> CliSentinelNoticeState.CHANNEL_PENDING
        else -> CliSentinelNoticeState.ALLOWED
    }
}

@Composable
internal fun cliSentinelNoticeRows(settings: Settings, detailed: Boolean): List<CliTerminalRow> {
    val policy = cliSentinelNoticePolicy(settings)
    val access = rememberSentinelNoticeAccess(policy)
    val rows = listOf(
        sentinelNoticeRow(R.string.cli_status_sentinel_notices, cliSentinelNoticeSummary(policy, access)),
    )
    if (!detailed) return rows
    return rows + listOf(
        sentinelNoticeRow(
            R.string.cli_status_sentinel_traffic_notices,
            cliSentinelNoticeSummary(policy.copy(staticChecks = false, appChanges = false), access),
        ),
        sentinelNoticeRow(
            R.string.cli_status_sentinel_static_notices,
            cliSentinelNoticeSummary(policy.copy(traffic = false, appChanges = false), access),
        ),
        sentinelNoticeRow(
            R.string.cli_status_sentinel_install_notices,
            cliSentinelNoticeSummary(policy.copy(traffic = false, staticChecks = false), access),
        ),
    )
}

@Composable
private fun sentinelNoticeRow(@StringRes label: Int, state: CliSentinelNoticeState): CliTerminalRow = CliTerminalRow(
    key = stringResource(label),
    value = stringResource(
        when (state) {
            CliSentinelNoticeState.OFF -> R.string.cli_status_notices_off
            CliSentinelNoticeState.ALLOWED -> R.string.cli_status_notices_allowed
            CliSentinelNoticeState.APP_BLOCKED -> R.string.cli_status_notices_android_blocked
            CliSentinelNoticeState.CHANNEL_BLOCKED -> R.string.cli_status_notices_channel_blocked
            CliSentinelNoticeState.CHANNEL_PENDING -> R.string.cli_status_notices_channel_pending
            CliSentinelNoticeState.PARTLY_BLOCKED -> R.string.cli_status_notices_partly_blocked
            CliSentinelNoticeState.UNAVAILABLE -> R.string.cli_status_notices_unavailable
        },
    ),
    tone = when (state) {
        CliSentinelNoticeState.OFF -> CliLineTone.DIM
        CliSentinelNoticeState.ALLOWED -> CliLineTone.INFO
        CliSentinelNoticeState.CHANNEL_PENDING -> CliLineTone.PENDING
        else -> CliLineTone.WARN
    },
)

@Composable
private fun rememberSentinelNoticeAccess(policy: CliSentinelNoticePolicy): CliSentinelNoticeAccess {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember(context) { mutableStateOf(CliSentinelNoticeAccess()) }
    LifecycleResumeEffect(context, policy) {
        access = CliSentinelNoticeAccess()
        val job = scope.launch {
            access = withContext(Dispatchers.IO) { readSentinelNoticeAccess(context) }
        }
        onPauseOrDispose { job.cancel() }
    }
    return access
}

private fun readSentinelNoticeAccess(context: Context): CliSentinelNoticeAccess = sentinelAttempt {
    val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val app = if (permission && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        CliNotificationAccess.ALLOWED
    } else {
        CliNotificationAccess.BLOCKED
    }
    val manager = context.getSystemService<NotificationManager>()
    CliSentinelNoticeAccess(
        app = app,
        detections = sentinelChannelAccess(manager, SentinelDetectionNotifier.CHANNEL_ID),
        installations = sentinelChannelAccess(manager, InstalledAppSecurityNotifier.CHANNEL_ID),
    )
}.getOrDefault(CliSentinelNoticeAccess())

private fun sentinelChannelAccess(manager: NotificationManager?, id: String): CliNotificationAccess {
    if (manager == null) return CliNotificationAccess.UNKNOWN
    val channel = manager.getNotificationChannel(id) ?: return CliNotificationAccess.NOT_CREATED
    val group = channel.group
    val groupBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && group != null &&
        manager.getNotificationChannelGroup(group)?.isBlocked == true
    return if (channel.importance == NotificationManager.IMPORTANCE_NONE || groupBlocked) {
        CliNotificationAccess.BLOCKED
    } else {
        CliNotificationAccess.ALLOWED
    }
}

internal fun cliSentinelEventTone(severity: AnomalySeverity): CliLineTone = when (severity) {
    AnomalySeverity.SILENT -> CliLineTone.DIM
    AnomalySeverity.ACTIVITY_LOG -> CliLineTone.INFO
    AnomalySeverity.NOTIFICATION -> CliLineTone.WARN
    AnomalySeverity.HIGH -> CliLineTone.ERR
}
