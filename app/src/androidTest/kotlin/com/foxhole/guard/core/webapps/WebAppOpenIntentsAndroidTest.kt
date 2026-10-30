package com.foxhole.guard.core.webapps

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.ui.cli.WebAppLaunchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebAppOpenIntentsAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun exportedLauncherRejectsNumericIdsWithoutAnAuthenticatedCommand() {
        val untrusted = Intent(context, CliMainActivity::class.java)
            .putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, TEST_APP_ID)
        assertNull(WebAppOpenIntents.consume(context, untrusted))

        val trusted = WebAppOpenIntents.authorizedMainIntent(context, TEST_APP_ID)
        val missingToken = Intent(trusted).replaceExtras(
            Bundle().apply { putLong(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, TEST_APP_ID) },
        )
        assertNull(WebAppOpenIntents.consume(context, missingToken))
        assertNull(
            WebAppOpenIntents.consume(
                context,
                Intent(trusted).putExtra(WebAppOpenIntents.EXTRA_TOKEN, "forged"),
            ),
        )
        assertNotNull(WebAppOpenIntents.consume(context, trusted))
        assertFalse(trusted.hasExtra(WebAppOpenIntents.EXTRA_TOKEN))
        assertFalse(trusted.hasExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID))
    }

    @Test
    fun trustedCommandsCannotBeRetargetedOrReplayed() {
        val trusted = WebAppOpenIntents.authorizedMainIntent(context, TEST_APP_ID)
        val replay = Intent(trusted)
        val retargeted = Intent(trusted).putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, TEST_APP_ID - 1L)
        assertNull(WebAppOpenIntents.consume(context, retargeted))
        assertNull(WebAppOpenIntents.consume(context, Intent(trusted).setAction(Intent.ACTION_VIEW)))
        assertNull(WebAppOpenIntents.consume(context, Intent(trusted).setClass(context, WebAppLaunchActivity::class.java)))
        assertNull(
            WebAppOpenIntents.consume(
                context,
                Intent(trusted).putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, "not-a-number"),
            ),
        )
        assertEquals(TEST_APP_ID, WebAppOpenIntents.consume(context, trusted)?.appId)
        assertNull(WebAppOpenIntents.consume(context, replay))
    }

    @Test
    fun widgetAndNotificationLaunchTargetsArePrivateAndDistinctPerApp() {
        val first = WebAppOpenIntents.launchIntent(context, TEST_APP_ID)
        val second = WebAppOpenIntents.launchIntent(context, TEST_APP_ID - 1L)
        assertEquals(ComponentName(context, WebAppLaunchActivity::class.java), first.component)
        assertEquals(TEST_APP_ID, WebAppOpenIntents.launchTarget(context, first))
        assertFalse(first.filterEquals(second))
        assertNull(WebAppOpenIntents.launchTarget(context, Intent(first).setClass(context, CliMainActivity::class.java)))
        assertNull(
            WebAppOpenIntents.launchTarget(
                context,
                Intent(first).putExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, -1L),
            ),
        )
    }

    @Test
    fun anotherUidCannotStartThePrivateLaunchActivity() {
        val manager = context.packageManager
        val privateComponent = ComponentName(context, WebAppLaunchActivity::class.java)
        assertFalse(manager.getActivityInfo(privateComponent, 0).exported)
        assertTrue(manager.getActivityInfo(ComponentName(context, CliMainActivity::class.java), 0).exported)
        assertEquals("2000", shellOutput("id -u").trim())
        val action = checkNotNull(WebAppOpenIntents.launchIntent(context, TEST_APP_ID).action)
        val output = shellOutput(
            "am start -W -n ${privateComponent.flattenToString()} -a $action " +
                "--el ${CliMainActivity.EXTRA_OPEN_WEB_APP_ID} $TEST_APP_ID",
        )
        assertTrue(output, output.contains("Permission Denial") || output.contains("not exported"))
    }

    @Test
    fun trustedNotificationPendingIntentReachesMainThroughThePrivateActivity() {
        val launchMain = Intent(context, CliMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CliMainActivity>(launchMain).use { scenario ->
            val pending = WebAppOpenIntents.notificationPendingIntent(context, TEST_APP_ID, TEST_REQUEST_CODE)
            try {
                assertEquals(context.packageName, pending.creatorPackage)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(pending.isImmutable)
                pending.send()
                awaitAuthorizedDelivery(scenario)
                pending.send()
                awaitAuthorizedDelivery(scenario)
            } finally {
                pending.cancel()
            }
        }
    }

    private fun awaitAuthorizedDelivery(scenario: ActivityScenario<CliMainActivity>) {
        var delivered = false
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!delivered && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                delivered = activity.intent.action == WebAppOpenIntents.ACTION_OPEN &&
                    !activity.intent.hasExtra(WebAppOpenIntents.EXTRA_TOKEN) &&
                    !activity.intent.hasExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID)
                if (delivered) activity.intent.action = null
            }
            if (!delivered) SystemClock.sleep(25L)
        }
        assertTrue("trusted notification did not reach the authenticated launcher path", delivered)
    }

    private fun shellOutput(command: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return legacyShellOutput(command)
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe(command)
        return try {
            descriptors[1].close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
            val errors = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
            output + errors
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    private fun legacyShellOutput(command: String): String {
        check(command.matches(Regex("[A-Za-z0-9_./ -]+")))
        val directory = checkNotNull(context.externalCacheDir)
        val script = File(directory, "foxhole-boundary-${UUID.randomUUID()}.sh")
        check(script.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        try {
            // Runtime.exec(String) does not parse shell quoting; the shell reads this own fixture.
            script.writeText("#!/system/bin/sh\n$command 2>&1\n")
            val descriptor = instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        } finally {
            check(!script.exists() || script.delete()) { "Could not remove the shell boundary fixture" }
        }
    }

    private companion object {
        const val TEST_APP_ID = Long.MAX_VALUE - 42L
        const val TEST_REQUEST_CODE = 44_042
    }
}
