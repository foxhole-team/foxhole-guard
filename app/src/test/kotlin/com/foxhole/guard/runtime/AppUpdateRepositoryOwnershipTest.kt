package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateRepositoryOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unsafeManifestNamesAreRefusedBeforeRequestOrFileCreation() = runTest {
        for (name in listOf("../../outside.apk", "/absolute.apk", "..\\outside.apk", "bad\u0000.apk", ".", "..")) {
            val factory = AppUpdateCancellationTest.HeldFactory()
            val directory = File(temporary.root, "cache")
            val repository = repository(factory, directory, StandardTestDispatcher(testScheduler))

            assertTrue(repository.download(available(name)).isFailure)
            assertTrue(factory.calls.isEmpty())
            assertFalse(directory.exists())
        }
    }

    @Test fun cancellingCheckRestoresItsPriorState() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val repository = repository(factory, temporary.newFolder(), StandardTestDispatcher(testScheduler))
        val operation = async { repository.check() }
        runCurrent()
        assertTrue(repository.state.value is AppUpdateState.Busy)

        operation.cancel()
        runCurrent()

        assertEquals(AppUpdateState.Idle, repository.state.value)
        assertTrue(factory.calls.single().isCanceled())
    }

    @Test fun cancellingDownloadRestoresAnAvailableOffer() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val repository = repository(factory, directory, StandardTestDispatcher(testScheduler))
        val update = available()
        val operation = async { repository.download(update) }
        runCurrent()
        operation.cancel()
        runCurrent()

        assertEquals(AppUpdateState.Available(update), repository.state.value)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun resetFencesProgressAndLateDownloadCompletion() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val repository = repository(factory, directory, StandardTestDispatcher(testScheduler))
        val operation = async { repository.download(available()) }
        runCurrent()
        repository.reset()
        runCurrent()
        factory.calls.single().respond(PAYLOAD.toResponseBody())
        runCurrent()

        assertTrue(operation.isCancelled)
        assertTrue(factory.calls.single().isCanceled())
        assertEquals(AppUpdateState.Idle, repository.state.value)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val next = async { repository.download(available()) }
        runCurrent()
        assertEquals(2, factory.calls.size)
        factory.calls.last().respond(PAYLOAD.toResponseBody())
        runCurrent()
        assertTrue(next.await().isSuccess)
    }

    @Test fun backgroundCheckKeepsTheSameVerifiedUpdateReady() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val repository = repository(factory, directory, StandardTestDispatcher(testScheduler))
        val first = async { repository.download(available()) }
        runCurrent()
        factory.calls.last().respond(PAYLOAD.toResponseBody())
        runCurrent()
        val artifact = first.await().getOrThrow()
        val background = async { repository.checkInBackground() }
        runCurrent()
        factory.calls.last().respond(
            """{"tag_name":"v0.1.2","assets":[{"name":"update-manifest.json","url":"https://api.example/manifest","browser_download_url":"https://api.example/manifest","size":256},{"name":"foxhole.apk","url":"https://cdn.example/foxhole.apk","browser_download_url":"https://cdn.example/foxhole.apk","size":32}]}""".toResponseBody()
        )
        runCurrent()
        val manifest = available().manifest
        factory.calls.last().respond(
            """{"versionCode":2,"versionName":"0.1.2","apkName":"foxhole.apk","apkSha256":"${manifest.apkSha256}"}""".toResponseBody()
        )
        runCurrent()

        assertTrue(background.await() is AppUpdateCheck.Available)
        assertEquals(artifact, (repository.state.value as AppUpdateState.Downloaded).apk)
        assertTrue(artifact.exists())
    }

    @Test fun oldCancelledReaderCannotReplaceOrDeleteANewerRepositoryArtifact() = runBlocking {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val repository = repository(factory, directory)
        val oldOperation = async(Dispatchers.Default) { repository.download(available()) }
        val oldCall = requireNotNull(factory.started.poll(2, TimeUnit.SECONDS))
        val oldReaderMayFinish = CountDownLatch(1)
        val body = AppUpdateCancellationTest.BlockingBody(oldReaderMayFinish)
        val reader = thread(isDaemon = true) { oldCall.respond(body) }
        try {
            assertTrue(body.blocked.await(2, TimeUnit.SECONDS))
            oldOperation.cancel()
            try { oldOperation.await() } catch (_: CancellationException) { }
            val newer = async(Dispatchers.Default) { repository.download(available()) }
            requireNotNull(factory.started.poll(2, TimeUnit.SECONDS)).respond(PAYLOAD.toResponseBody())
            val file = newer.await().getOrThrow()
            val published = repository.state.value
            assertTrue(published is AppUpdateState.Downloaded)

            oldReaderMayFinish.countDown()
            reader.join(2000)

            assertFalse(reader.isAlive)
            assertTrue(file.exists())
            assertEquals(published, repository.state.value)
            assertEquals(listOf(file), directory.listFiles().orEmpty().toList())
        } finally {
            oldReaderMayFinish.countDown()
            body.close()
            oldOperation.cancel()
            reader.join(2000)
        }
    }

    @Test fun failedReplacementPreservesThePreviousVerifiedArtifactAndUnrelatedFiles() = runTest {
        val factory = AppUpdateCancellationTest.HeldFactory()
        val directory = temporary.newFolder()
        val sentinel = File(directory, "owner-sentinel.txt").apply { writeText("caller owned") }
        val repository = repository(factory, directory, StandardTestDispatcher(testScheduler))
        val first = async { repository.download(available()) }
        runCurrent()
        factory.calls.last().respond(PAYLOAD.toResponseBody())
        runCurrent()
        val verified = first.await().getOrThrow()
        val replacement = async { repository.download(available()) }
        runCurrent()
        factory.calls.last().respond(PAYLOAD.copyOf().also { it[0] = 99 }.toResponseBody())
        runCurrent()

        assertTrue(replacement.await().isFailure)
        assertTrue(verified.exists())
        assertEquals("caller owned", sentinel.readText())
        assertEquals(setOf(verified, sentinel), directory.listFiles().orEmpty().toSet())
    }

    private fun repository(
        factory: AppUpdateCancellationTest.HeldFactory,
        directory: File,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ) = AppUpdateRepository(
        client = AppUpdateClient(
            OkHttpClient(),
            resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            operationTimeoutMs = 10000,
            ioDispatcher = dispatcher,
            callFactory = factory,
        ),
        currentVersionCode = 1,
        downloadDirectory = directory,
        currentVersionName = "0.1.0",
        verifierDispatcher = dispatcher,
    )

    private fun available(name: String = "foxhole.apk") = AppUpdateCheck.Available(
        AppUpdateManifest(
            2,
            "0.1.2",
            name,
            MessageDigest.getInstance("SHA-256").digest(PAYLOAD).joinToString("") { "%02x".format(it) }
        ),
        "https://cdn.example/foxhole.apk",
        PAYLOAD.size.toLong(),
    )

    private companion object { val PAYLOAD = ByteArray(32) { it.toByte() } }
}
