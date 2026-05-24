package com.foxhole.beta.vpn

import android.content.Context
import android.os.Build
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class TorRuntimePaths(
    val executablePath: String,
    val dataDirectory: String,
    val geoIpFilePath: String? = null,
    val geoIpv6FilePath: String? = null,
    val torrcDefaultsFilePath: String? = null,
)

internal fun TorRuntimePaths.withIdentityVersion(identityVersion: Long): TorRuntimePaths {
    if (identityVersion <= 0L) {
        return this
    }
    val rootDirectory = File(dataDirectory)
    val identityDirectory = File(rootDirectory, "identity-$identityVersion").apply { mkdirs() }
    rootDirectory
        .listFiles { file -> file.isDirectory && file.name.startsWith("identity-") && file.name != identityDirectory.name }
        .orEmpty()
        .forEach { staleDirectory -> staleDirectory.deleteRecursively() }
    return copy(dataDirectory = identityDirectory.absolutePath)
}

class TorRuntimeUnavailableException(message: String) : IllegalStateException(message)

class TorRuntimeInstaller(
    context: Context,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    private val appContext = context.applicationContext
    private val installMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedPaths: TorRuntimePaths? = null
    private var cachedVersion: String? = null

    suspend fun prepare(): TorRuntimePaths =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                val assetAbi =
                    Build.SUPPORTED_ABIS
                        .firstOrNull { abi -> torExecutableAssetName("tor/$abi") != null }
                        ?: run {
                            recordTorPreflight(
                                "asset_abi=missing",
                                "supported_abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
                            )
                            throw TorRuntimeUnavailableException("Tor Expert Bundle asset is missing for this device ABI")
                        }
                val assetRoot = "tor/$assetAbi"
                val targetRoot = File(appContext.filesDir, "tor/$assetAbi")
                val assetVersion = readAssetText("$assetRoot/.version").orEmpty()
                val targetVersion = File(targetRoot, ".version").takeIf(File::isFile)?.readText().orEmpty()
                cachedPaths
                    ?.takeIf { cachedVersion == assetVersion && assetVersion == targetVersion }
                    ?.takeIf { paths -> paths.filesReady() }
                    ?.let { return@withContext it }
                if (assetVersion != targetVersion || !targetRoot.isDirectory) {
                    targetRoot.deleteRecursively()
                    copyAssetTree(assetRoot, targetRoot)
                    markTorBundleExecutables(targetRoot)
                }
                val executable =
                    nativeTorExecutable()
                        ?: run {
                            recordTorPreflight(
                                "asset_abi=$assetAbi",
                                "asset_version=${assetVersion.ifBlank { "unknown" }}",
                                "native_tor=false",
                            )
                            throw TorRuntimeUnavailableException("Tor native executable is missing for this device ABI")
                        }
                if (!executable.isFile || !executable.ensureExecutable()) {
                    recordTorPreflight(
                        "asset_abi=$assetAbi",
                        "asset_version=${assetVersion.ifBlank { "unknown" }}",
                        "native_tor=true",
                        "native_tor_executable=false",
                    )
                    throw TorRuntimeUnavailableException("Tor executable could not be prepared")
                }
                val dataDirectory = File(appContext.filesDir, "tor-data/$assetAbi").apply { mkdirs() }
                val geoIpFile = copyTorDataFile(targetRoot, dataDirectory, "geoip")
                val geoIpv6File = copyTorDataFile(targetRoot, dataDirectory, "geoip6")
                val torrcDefaultsFile = writeRuntimeTorrcDefaults(targetRoot, dataDirectory)
                recordTorPreflight(
                    "asset_abi=$assetAbi",
                    "asset_version=${assetVersion.ifBlank { "unknown" }}",
                    "native_tor=true",
                    "native_tor_executable=${executable.canExecute()}",
                    "lyrebird_native=${nativePluggableTransportExists("lyrebird")}",
                    "conjure_native=${nativePluggableTransportExists("conjure-client")}",
                    "torrc_defaults=${torrcDefaultsFile?.isFile == true}",
                    "bridge_count=${torrcDefaultsFile.bridgeCount()}",
                    "geoip=${geoIpFile?.isFile == true}",
                    "geoip6=${geoIpv6File?.isFile == true}",
                    "data_dir_writable=${dataDirectory.canWrite()}",
                )
                TorRuntimePaths(
                    executablePath = executable.absolutePath,
                    dataDirectory = dataDirectory.absolutePath,
                    geoIpFilePath = geoIpFile?.absolutePath,
                    geoIpv6FilePath = geoIpv6File?.absolutePath,
                    torrcDefaultsFilePath = torrcDefaultsFile?.absolutePath,
                ).also { paths ->
                    cachedPaths = paths
                    cachedVersion = assetVersion
                }
            }
        }

    private fun torExecutableAssetName(assetRoot: String): String? =
        TOR_EXECUTABLE_ASSET_NAMES.firstOrNull { name -> assetFileExists("$assetRoot/$name") }

    private fun nativeTorExecutable(): File? =
        File(appContext.applicationInfo.nativeLibraryDir, TOR_NATIVE_LIBRARY_NAME)
            .takeIf(File::isFile)

    private fun File.ensureExecutable(): Boolean =
        canExecute() || setExecutable(true, true)

    private fun TorRuntimePaths.filesReady(): Boolean =
        File(executablePath).isFile &&
            File(dataDirectory).isDirectory &&
            geoIpFilePath?.let { File(it).isFile } != false &&
            geoIpv6FilePath?.let { File(it).isFile } != false &&
            torrcDefaultsFilePath?.let { File(it).isFile } != false

    private fun markTorBundleExecutables(targetRoot: File) {
        TOR_EXECUTABLE_ASSET_NAMES.forEach { name ->
            File(targetRoot, name).takeIf(File::isFile)?.ensureExecutable()
        }
        TOR_PLUGGABLE_TRANSPORT_NAMES.forEach { name ->
            File(targetRoot, "tor/pluggable_transports/$name").takeIf(File::isFile)?.ensureExecutable()
        }
    }

    private fun copyTorDataFile(
        targetRoot: File,
        dataDirectory: File,
        name: String,
    ): File? {
        val source = findTorDataFile(targetRoot, name) ?: return null
        return File(dataDirectory, name)
            .also { target -> source.copyToIfChanged(target) }
    }

    private fun writeRuntimeTorrcDefaults(
        targetRoot: File,
        dataDirectory: File,
    ): File? {
        val source = findTorDataFile(targetRoot, TORRC_DEFAULTS_FILE_NAME) ?: return null
        val transportLines =
            source
                .readLines()
                .mapNotNull { line -> normalizedTorrcDefaultsLine(line) }
        val bridgeLines = defaultBridgeTorrcLines(targetRoot, transportLines)
        val content =
            (transportLines + bridgeLines)
                .joinToString(separator = "\n", postfix = "\n")
        return File(dataDirectory, TORRC_DEFAULTS_FILE_NAME)
            .also { target -> target.writeTextIfChanged(content) }
    }

    @Suppress("ReturnCount")
    private fun normalizedTorrcDefaultsLine(line: String): String? {
        if (!line.startsWith("ClientTransportPlugin ") || " exec " !in line) {
            return line
        }
        val prefix = line.substringBefore(" exec ")
        val command = line.substringAfter(" exec ")
        val executableName = command.substringBefore(' ')
        val executable = nativePluggableTransportExecutable(executableName)
        if (!executable.isFile || !executable.ensureExecutable()) {
            return null
        }
        val arguments = command.substringAfter(' ', missingDelimiterValue = "").trim()
        return buildString {
            append(prefix)
            append(" exec ")
            append(executable.absolutePath)
            if (arguments.isNotBlank()) {
                append(' ')
                append(arguments)
            }
        }
    }

    private fun nativePluggableTransportExecutable(name: String): File {
        val nativeName = TOR_NATIVE_PLUGGABLE_TRANSPORT_NAMES[name].orEmpty()
        val nativeExecutable = File(appContext.applicationInfo.nativeLibraryDir, nativeName)
        if (nativeExecutable.isFile) {
            return nativeExecutable
        }
        // Android rejects executing binaries copied into app-private data on recent devices.
        // Omit the transport instead of giving Tor a startup-time EACCES path.
        return File("")
    }

    private fun nativePluggableTransportExists(name: String): Boolean {
        val nativeName = TOR_NATIVE_PLUGGABLE_TRANSPORT_NAMES[name].orEmpty()
        return nativeName.isNotBlank() && File(appContext.applicationInfo.nativeLibraryDir, nativeName).isFile
    }

    @Suppress("ReturnCount")
    private fun defaultBridgeTorrcLines(
        targetRoot: File,
        transportLines: List<String>,
    ): List<String> {
        val ptConfig = File(targetRoot, "tor/pluggable_transports/pt_config.json").takeIf(File::isFile) ?: return emptyList()
        val root =
            runCatching { json.parseToJsonElement(ptConfig.readText()).jsonObject }
                .getOrNull()
                ?: return emptyList()
        val recommended =
            root["recommendedDefault"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return emptyList()
        val bridges =
            root["bridges"]
                ?.jsonObject
                ?.get(recommended)
                ?.jsonArray
                ?.mapNotNull { bridge -> bridge.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
        if (bridges.isEmpty()) {
            return emptyList()
        }
        val bridgeTransport = bridges.first().substringBefore(' ')
        val transportReady =
            transportLines.any { line ->
                line.startsWith("ClientTransportPlugin ") &&
                    line
                        .substringAfter("ClientTransportPlugin ")
                        .substringBefore(" exec ")
                        .split(',')
                        .map(String::trim)
                        .contains(bridgeTransport)
            }
        if (!transportReady) {
            return emptyList()
        }
        return listOf("UseBridges 1") + bridges.map { bridge -> "Bridge $bridge" }
    }

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

    private fun File.copyToIfChanged(target: File) {
        target.parentFile?.mkdirs()
        if (target.isFile && readBytes().contentEquals(target.readBytes())) {
            return
        }
        copyTo(target, overwrite = true)
    }

    private fun File.writeTextIfChanged(content: String) {
        parentFile?.mkdirs()
        if (isFile && readText() == content) {
            return
        }
        writeText(content)
    }

    private fun File?.bridgeCount(): Int =
        this
            ?.takeIf(File::isFile)
            ?.readLines()
            ?.count { line -> line.startsWith("Bridge ") }
            ?: 0

    private fun recordTorPreflight(vararg details: String) {
        diagnosticsLogger?.recordStructured(
            tag = "tor",
            headline = "tor runtime preflight",
            *details,
        )
    }

    private companion object {
        const val TOR_NATIVE_LIBRARY_NAME = "libTor.so"
        const val TORRC_DEFAULTS_FILE_NAME = "torrc-defaults"
        val TOR_EXECUTABLE_ASSET_NAMES = listOf("tor", "libTor.so", "tor/libTor.so")
        val TOR_PLUGGABLE_TRANSPORT_NAMES = listOf("lyrebird", "conjure-client")
        val TOR_NATIVE_PLUGGABLE_TRANSPORT_NAMES =
            mapOf(
                "lyrebird" to "liblyrebird.so",
                "conjure-client" to "libconjure_client.so",
            )
    }
}
