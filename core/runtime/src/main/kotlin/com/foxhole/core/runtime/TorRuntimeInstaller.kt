package com.foxhole.core.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-side inputs for FoxCore's in-process Arti outbound.
 *
 * No Tor executable or public SOCKS listener exists. The only executable paths are optional
 * managed-transport helpers, and the Rust core owns their lifetime.
 */
data class TorRuntimePaths(
    val dataDirectory: String,
    val bridges: List<String> = emptyList(),
    val pluggableTransports: List<TorPluggableTransport> = emptyList(),
) {
    override fun toString(): String =
        "TorRuntimePaths(dataDirectory=$dataDirectory, bridges=<redacted:${bridges.size}>, " +
            "pluggableTransports=${pluggableTransports.size})"
}

data class TorPluggableTransport(
    val protocols: List<String>,
    val executablePath: String,
    val arguments: List<String> = emptyList(),
) {
    override fun toString(): String =
        "TorPluggableTransport(protocols=$protocols, executablePath=$executablePath, " +
            "arguments=<redacted:${arguments.size}>)"
}

fun TorRuntimePaths.withIdentityVersion(identityVersion: Long): TorRuntimePaths {
    if (identityVersion <= 0L) {
        return this
    }
    val rootDirectory = File(dataDirectory)
    val identityDirectory = File(rootDirectory, "identity-$identityVersion").apply { ensurePrivateDirectory() }
    rootDirectory
        .listFiles { file -> file.isDirectory && file.name.startsWith("identity-") && file.name != identityDirectory.name }
        .orEmpty()
        .forEach { staleDirectory -> staleDirectory.deleteRecursively() }
    return copy(dataDirectory = identityDirectory.absolutePath)
}

internal class TorRuntimeUnavailableException(message: String) : IllegalStateException(message)

/**
 * Prepares writable Arti state and translates the bundled/downloaded bridge inventory into the
 * strict FoxCore transport plan. Architecture-specific assets are used only to discover which
 * managed transports are executable on this device.
 */
