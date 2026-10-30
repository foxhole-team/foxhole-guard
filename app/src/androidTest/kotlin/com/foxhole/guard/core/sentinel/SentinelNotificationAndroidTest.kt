package com.foxhole.guard.core.sentinel

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.Formatter
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.AnomalyNotifier
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.withStoredAppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SentinelNotificationAndroidTest {
    private lateinit var context: Context
    private lateinit var localized: Context
    private lateinit var manager: NotificationManager

    @Before
    fun prepareOwnedDebugNotificationPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext.applicationContext
        assumeTrue("Notification smoke requires the owned debug target", BuildConfig.DEBUG)
        assumeTrue("Notification smoke requires the debug package", context.packageName.endsWith(".debug"))
        require(context.packageName.matches(Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+")))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val output = instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            )
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        assumeTrue(
            "Android notifications are disabled for the QA target",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        localized = context.withStoredAppLocale()
        manager = requireNotNull(context.getSystemService<NotificationManager>())
    }

    @Test
    fun trafficNoticeIsRegisteredWithPrivateLocalizedContentAndOwnClick() {
        val marker = ownedMarker()
        val event = AnomalyEvent(
            createdAtMs = System.currentTimeMillis(),
            type = AnomalyType.TOTAL_TRAFFIC_SPIKE,
            severity = AnomalySeverity.NOTIFICATION,
            score = 75,
            reason = "owned_notification_fixture",
            evidence = mapOf("bytes_per_min" to "2048", "robust_z" to marker),
        )
        val notifier = AnomalyNotifier(context)
        notifier.ensureChannel()
        val spec = OwnedNotice(
            id = 8402,
            channelId = AnomalyNotifier.CHANNEL_ID,
            title = localized.getString(R.string.anomaly_notification_orange_flag_title),
            body = localized.getString(
                R.string.anomaly_reason_total_spike,
                Formatter.formatShortFileSize(localized, 2048),
                marker,
            ),
            marker = marker,
        )
        withOwnedNotice(spec, { assertTrue(notifier.notify(event)) }) { notification ->
            assertActivityIntent(
                notification.contentIntent,
                spec.id,
                Intent(context, CliMainActivity::class.java),
            )
            assertTrue(notification.actions.isNullOrEmpty())
        }
    }

    @Test
    fun staticReferenceNoticeIsRegisteredWithoutTurningItIntoAThreatVerdict() {
        val marker = ownedMarker()
        val packageName = "org.foxhole.owned.$marker"
        val notifier = AnomalyNotifier(context)
        notifier.ensureChannel()
        val event = AnomalyEvent(
            createdAtMs = System.currentTimeMillis(),
            type = AnomalyType.APP_STATIC_REFERENCE,
            severity = AnomalySeverity.ACTIVITY_LOG,
            score = 0,
            reason = "static_reference_presence",
            evidence = mapOf("reference_count" to "1", "rule_count" to "1"),
            packageName = packageName,
        )
        val spec = OwnedNotice(
            id = 8410,
            channelId = AnomalyNotifier.CHANNEL_ID,
            title = localized.getString(R.string.sentinel_static_notification_title),
            body = localized.getString(R.string.sentinel_static_reference_summary, packageName, "1", "1"),
            marker = marker,
        )
        withOwnedNotice(spec, { assertTrue(notifier.notify(event)) }) { notification ->
            assertActivityIntent(
                notification.contentIntent,
                spec.id,
                Intent(context, CliMainActivity::class.java),
            )
            assertTrue(notification.actions.isNullOrEmpty())
        }
    }

    @Test
    fun installedAppNoticeRegistersOnlyUserClickSettingsAndUninstallActions() {
        val marker = ownedMarker()
        val packageName = "org.foxhole.owned.$marker"
        val summary = InstalledAppSecuritySummary(
            packageName = packageName,
            label = "Owned QA $marker",
            isSystemApp = false,
            installerPackageName = null,
            riskLevel = InstalledAppRiskLevel.LOW,
            riskSignals = emptyList(),
        )
        val notifier = InstalledAppSecurityNotifier(context)
        notifier.ensureChannel()
        val spec = OwnedNotice(
            id = InstalledAppSecurityNotifier.NOTIFICATION_ID,
            tag = packageName,
            channelId = InstalledAppSecurityNotifier.CHANNEL_ID,
            title = localized.getString(R.string.installed_app_notification_title),
            body = localized.getString(
                R.string.installed_app_notification_body,
                summary.label,
                localized.getString(R.string.installed_app_source_unknown),
                localized.getString(R.string.installed_app_risk_low),
                localized.getString(R.string.installed_app_risk_signals_none),
            ),
            marker = marker,
        )
        withOwnedNotice(spec, { notifier.notifyInstalledApp(summary) }) { notification ->
            val settings =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            assertActivityIntent(notification.contentIntent, 110 + packageName.hashCode(), settings)
            val actions = notification.actions.orEmpty()
            assertEquals(3, actions.size)
            assertActivityIntent(
                actions[0].actionIntent,
                140 + packageName.hashCode(),
                Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()),
            )
            assertActivityIntent(actions[1].actionIntent, 120 + packageName.hashCode(), settings)
            assertActivityIntent(actions[2].actionIntent, 130 + packageName.hashCode(), settings)
        }
    }

    private fun withOwnedNotice(
        spec: OwnedNotice,
        publish: () -> Unit,
        verifyClick: (Notification) -> Unit,
    ) {
        val channel = requireNotNull(manager.getNotificationChannel(spec.channelId))
        assumeTrue(
            "QA notification channel is disabled",
            channel.importance != NotificationManager.IMPORTANCE_NONE,
        )
        val group = channel.group
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && group != null) {
            assumeTrue(
                "QA notification channel group is disabled",
                manager.getNotificationChannelGroup(group)?.isBlocked != true,
            )
        }
        assumeTrue(
            "Notification ID is already occupied; existing notice is preserved",
            activeNotice(spec) == null,
        )
        assertTrue(spec.body.contains(spec.marker))
        try {
            publish()
            val active = awaitOwnedNotice(spec)
            val notification = active.notification
            assertEquals(context.packageName, active.packageName)
            assertEquals(spec.channelId, notification.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(Notification.CATEGORY_STATUS, notification.category)
            assertEquals(
                spec.title,
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )
            assertEquals(spec.body, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertNull(notification.fullScreenIntent)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            verifyClick(notification)
        } finally {
            if (activeNotice(spec)?.notification?.containsMarker(spec.marker) == true) {
                manager.cancel(spec.tag, spec.id)
                val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
                while (activeNotice(spec)?.notification?.containsMarker(spec.marker) == true &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(POLL_MS)
                }
                assertFalse(
                    "Owned notification cleanup did not settle",
                    activeNotice(spec)?.notification?.containsMarker(spec.marker) == true,
                )
            }
        }
    }

    private fun awaitOwnedNotice(spec: OwnedNotice): StatusBarNotification {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val notification = activeNotice(spec)
            if (notification?.notification?.containsMarker(spec.marker) == true) return notification
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Owned Sentinel notification was not registered by Android")
    }

    private fun activeNotice(spec: OwnedNotice): StatusBarNotification? =
        manager.activeNotifications.firstOrNull {
            it.id == spec.id && it.tag == spec.tag
        }

    private fun Notification.containsMarker(marker: String): Boolean =
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains(marker) == true

    private fun assertActivityIntent(actual: PendingIntent?, requestCode: Int, intent: Intent) {
        assertNotNull(actual)
        val pending = requireNotNull(actual)
        assertEquals(context.packageName, pending.creatorPackage)
        assertEquals(context.applicationInfo.uid, pending.creatorUid)
        val expected = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull("Expected immutable activity PendingIntent is absent", expected)
        assertEquals(expected, pending)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(pending.isImmutable)
            assertTrue(pending.isActivity)
        }
    }

    private fun ownedMarker(): String = "n" + UUID.randomUUID().toString().replace("-", "")

    private data class OwnedNotice(
        val id: Int,
        val tag: String? = null,
        val channelId: String,
        val title: String,
        val body: String,
        val marker: String,
    )

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 5_000L
        const val POLL_MS = 40L
    }
}
