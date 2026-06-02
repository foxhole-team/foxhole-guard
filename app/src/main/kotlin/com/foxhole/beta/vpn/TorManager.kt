package com.foxhole.beta.vpn

import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

interface TorManager {
    suspend fun prepare(): TorRuntimePaths

    suspend fun start(profile: TorStartProfile): TorStartResult

    suspend fun awaitReady(timeoutMs: Long): TorReadyResult

    suspend fun stop(policy: TorStopPolicy = TorStopPolicy()): TorStopResult

    fun kill(reason: String): TorKillResult

    fun snapshot(): TorSnapshot
}

data class TorStartProfile(
    val paths: TorRuntimePaths,
    val socksPort: Int = 0,
    val controlPort: Int = 0,
)

data class TorStartResult(
    val socksPort: Int,
    val controlPort: Int,
)

data class TorReadyResult(
    val ready: Boolean,
    val bootstrapProgress: Int? = null,
)

data class TorStopPolicy(
    val processDestroyTimeoutMs: Long = 700L,
    val processDestroyForciblyTimeoutMs: Long = 1_500L,
)

data class TorStopResult(
    val graceful: Boolean,
    val killed: Boolean,
)

data class TorKillResult(
    val reason: String,
    val killed: Boolean,
)

data class TorSnapshot(
    val state: TorState = TorState.IDLE,
    val socksPort: Int? = null,
    val controlPort: Int? = null,
)

enum class TorState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    KILLED,
    ERROR,
}

