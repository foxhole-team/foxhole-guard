package com.foxhole.guard.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class AppUpdateArchitectureManifestTest {
    @Test
    fun `ARMv7 build selects only its manifest when both architecture packages exist`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = client(AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME, requests).check(1, "1.0.0")
        assertTrue(result is AppUpdateCheck.Available)
        assertEquals("guard-armeabi-v7a.apk", (result as AppUpdateCheck.Available).manifest.apkName)
        assertTrue(requests.contains("/" + AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME))
        assertFalse(requests.contains("/" + AppUpdateClient.MANIFEST_ASSET_NAME))
    }

    @Test
    fun `existing builds retain the generic manifest selection`() = runBlocking {
        val result = client(AppUpdateClient.MANIFEST_ASSET_NAME, mutableListOf()).check(1, "1.0.0")
        assertEquals("guard-android64.apk", (result as AppUpdateCheck.Available).manifest.apkName)
    }

    @Test
    fun `missing ARMv7 manifest never falls back to a 64 bit package`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = client(AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME, requests, includeArm = false).check(1, "1.0.0")
        assertTrue(result is AppUpdateCheck.Available)
        assertFalse((result as AppUpdateCheck.Available).installable)
        assertEquals(listOf("/releases/latest"), requests)
    }

    @Test
    fun `split 64 bit installs accept complete universal 64 bit updates`() {
        fun complete(vararg abis: String) = abis.associateWith { appUpdateRequiredNativeLibraries }
        val arm64 = appUpdateRunningAbi(setOf("arm64-v8a"), listOf("arm64-v8a", "x86_64"))
        val x64 = appUpdateRunningAbi(setOf("x86_64"), listOf("x86_64", "arm64-v8a"))
        assertTrue(appUpdateNativeProfileCompatible(arm64, complete("arm64-v8a", "x86_64"), false))
        assertTrue(appUpdateNativeProfileCompatible(x64, complete("arm64-v8a", "x86_64"), false))
        assertFalse(appUpdateNativeProfileCompatible(arm64, complete("x86_64"), false))
        assertFalse(appUpdateNativeProfileCompatible(x64, complete("arm64-v8a"), false))
        assertFalse(appUpdateNativeProfileCompatible(arm64, complete("armeabi-v7a"), false))
        assertFalse(appUpdateNativeProfileCompatible("armeabi-v7a", complete("arm64-v8a"), true))
        assertTrue(appUpdateNativeProfileCompatible("armeabi-v7a", complete("armeabi-v7a"), true))
        assertFalse(appUpdateNativeProfileCompatible("armeabi-v7a", complete("armeabi-v7a", "x86_64"), true))
        assertFalse(appUpdateNativeProfileCompatible(arm64, complete("arm64-v8a", "x86_64") +
            ("x86_64" to setOf("libfoxhole_native.so")), false))
        assertFalse(appUpdateNativeProfileCompatible(null, complete("arm64-v8a"), false))
    }

    @Test
    fun `native inventory uses packaged paths and checks actual ELF headers`() {
        val apk = File.createTempFile("update-native-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                listOf("lib/armeabi-v7a/libfoxhole_native.so", "assets/arm64-v8a/libfoxhole_native.so", "lib/x86/libother.so").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    val header = byteArrayOf(127, 69, 76, 70, 1, 1, 1).copyOf(20)
                    header[18] = if (name.contains("/x86/")) 3 else 40
                    zip.write(header)
                    zip.closeEntry()
                }
            }
            val libraries = appUpdateApkNativeLibraries(apk)
            assertEquals(setOf("armeabi-v7a", "x86"), libraries.keys)
            assertEquals(setOf("libfoxhole_native.so"), libraries["armeabi-v7a"])
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/armeabi-v7a/libfoxhole_native.so"))
                zip.write(byteArrayOf(127, 69, 76, 70, 2, 1, 1).copyOf(20).also { it[18] = 183.toByte() })
                zip.closeEntry()
            }
            assertTrue(runCatching { appUpdateApkNativeLibraries(apk) }.isFailure)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `native inventory accepts 256 entries but refuses 257 before growing the map`() {
        for (count in listOf(256, 257)) {
            val entries = (0 until count).asSequence().map { index -> "lib/armeabi-v7a/libfixture$index.so" to armHeader() }
            withNativeArchive(entries) { apk ->
                val result = runCatching { appUpdateApkNativeLibraries(apk) }
                if (count == 256) assertEquals(256, result.getOrThrow()["armeabi-v7a"]?.size)
                else assertTrue(result.exceptionOrNull() is AppUpdateVerificationException)
            }
        }
    }

    @Test
    fun `whole APK inventory is bounded even when entries are not native`() {
        for (count in listOf(50_000, 50_001)) {
            val entries = (0 until count).asSequence().map { index -> "assets/fixture$index" to byteArrayOf() }
            withNativeArchive(entries) { apk ->
                val result = runCatching { appUpdateApkNativeLibraries(apk) }
                if (count == 50_000) assertTrue(result.getOrThrow().isEmpty())
                else assertTrue(result.exceptionOrNull() is AppUpdateVerificationException)
            }
        }
    }

    @Test
    fun `ZIP names are bounded to 512 characters for native and other entries`() {
        val prefix = "lib/armeabi-v7a/"
        for (length in listOf(512, 513)) {
            val nativeName = prefix + "x".repeat(length - prefix.length - 3) + ".so"
            withNativeArchive(sequenceOf(nativeName to armHeader())) { apk ->
                val result = runCatching { appUpdateApkNativeLibraries(apk) }
                if (length == 512) assertEquals(setOf(nativeName.substringAfterLast('/')), result.getOrThrow()["armeabi-v7a"])
                else assertTrue(result.exceptionOrNull() is AppUpdateVerificationException)
            }
        }
        withNativeArchive(sequenceOf("assets/" + "x".repeat(506) to byteArrayOf())) { apk ->
            assertTrue(runCatching { appUpdateApkNativeLibraries(apk) }.exceptionOrNull() is AppUpdateVerificationException)
        }
    }

    @Test
    fun `native directories nested paths foreign ABIs and invalid ELF versions are refused`() {
        val entries = listOf("lib/armeabi-v7a/libfixture.so/" to byteArrayOf(),
            "lib/armeabi-v7a/nested/libfixture.so" to armHeader(), "lib/mips/libfixture.so" to armHeader(),
            "lib/armeabi-v7a/libfixture.so" to armHeader().also { it[6] = 0 },
            "lib/armeabi-v7a/libfixture.so" to armHeader().copyOf(19), "lib/armeabi-v7a/libbad\n.so" to armHeader())
        for (entry in entries) {
            withNativeArchive(sequenceOf(entry)) { apk ->
                assertTrue("expected native verification failure for ${entry.first}",
                    runCatching { appUpdateApkNativeLibraries(apk) }.exceptionOrNull() is AppUpdateVerificationException)
            }
        }
    }

    private fun armHeader(): ByteArray = byteArrayOf(127, 69, 76, 70, 1, 1, 1).copyOf(20).also { it[18] = 40 }

    private fun <ResultType> withNativeArchive(entries: Sequence<Pair<String, ByteArray>>, action: (File) -> ResultType): ResultType {
        val apk = File.createTempFile("update-native-bound-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return action(apk)
        } finally {
            apk.delete()
        }
    }

    private fun client(asset: String, requests: MutableList<String>, includeArm: Boolean = true): AppUpdateClient =
        AppUpdateClient(
            httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requests += path
                val body = when (path) {
                    "/releases/latest" -> release(includeArm)
                    "/" + AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME -> manifest("guard-armeabi-v7a.apk")
                    "/" + AppUpdateClient.MANIFEST_ASSET_NAME -> manifest("guard-android64.apk")
                    else -> error("Unexpected request: $path")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build(),
            resolver = { listOf(InetAddress.getByName("8.8.8.8")) },
            releasesApiUrl = { "https://updates.example.org/releases/latest" },
            manifestAssetName = asset,
        )

    private fun release(includeArm: Boolean): String {
        val names = listOf(AppUpdateClient.MANIFEST_ASSET_NAME, "guard-android64.apk", "guard-armeabi-v7a.apk") +
            if (includeArm) listOf(AppUpdateClient.ARMV7_MANIFEST_ASSET_NAME) else emptyList()
        val assets = names.joinToString(",") { name ->
            """{"name":"$name","browser_download_url":"https://updates.example.org/$name","size":128}"""
        }
        return """{"tag_name":"v2.0.0","assets":[$assets]}"""
    }

    private fun manifest(apk: String): String =
        """{"versionCode":2,"versionName":"2.0.0","apkName":"$apk","apkSha256":"${"a".repeat(64)}"}"""
}
