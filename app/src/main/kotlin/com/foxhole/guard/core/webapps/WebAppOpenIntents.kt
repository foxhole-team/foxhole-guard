package com.foxhole.guard.core.webapps

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.ui.cli.WebAppLaunchActivity

internal object WebAppOpenIntents {
    private const val ACTION_LAUNCH = "com.foxhole.guard.action.LAUNCH_WEB_APP"
    const val ACTION_OPEN = "com.foxhole.guard.action.OPEN_WEB_APP"
    const val EXTRA_TOKEN = "web_app_open_token"

    // Only a fresh private Activity launch can mint a command after process death.
    private val requests = WebAppOpenCommandRequests(SystemClock::elapsedRealtime)

    fun launchIntent(context: Context, appId: Long): Intent {
        require(appId > 0L)
        // Glance keys PendingIntents by Intent.filterEquals, which ignores extras.
        return Intent(context, WebAppLaunchActivity::class.java)
            .setAction(ACTION_LAUNCH)
            .setData(Uri.parse("foxhole-internal://web-app/$appId"))
            .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, appId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun notificationPendingIntent(context: Context, appId: Long, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent(context, appId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun launchTarget(context: Context, intent: Intent?): Long? =
        readTarget(context, intent, WebAppLaunchActivity::class.java, ACTION_LAUNCH)

    fun authorizedMainIntent(context: Context, appId: Long): Intent {
        val command = requests.issue(appId)
        return Intent(context, CliMainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, command.appId)
            .putExtra(EXTRA_TOKEN, command.token)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }

    fun consume(context: Context, intent: Intent?): WebAppOpenCommand? {
        val appId = readTarget(context, intent, CliMainActivity::class.java, ACTION_OPEN) ?: return null
        return try {
            val token = intent?.getStringExtra(EXTRA_TOKEN)
            intent?.removeExtra(EXTRA_TOKEN)
            intent?.removeExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID)
            requests.consume(token, appId)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun readTarget(
        context: Context,
        intent: Intent?,
        target: Class<*>,
        action: String,
    ): Long? {
        if (intent == null || intent.action != action || intent.component != ComponentName(context, target)) return null
        return try {
            intent.getLongExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, -1L).takeIf { it > 0L }
        } catch (_: RuntimeException) {
            null
        }
    }
}
