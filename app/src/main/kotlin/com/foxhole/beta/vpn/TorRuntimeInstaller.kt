package com.foxhole.beta.vpn

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class TorRuntimePaths(
    val executablePath: String,
    val dataDirectory: String,
    val geoIpFilePath: String? = null,
    val geoIpv6FilePath: String? = null,
)

class TorRuntimeUnavailableException(message: String) : IllegalStateException(message)

class TorRuntimeInstaller(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun prepare(): TorRuntimePaths =
        withContext(Dispatchers.IO) {
            val assetAbi =
                Build.SUPPORTED_ABIS
                    .firstOrNull { abi -> torExecutableAssetName("tor/$abi") != null }
                    ?: throw TorRuntimeUnavailableException("Tor Expert Bundle asset is missing for this device ABI")
            val assetRoot = "tor/$assetAbi"
            val targetRoot = File(appContext.filesDir, "tor/$assetAbi")
            val assetVersion = readAssetText("$assetRoot/.version").orEmpty()
            val targetVersion = File(targetRoot, ".version").takeIf(File::isFile)?.readText().orEmpty()
            if (assetVersion != targetVersion || !targetRoot.isDirectory) {
                targetRoot.deleteRecursively()
                copyAssetTree(assetRoot, targetRoot)
            }
            val executable =
                nativeTorExecutable()
                    ?: throw TorRuntimeUnavailableException("Tor native executable is missing for this device ABI")
            if (!executable.isFile || !executable.ensureExecutable()) {
                throw TorRuntimeUnavailableException("Tor executable could not be prepared")
            }
            val dataDirectory = File(appContext.filesDir, "tor-data").apply { mkdirs() }
            TorRuntimePaths(
                executablePath = executable.absolutePath,
                dataDirectory = dataDirectory.absolutePath,
                geoIpFilePath = findTorDataFile(targetRoot, "geoip")?.absolutePath,
                geoIpv6FilePath = findTorDataFile(targetRoot, "geoip6")?.absolutePath,
            )
        }

    private fun torExecutableAssetName(assetRoot: String): String? =
        TOR_EXECUTABLE_ASSET_NAMES.firstOrNull { name -> assetFileExists("$assetRoot/$name") }

    private fun nativeTorExecutable(): File? =
        File(appContext.applicationInfo.nativeLibraryDir, TOR_NATIVE_LIBRARY_NAME)
            .takeIf(File::isFile)

    private fun File.ensureExecutable(): Boolean =
        canExecute() || setExecutable(true, true)

    private fun findTorDataFile(
        targetRoot: File,
        name: String,
    ): File? =
        listOf(
            File(targetRoot, name),
            File(targetRoot, "data/$name"),
        ).firstOrNull(File::isFile)

    private fun assetFileExists(path: String): Boolean =
        runCatching {
            appContext.assets.open(path).use { true }
        }.getOrDefault(false)

    private fun readAssetText(path: String): String? =
        runCatching {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()

    private fun copyAssetTree(
        assetPath: String,
        target: File,
    ) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private companion object {
        const val TOR_NATIVE_LIBRARY_NAME = "libTor.so"
        val TOR_EXECUTABLE_ASSET_NAMES = listOf("tor", "libTor.so", "tor/libTor.so")
    }
}