class TorRuntimeInstaller(
    context: Context,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
    private val bridgeStore: TorBridgeStore? = null,
) {
    private val appContext = context.applicationContext
    private val installMutex = Mutex()
    private var cachedPaths: TorRuntimePaths? = null
    private var cachedVersion: String? = null
    private var cachedBridgeFingerprint: String? = null

    suspend fun prepare(bridgePolicy: TorBridgePolicy = TorBridgePolicy.DEFAULT): TorRuntimePaths =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                val assetAbi =
                    Build.SUPPORTED_ABIS
                        .firstOrNull { abi -> assetFileExists(torrcAssetPath(abi)) }
                        ?: throw TorRuntimeUnavailableException(
                            "TOR bridge inventory is missing for this device ABI",
                        )
                val assetVersion = readAssetText("tor/$assetAbi/$TOR_BUNDLE_VERSION_FILE_NAME").orEmpty().trim()
                val bridgeFingerprint = bridgePolicy.fingerprint(bridgeStore?.payloadFingerprint())
                cachedPaths
                    ?.takeIf { cachedVersion == assetVersion }
                    ?.takeIf { cachedBridgeFingerprint == bridgeFingerprint }
                    ?.takeIf { paths -> paths.filesReady() }
                    ?.let { return@withContext it }

                val stateDirectory =
                    File(appContext.filesDir, "$ARTI_STATE_ROOT/$assetAbi").apply {
                        ensurePrivateDirectory()
                    }
                val transportLines =
                    readAssetText(torrcAssetPath(assetAbi))
                        .orEmpty()
                        .lineSequence()
                        .mapNotNull(::normalizedTransportLine)
                        .toList()
                val bridgeLines =
                    buildBridgeTorrcLines(
                        policy = bridgePolicy,
                        transportLines = transportLines,
                        downloadedGroupsJson = bridgeStore?.readGroupsJsonOrNull(),
                        bundledPtConfigJson = readAssetText(ptConfigAssetPath(assetAbi)),
                    )
                val plan = (transportLines + bridgeLines).readFoxCoreBridgePlan(bridgePolicy)

                recordTorPreflight(
                    "engine=arti",
                    "asset_abi=$assetAbi",
                    "asset_version=${assetVersion.ifBlank { "unknown" }}",
                    "bridges_enabled=${bridgePolicy.enabled}",
                    "bridge_transport=${bridgePolicy.transport.name.lowercase()}",
                    "bridge_source=${if (bridgeStore?.readGroupsJsonOrNull() != null) "downloaded" else "bundled"}",
                    "bridge_count=${plan.bridges.size}",
                    "transport_count=${plan.transports.size}",
                    "state_dir_writable=${stateDirectory.canWrite()}",
                )
                TorRuntimePaths(
                    dataDirectory = stateDirectory.absolutePath,
                    bridges = plan.bridges,
                    pluggableTransports = plan.transports,
                ).also { paths ->
                    cachedPaths = paths
                    cachedVersion = assetVersion
                    cachedBridgeFingerprint = bridgeFingerprint
                }
            }
        }

    @Suppress("ReturnCount")
    private fun normalizedTransportLine(line: String): String? {
        if (!line.startsWith(TOR_TRANSPORT_PREFIX) || TOR_TRANSPORT_EXEC_SEPARATOR !in line) {
            return null
        }
        val prefix = line.substringBefore(TOR_TRANSPORT_EXEC_SEPARATOR)
        val command = line.substringAfter(TOR_TRANSPORT_EXEC_SEPARATOR)
        val executableName = command.substringBefore(' ')
        val executable = nativePluggableTransportExecutable(executableName)
        if (!executable.isFile || !executable.ensureExecutable()) {
            return null
        }
        val arguments = command.substringAfter(' ', missingDelimiterValue = "").trim()
        return buildString {
            append(prefix)
            append(TOR_TRANSPORT_EXEC_SEPARATOR)
            append(executable.absolutePath)
            if (arguments.isNotBlank()) {
                append(' ')
                append(arguments)
            }
        }
    }

    private fun nativePluggableTransportExecutable(name: String): File {
        val nativeName = TOR_NATIVE_PLUGGABLE_TRANSPORT_NAMES[name] ?: return File("")
        return File(appContext.applicationInfo.nativeLibraryDir, nativeName)
    }

    private fun File.ensureExecutable(): Boolean =
        canExecute() || setExecutable(true, true)

    private fun TorRuntimePaths.filesReady(): Boolean =
        File(dataDirectory).let { directory -> directory.isDirectory && directory.canWrite() } &&
            pluggableTransports.all { transport -> File(transport.executablePath).isFile }

    private fun List<String>.readFoxCoreBridgePlan(policy: TorBridgePolicy): FoxCoreBridgePlan {
        if (!policy.enabled) {
            return FoxCoreBridgePlan()
        }
        val bridges =
            mapNotNull { line ->
                line
                    .takeIf { it.startsWith(TOR_BRIDGE_PREFIX) }
                    ?.removePrefix(TOR_BRIDGE_PREFIX)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }.filter(::isArtiCompatibleBridgeLine)
        if (bridges.isEmpty()) {
            throw TorRuntimeUnavailableException("TOR bridges are enabled but no bridge is available")
        }
        val requiredProtocols =
            bridges
                .mapNotNull { bridge ->
                    bridge
                        .substringBefore(' ')
                        .takeUnless(::looksLikeDirectTorBridgeAddress)
                }.toSet()
        val transports =
            mapNotNull(::parseFoxCorePluggableTransport)
                .filter { transport -> transport.protocols.any(requiredProtocols::contains) }
                .coalesceByProcess()
        val configuredProtocols = transports.flatMap(TorPluggableTransport::protocols).toSet()
        if (!configuredProtocols.containsAll(requiredProtocols)) {
            throw TorRuntimeUnavailableException("TOR bridge transport is unavailable for this device ABI")
        }
        return FoxCoreBridgePlan(bridges = bridges, transports = transports)
    }

    private fun parseFoxCorePluggableTransport(line: String): TorPluggableTransport? {
        if (!line.startsWith(TOR_TRANSPORT_PREFIX) || TOR_TRANSPORT_EXEC_SEPARATOR !in line) {
            return null
        }
        val protocols =
            line
                .substringAfter(TOR_TRANSPORT_PREFIX)
                .substringBefore(TOR_TRANSPORT_EXEC_SEPARATOR)
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        val command =
            line
                .substringAfter(TOR_TRANSPORT_EXEC_SEPARATOR)
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
        val executable = command.firstOrNull() ?: return null
        if (protocols.isEmpty() || !File(executable).isFile) {
            return null
        }
        return TorPluggableTransport(
            protocols = protocols,
            executablePath = executable,
            arguments = command.drop(1),
        )
    }

    private fun assetFileExists(path: String): Boolean =
        runCatching { appContext.assets.open(path).use { true } }.getOrDefault(false)

    private fun readAssetText(path: String): String? =
        runCatching { appContext.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    private fun recordTorPreflight(vararg details: String) {
        diagnosticsLogger?.recordStructured(
            tag = "tor",
            headline = "arti runtime preflight",
            *details,
        )
    }

    private fun torrcAssetPath(abi: String): String = "tor/$abi/data/$TORRC_DEFAULTS_FILE_NAME"

    private fun ptConfigAssetPath(abi: String): String =
        "tor/$abi/tor/pluggable_transports/$PT_CONFIG_FILE_NAME"

    private companion object {
        const val ARTI_STATE_ROOT = "foxcore/arti"
        const val TOR_BUNDLE_VERSION_FILE_NAME = "bundle.version"
        const val TORRC_DEFAULTS_FILE_NAME = "torrc-defaults"
        const val PT_CONFIG_FILE_NAME = "pt_config.json"
        const val TOR_BRIDGE_PREFIX = "Bridge "
        const val TOR_TRANSPORT_PREFIX = "ClientTransportPlugin "
        const val TOR_TRANSPORT_EXEC_SEPARATOR = " exec "
        val TOR_NATIVE_PLUGGABLE_TRANSPORT_NAMES =
            mapOf(
                "lyrebird" to "liblyrebird.so",
                "conjure-client" to "libconjure_client.so",
            )
    }
}

