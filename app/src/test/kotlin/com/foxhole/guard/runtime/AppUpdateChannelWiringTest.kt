package com.foxhole.guard.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateChannelWiringTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `the scheduled check follows the worker conventions`() {
        val application = projectSource("app/src/main/kotlin/com/foxhole/guard/FoxholeApplication.kt")
        val schedule =
            application
                .substringAfter("fun Context.applyAppUpdateSchedule")
                .substringBefore("internal const val APP_UPDATE_INTERVAL_HOURS")

        assertTrue("a disabled schedule must cancel, not merely skip", schedule.contains("cancelUniqueWork"))
        assertTrue(schedule.contains("NetworkType.CONNECTED"))
        assertTrue(schedule.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(schedule.contains("BackoffPolicy.EXPONENTIAL"))
        assertTrue(schedule.contains("enqueueUniquePeriodicWork"))
        assertTrue(application.contains("internal const val APP_UPDATE_INTERVAL_HOURS = 24L"))
    }

    @Test
    fun `scheduling is gated on the channel and the updates master switch`() {
        val application = projectSource("app/src/main/kotlin/com/foxhole/guard/FoxholeApplication.kt")
        val call =
            application
                .substringAfter("applyAppUpdateSchedule(")
                .substringBefore(")")

        assertTrue(call.contains("AppUpdatePolicy.backgroundCheckAllowed"))
        assertTrue(call.contains("componentUpdatesPermitted"))
    }

    @Test
    fun `the worker re-checks the gate itself`() {
        val worker = runtimeSource("AppUpdateWorker.kt")

        assertTrue(worker.contains("AppUpdatePolicy.backgroundCheckAllowed"))
        assertTrue(worker.contains("BuildConfig.UPDATE_CHANNEL"))
        assertTrue(worker.contains("componentUpdateCheckEnabled"))
        assertTrue("a failed probe must be retried, not swallowed", worker.contains("Result.retry()"))
    }

    @Test
    fun `toggling the updates master switch re-applies the app update schedule`() {
        val support =
            projectSource(
                "app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelComponentUpdatesSupport.kt",
            )
        val schedules =
            support
                .substringAfter("private fun HomeViewModel.applyComponentUpdateSchedules")
                .substringBefore("internal fun HomeViewModel.onDeleteDownloadedComponentData")

        assertTrue(schedules.contains("applyAppUpdateSchedule"))
        assertTrue(schedules.contains("AppUpdatePolicy.backgroundCheckAllowed"))
    }

    @Test
    fun `a background failure never paints the updates screen red`() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val repository = AppUpdateRepository(
            controlledClient(factory, StandardTestDispatcher(testScheduler)),
            1,
            temporary.newFolder(),
            currentVersionName = "0.1.0"
        )
        val first = async { repository.check() }
        runCurrent()
        factory.calls.last().respond("""{"tag_name":"v0.1.2","assets":[]}""".toResponseBody())
        runCurrent()
        first.await()
        val previous = repository.state.value
        val background = async { repository.checkInBackground() }
        runCurrent()
        factory.calls.last().respond("unavailable".toResponseBody(), 503)
        runCurrent()

        assertTrue(background.await() is AppUpdateCheck.Failed)
        assertEquals(previous, repository.state.value)
    }

    @Test
    fun `the downloaded state is unreachable without the package identity gate`() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val repository = AppUpdateRepository(
            controlledClient(factory, StandardTestDispatcher(testScheduler)),
            1,
            directory,
            apkVerifier = { _, _ -> Result.failure(AppUpdateVerificationException("fixture foreign package")) },
            currentVersionName = "0.1.0",
            verifierDispatcher = StandardTestDispatcher(testScheduler),
        )
        val bytes = ByteArray(32) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val update = AppUpdateCheck.Available(
            AppUpdateManifest(2, "0.1.2", "foxhole.apk", digest),
            "https://cdn.example/foxhole.apk",
            bytes.size.toLong()
        )
        val operation = async { repository.download(update) }
        runCurrent()
        factory.calls.last().respond(bytes.toResponseBody())
        runCurrent()

        assertTrue(operation.await().isFailure)
        assertEquals(AppUpdateFailure.VERIFICATION, (repository.state.value as AppUpdateState.Failed).failure)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun controlledClient(factory: AppUpdateCancellationTest.HeldFactory, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = AppUpdateClient(
        OkHttpClient(),
        resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
        releasesApiUrl = { "https://api.example/release" },
        operationTimeoutMs = 10000,
        ioDispatcher = dispatcher,
        callFactory = factory,
    )

    @Test
    fun `the verifier refuses a foreign package, version or certificate`() {
        val verifier = runtimeSource("AppUpdateApkVerifier.kt")

        assertTrue(verifier.contains("archive.packageName != appContext.packageName"))
        assertTrue(verifier.contains("archiveVersionCode != manifest.versionCode"))
        assertTrue(verifier.contains("appUpdateSignersMatch"))
        assertTrue(
            "rotated-away keys must not be accepted",
            verifier.contains("signingInfo?.apkContentsSigners"),
        )
    }

    @Test
    fun `the fdroid channel neither blinks nor offers a download`() {
        val screen =
            projectSource(
                "app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliUpdatesSubScreen.kt",
            )
        val attention = screen.substringAfter("fun rememberCliUpdatesAttention")

        assertTrue(
            "the app-update indicator must be github-only",
            attention.contains("appUpdateChannelIsGithub &&"),
        )
        assertTrue(screen.contains("if (appUpdateChannelIsGithub)"))
        assertTrue(screen.contains("AppUpdateBuildSignals.fdroidUpdateNoticeRequired()"))
        val notice =
            screen
                .substringAfter("private fun CliFdroidUpdateNotice")
                .substringBefore("private data class FoxholeDbPhases")
        assertTrue("the notice must be red", notice.contains("colors.err"))
        assertFalse("the notice must not offer a self-update action", notice.contains("CliButton"))
    }

    @Test
    fun `the fdroid notice reads only build-stamped signals`() {
        val signals = runtimeSource("AppUpdateBuildSignals.kt")

        assertTrue(signals.contains("BuildConfig.UPDATE_FLOOR_VERSION_CODE"))
        assertTrue(signals.contains("BuildConfig.UPDATE_SUPPORTED_UNTIL_EPOCH_DAY"))
        assertFalse("no network may back the fdroid notice", signals.contains("http"))
        assertFalse(signals.contains("Repository"))
    }

    @Test
    fun `the cold-start notice fires before the update-check master switch is consulted`() {
        val support =
            projectSource(
                "app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelComponentUpdatesSupport.kt",
            )
        val supervisor =
            support
                .substringAfter("fun HomeViewModel.superviseComponentUpdateAvailabilityInternal")
                .substringBefore("internal suspend fun HomeViewModel.raiseFdroidUpdateNoticeInternal")

        assertTrue(supervisor.contains("raiseFdroidUpdateNoticeInternal()"))
        assertTrue(
            supervisor.indexOf("raiseFdroidUpdateNoticeInternal()") <
                supervisor.indexOf("componentUpdateCheckEnabled"),
        )
        assertTrue(support.contains("errorBanner(R.string.cli_updates_fdroid_banner)"))
    }

    @Test
    fun `both update notice strings are localized`() {
        listOf("app/src/main/res/values/strings.xml", "app/src/main/res/values-ru/strings.xml")
            .map(::projectSource)
            .forEach { strings ->
                listOf(
                    "cli_updates_fdroid_title",
                    "cli_updates_fdroid_notice",
                    "cli_updates_fdroid_banner",
                    "app_update_notification_title",
                    "app_update_notification_body",
                    "app_update_notification_channel_name",
                    "app_update_notification_channel_description",
                ).forEach { key ->
                    assertTrue("$key is missing a translation", strings.contains("\"$key\""))
                }
            }
    }

    private fun runtimeSource(name: String): String =
        projectSource("app/src/main/kotlin/com/foxhole/guard/runtime/$name")

    private fun projectSource(path: String): String =
        listOf(File(path), File("../$path"), File(path.removePrefix("app/")))
            .first(File::isFile)
            .readText()
}