class TorProcessManager(
    private val installer: TorRuntimeInstaller,
    private val diagnosticsLogger: DiagnosticsLogger,
) : TorManager {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var currentSnapshot = TorSnapshot()

    override suspend fun prepare(): TorRuntimePaths = installer.prepare()

    override suspend fun start(profile: TorStartProfile): TorStartResult =
        withContext(Dispatchers.IO) {
            stop()
            val socksPort = profile.socksPort.takeIf { it > 0 } ?: allocateLoopbackPort()
            val controlPort = profile.controlPort.takeIf { it > 0 } ?: allocateLoopbackPort()
            currentSnapshot = TorSnapshot(TorState.STARTING, socksPort, controlPort)
            val args =
                buildList {
                    add(profile.paths.executablePath)
                    profile.paths.torrcDefaultsFilePath?.let { defaults ->
                        add("--defaults-torrc")
                        add(defaults)
                    }
                    add("--ClientOnly")
                    add("1")
                    add("--AvoidDiskWrites")
                    add("1")
                    add("--DataDirectory")
                    add(profile.paths.dataDirectory)
                    add("--SocksPort")
                    add("127.0.0.1:$socksPort")
                    add("--ControlPort")
                    add("127.0.0.1:$controlPort")
                    add("--CookieAuthentication")
                    add("0")
                    profile.paths.geoIpFilePath?.let {
                        add("--GeoIPFile")
                        add(it)
                    }
                    profile.paths.geoIpv6FilePath?.let {
                        add("--GeoIPv6File")
                        add(it)
                    }
                }
            val startedProcess =
                ProcessBuilder(args)
                    .directory(File(profile.paths.dataDirectory))
                    .redirectErrorStream(true)
                    .start()
            drainProcessOutput(startedProcess)
            process = startedProcess
            currentSnapshot = TorSnapshot(TorState.RUNNING, socksPort, controlPort)
            diagnosticsLogger.recordStructured(
                "runtime",
                "tor process started",
                "socks_port=$socksPort",
                "control_port=$controlPort",
            )
            TorStartResult(socksPort = socksPort, controlPort = controlPort)
        }

    override suspend fun awaitReady(timeoutMs: Long): TorReadyResult {
        val controlPort = currentSnapshot.controlPort ?: return TorReadyResult(false)
        var lastProgress: Int? = null
        var lastProbe = TorBootstrapProbe()
        val ready =
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                while (true) {
                    val activeProcess = process
                    if (activeProcess == null || !activeProcess.isAlive) {
                        return@withTimeoutOrNull false
                    }
                    val probe = queryBootstrap(controlPort)
                    lastProbe = probe
                    if (probe.progress != null && probe.progress != lastProgress) {
                        lastProgress = probe.progress
                        diagnosticsLogger.recordStructured(
                            "runtime",
                            "tor bootstrap progress",
                            "progress=${probe.progress}",
                            probe.summary?.let { "summary=$it" },
                        )
                    }
                    if (probe.ready) {
                        return@withTimeoutOrNull true
                    }
                    delay(TOR_BOOTSTRAP_POLL_MS)
                }
            } == true
        if (!ready) {
            diagnosticsLogger.recordStructured(
                "runtime",
                "tor bootstrap wait failed",
                "progress=${lastProbe.progress ?: "unknown"}",
                lastProbe.summary?.let { "summary=$it" },
            )
        }
        return TorReadyResult(
            ready = ready,
            bootstrapProgress = lastProbe.progress,
        )
    }

    override suspend fun stop(policy: TorStopPolicy): TorStopResult =
        withContext(Dispatchers.IO) {
            val active = process ?: return@withContext TorStopResult(graceful = true, killed = false)
            currentSnapshot = currentSnapshot.copy(state = TorState.STOPPING)
            process = null
            active.destroy()
            val graceful = active.waitFor(policy.processDestroyTimeoutMs, TimeUnit.MILLISECONDS)
            val killed =
                if (graceful) {
                    false
                } else {
                    active.destroyForcibly()
                    active.waitFor(policy.processDestroyForciblyTimeoutMs, TimeUnit.MILLISECONDS)
                }
            currentSnapshot = TorSnapshot(if (graceful) TorState.IDLE else TorState.KILLED)
            TorStopResult(graceful = graceful, killed = killed)
        }

    override fun kill(reason: String): TorKillResult {
        val active = process ?: return TorKillResult(reason, killed = false)
        process = null
        currentSnapshot = TorSnapshot(TorState.KILLED)
        runCatching { active.destroyForcibly() }
        diagnosticsLogger.recordStructured("runtime", "tor process killed", "reason=$reason")
        return TorKillResult(reason, killed = true)
    }

    override fun snapshot(): TorSnapshot = currentSnapshot

    private fun allocateLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket -> socket.localPort }

    private fun queryBootstrap(controlPort: Int): TorBootstrapProbe =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", controlPort), TOR_CONTROL_CONNECT_TIMEOUT_MS)
                socket.soTimeout = TOR_CONTROL_READ_TIMEOUT_MS
                val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                writer.write("AUTHENTICATE\r\n")
                writer.write("GETINFO status/bootstrap-phase\r\n")
                writer.write("QUIT\r\n")
                writer.flush()
                val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                TorBootstrapProbe(
                    ready = response.hasCompletedTorBootstrap(),
                    progress = response.torBootstrapProgress(),
                    summary = response.torBootstrapSummary(),
                )
            }
        }.getOrDefault(TorBootstrapProbe())

    private fun String.hasCompletedTorBootstrap(): Boolean =
        torBootstrapProgress() == 100 || contains("TAG=done", ignoreCase = true)

    private fun String.torBootstrapProgress(): Int? =
        BOOTSTRAP_PROGRESS_REGEX.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun String.torBootstrapSummary(): String? =
        BOOTSTRAP_SUMMARY_REGEX.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf(String::isNotBlank)

    private fun drainProcessOutput(activeProcess: Process) {
        Thread(
            {
                runCatching {
                    activeProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            line
                                .trim()
                                .takeIf(String::isNotBlank)
                                ?.let { message ->
                                    diagnosticsLogger.recordThrottled(
                                        tag = "tor",
                                        throttleKey = "tor_process_output",
                                        windowMs = TOR_PROCESS_LOG_THROTTLE_MS,
                                        message = "tor process: $message",
                                    )
                                }
                        }
                    }
                }
            },
            "FoxHoleTorProcessLog",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TOR_PROCESS_LOG_THROTTLE_MS = 1_000L
        const val TOR_BOOTSTRAP_POLL_MS = 500L
        const val TOR_CONTROL_CONNECT_TIMEOUT_MS = 300
        const val TOR_CONTROL_READ_TIMEOUT_MS = 500
        val BOOTSTRAP_PROGRESS_REGEX = Regex("""PROGRESS=(\d{1,3})""")
        val BOOTSTRAP_SUMMARY_REGEX = Regex("""SUMMARY="?([^"\r\n]+)"?""")
    }
}

private data class TorBootstrapProbe(
    val ready: Boolean = false,
    val progress: Int? = null,
    val summary: String? = null,
)