internal fun isArtiCompatibleBridgeLine(bridge: String): Boolean =
    bridge
        .trim()
        .split(Regex("\\s+"))
        .any { token -> TOR_RSA_IDENTITY.matches(token.removePrefix("\$")) }

private val TOR_RSA_IDENTITY = Regex("[0-9A-Fa-f]{40}")

/**
 * One managed-transport process can advertise several protocols. The bundled inventory has
 * separate ClientTransportPlugin lines for Lyrebird and Snowflake even though both resolve to the
 * same executable and arguments. Starting them as separate Arti transports duplicates the helper,
 * state directory and startup handshake; coalescing keeps startup parallel and process ownership
 * unambiguous while preserving genuinely different commands as separate helpers.
 */
internal fun List<TorPluggableTransport>.coalesceByProcess(): List<TorPluggableTransport> {
    val processes = linkedMapOf<Pair<String, List<String>>, TorPluggableTransport>()
    for (transport in this) {
        val key = transport.executablePath to transport.arguments
        val current = processes[key]
        processes[key] =
            if (current == null) {
                transport.copy(protocols = transport.protocols.distinct())
            } else {
                current.copy(protocols = (current.protocols + transport.protocols).distinct())
            }
    }
    return processes.values.toList()
}

private data class FoxCoreBridgePlan(
    val bridges: List<String> = emptyList(),
    val transports: List<TorPluggableTransport> = emptyList(),
)

private fun looksLikeDirectTorBridgeAddress(value: String): Boolean =
    value.contains(':') || value.startsWith('[')

private fun File.ensurePrivateDirectory() {
    if ((!isDirectory && !mkdirs()) || !canWrite()) {
        throw TorRuntimeUnavailableException("Arti state directory is unavailable")
    }
    runCatching { Os.chmod(absolutePath, PRIVATE_DIRECTORY_MODE) }
}

private const val PRIVATE_DIRECTORY_MODE = 448 // 0700
