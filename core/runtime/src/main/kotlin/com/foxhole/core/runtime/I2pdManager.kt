package com.foxhole.core.runtime

import com.foxhole.core.model.I2pAddressBookEntry
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.i2pdBandwidthChar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

interface I2pdManager {
    suspend fun ensureStarted(settings: I2pSettings = I2pSettings()): I2pdEndpoints

    suspend fun awaitReady(timeoutMs: Long): Boolean

    /** Publishes CONNECTED only for a probed endpoint generation carried by an applied Android TUN. */
    fun confirmCarrierReady(expectedGeneration: Long): Boolean

    fun markCarrierUnavailable(): Unit

    suspend fun stop(policy: I2pdStopPolicy = I2pdStopPolicy()): Unit

    fun kill(reason: String): Unit

    fun snapshot(): I2pdSnapshot

    fun setUnexpectedExitListener(listener: (() -> Unit)?) {}
}

data class I2pdEndpoints(
    val httpProxyPort: Int,
    val socksPort: Int,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val generation: Long = 0L,
)

data class I2pdSocksProxyEndpoint(
    val port: Int,
    val username: String,
    val password: String,
)

object I2pdSocksProxy {
    @Volatile
    var endpoint: I2pdSocksProxyEndpoint? = null
}

data class I2pdWebConsoleEndpoint(
    val port: Int,
    val password: String,
)

object I2pdWebConsole {
    @Volatile
    var endpoint: I2pdWebConsoleEndpoint? = null
}

data class I2pdStopPolicy(
    val processDestroyTimeoutMs: Long = 700L,
    val processDestroyForciblyTimeoutMs: Long = 1_500L,
)

data class I2pdSnapshot(
    val state: I2pdState = I2pdState.IDLE,
    val endpoints: I2pdEndpoints? = null,
)

enum class I2pdState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    KILLED,
    ERROR,
}

internal data class I2pdProcessManagerHooks(
    val processLauncher: RuntimeChildProcessLauncher,
    val allocateLoopbackPort: () -> Int = ::allocateI2pdLoopbackPort,
    val localPortAccepts: (Int) -> Boolean = ::i2pdLocalPortAccepts,
    val generateWebConsolePassword: () -> String = ::newI2pdWebConsolePassword,
    val generateSocksPassword: () -> String = ::newI2pdSocksPassword,
    val socksProxyReady: ((I2pdEndpoints) -> Boolean)? = null,
    val reapStartupOrphans: (String, String) -> Int = ::reapI2pdOrphansByIdentity,
    val startSocksCredentialGate: (Int, Int, String, String) -> I2pdSocksGate = ::openI2pdSocksGate,

    val nextGeneration: () -> Long = RuntimeGenerationClock::next,
) {
    constructor() : this(
        processLauncher = DefaultI2pdProcessLauncher,
        socksProxyReady = ::i2pdAuthenticatedSocksProxyReady,
    )
}

private fun reapI2pdOrphansByIdentity(
    executablePath: String,
    dataDirectoryRoot: String,
): Int =
    RuntimeChildProcessReaper(
        selfPid = android.os.Process.myPid(),
        killProcess = { pid -> android.os.Process.killProcess(pid) },
    ).reapOrphans(executablePath = executablePath, dataDirectoryRoot = dataDirectoryRoot)

internal object I2pdStartupOrphanReap {
    private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)

    internal fun resetForTest() = claimed.set(false)
}

private object DefaultI2pdProcessLauncher : RuntimeChildProcessLauncher {
    override fun launch(
        command: List<String>,
        workingDirectory: File,
    ): Process =
        ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
}

class I2pdProcessManager internal constructor(
    private val prepareRuntime: suspend () -> I2pdRuntimePaths,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,

    private val isMeteredNetwork: () -> Boolean,
    private val hooks: I2pdProcessManagerHooks,
) : I2pdManager {
    constructor(
        installer: I2pdRuntimeInstaller,
        diagnosticsLogger: RuntimeDiagnosticsSink,
        isMeteredNetwork: () -> Boolean = { false },
    ) : this(
        prepareRuntime = installer::prepare,
        diagnosticsLogger = diagnosticsLogger,
        isMeteredNetwork = isMeteredNetwork,
        hooks = I2pdProcessManagerHooks(),
    )

    private val lifecycleMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var unexpectedExitListener: (() -> Unit)? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var socksGate: I2pdSocksGate? = null

    private var processGeneration: Long = NO_GENERATION
    private var lifecycleGeneration: Long = 0L
    private var readyProxyGeneration: Long = NO_GENERATION
    private var establishedNetworkTunnels = I2pdEstablishedNetworkTunnels()

    @Volatile
    private var currentSnapshot = I2pdSnapshot()

    @Volatile
    private var appliedConfigFingerprint: Int? = null

    @Suppress("ComplexCondition")
    override suspend fun ensureStarted(settings: I2pSettings): I2pdEndpoints =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                ensureStartedLocked(settings)
            }
        }

    @Suppress("ComplexCondition")
    private suspend fun ensureStartedLocked(settings: I2pSettings): I2pdEndpoints {
        val startConfiguration = createStartConfiguration(settings)
        synchronized(stateLock) {
            val running = process
            val endpoints = currentSnapshot.endpoints
            if (running != null && running.isAlive && endpoints != null &&
                startConfiguration.fingerprint == appliedConfigFingerprint
            ) {
                return endpoints
            }
        }

        val idleAtEntry =
            synchronized(stateLock) {
                process == null && currentSnapshot.state == I2pdState.IDLE
            }
        val priorStopGeneration = stopLocked(I2pdStopPolicy())
        val allocation = allocateStartEndpoints()
        lateinit var endpoints: I2pdEndpoints
        val allocatedEndpoints = allocation.endpoints
        val webConsolePort = allocation.webConsolePort
        val webConsolePassword = allocation.webConsolePassword
        val generation =
            synchronized(stateLock) {
                if (lifecycleGeneration != priorStopGeneration) {
                    cancelI2pdStart()
                }
                if (process?.isAlive == true) {
                    currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
                    error("previous i2pd process did not terminate")
                }
                process = null
                processGeneration = NO_GENERATION
                lifecycleGeneration = hooks.nextGeneration()
                endpoints = allocatedEndpoints.copy(generation = lifecycleGeneration)
                clearPublishedStateLocked()
                currentSnapshot = I2pdSnapshot(I2pdState.STARTING, endpoints)
                lifecycleGeneration
            }
        val httpProxyPort = endpoints.httpProxyPort
        val routerSocksPort = allocation.routerSocksPort
        var startedProcess: Process? = null
        try {
            val paths = prepareRuntime()
            requireCurrentGeneration(generation)
            reapOrphansBeforeFirstStart(idleAtEntry, paths)
            applyAddressBook(File(paths.dataDirectory), settings.addressBook)
            val confFile =
                writeConf(
                    dataDirectory = File(paths.dataDirectory),
                    httpProxyPort = httpProxyPort,
                    socksPort = routerSocksPort,
                    relayTransitTraffic = startConfiguration.effectiveRelay,
                    transitBandwidth = startConfiguration.bandwidthChar,
                    transitTunnelsLimit = settings.transitTunnelsLimit,
                    webConsolePort = webConsolePort,
                    webConsolePassword = webConsolePassword,
                )
            val args =
                listOf(
                    paths.executablePath,
                    "--conf",
                    confFile.absolutePath,
                    "--datadir",
                    paths.dataDirectory,
                )
            requireCurrentGeneration(generation)
            val started = hooks.processLauncher.launch(args, File(paths.dataDirectory))
            startedProcess = started
            val gate =
                hooks.startSocksCredentialGate(
                    routerSocksPort,
                    httpProxyPort,
                    endpoints.socksUsername,
                    endpoints.socksPassword,
                )
            endpoints = endpoints.copy(socksPort = gate.port)
            val socksPort = endpoints.socksPort
            val published =
                publishStartedProcess(
                    generation = generation,
                    started = started,
                    gate = gate,
                    endpoints = endpoints,
                    fingerprint = startConfiguration.fingerprint,
                    webConsolePort = webConsolePort,
                    webConsolePassword = webConsolePassword,
                )
            if (!published) {
                runCatching { gate.close() }
                retainIfTerminationFailed(started)
                cancelI2pdStart()
            }

            drainProcessOutput(started, generation)
            val running =
                synchronized(stateLock) {
                    if (isCurrentProcessLocked(started, generation) && started.isAlive) {
                        currentSnapshot = I2pdSnapshot(I2pdState.RUNNING, endpoints)
                        true
                    } else {
                        false
                    }
                }
            if (!running) {
                cleanupFailedStart(generation, started)
                error("i2pd process exited during startup")
            }
            diagnosticsLogger.recordStructured(
                "i2pd",
                "i2pd process started",
                "http_port=$httpProxyPort",
                "socks_port=$socksPort",
            )
            return endpoints
        } catch (error: Throwable) {
            cleanupFailedStart(generation, startedProcess)
            throw error
        }
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        val session =
            synchronized(stateLock) {
                val active = process ?: return false
                val endpoints = currentSnapshot.endpoints ?: return false
                I2pdReadySession(
                    generation = processGeneration,
                    process = active,
                    endpoints = endpoints,
                )
            }
        val outcome =
            I2pdReadyProbe(
                sessionCurrent = {
                    isCurrentProcess(session.process, session.generation) && session.process.isAlive
                },
                listenerReady = {
                    hooks.socksProxyReady?.invoke(session.endpoints)
                        ?: hooks.localPortAccepts(session.endpoints.socksPort)
                },
                networkTunnelPairReady = {
                    synchronized(stateLock) {
                        isCurrentProcessLocked(session.process, session.generation) &&
                            session.process.isAlive &&
                            establishedNetworkTunnels.ready
                    }
                },
                recordReady = { recordProxyReady(session) },
            ).await(timeoutMs)
        diagnosticsLogger.recordStructured(
            "i2pd",
            i2pdReadyOutcome(ready = outcome.ready, listenerUp = outcome.listenerUp),
            "socks_port=${session.endpoints.socksPort} " +
                "network_tunnel_pair=${outcome.networkTunnelPairReady}",
        )
        return outcome.ready
    }

    override fun confirmCarrierReady(expectedGeneration: Long): Boolean =
        synchronized(stateLock) {
            val active = process
            val generationCurrent =
                expectedGeneration != NO_GENERATION &&
                    processGeneration == expectedGeneration &&
                    lifecycleGeneration == expectedGeneration
            if (
                !generationCurrent ||
                readyProxyGeneration != expectedGeneration ||
                active?.isAlive != true
            ) {
                false
            } else {
                val previous = FoxholeVpnRuntimeBridge.i2pPhase.value
                FoxholeVpnRuntimeBridge.updateI2pPhase(
                    previous.copy(
                        phase = I2pNetworkPhase.CONNECTED,
                        connectedAt = previous.connectedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
                true
            }
        }

    override fun markCarrierUnavailable() {
        synchronized(stateLock) {
            val active = process
            val previous = FoxholeVpnRuntimeBridge.i2pPhase.value
            if (previous.phase != I2pNetworkPhase.CONNECTED) {
                return
            }
            FoxholeVpnRuntimeBridge.updateI2pPhase(
                if (active?.isAlive == true && processGeneration == lifecycleGeneration) {
                    previous.copy(
                        phase = I2pNetworkPhase.BUILDING_TUNNELS,
                        connectedAt = 0L,
                    )
                } else {
                    I2pPhaseSnapshot()
                },
            )
        }
    }

    override suspend fun stop(policy: I2pdStopPolicy) {
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                stopLocked(policy)
            }
        }
    }

    private fun stopLocked(policy: I2pdStopPolicy): Long {
        val capture =
            synchronized(stateLock) {
                lifecycleGeneration = hooks.nextGeneration()
                clearPublishedStateLocked()
                val active = process
                if (active == null) {
                    processGeneration = NO_GENERATION
                    currentSnapshot = I2pdSnapshot(I2pdState.IDLE)
                } else {
                    currentSnapshot = currentSnapshot.copy(state = I2pdState.STOPPING)
                }
                I2pdStopCapture(lifecycleGeneration, active)
            }
        val active = capture.process ?: return capture.generation

        active.destroyForcibly()
        val terminated =
            active.waitFor(policy.processDestroyForciblyTimeoutMs, TimeUnit.MILLISECONDS) || !active.isAlive
        synchronized(stateLock) {
            if (lifecycleGeneration == capture.generation && process === active) {
                if (terminated) {
                    process = null
                    processGeneration = NO_GENERATION
                    currentSnapshot = I2pdSnapshot(I2pdState.IDLE)
                } else {
                    currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
                }
            }
        }
        if (!terminated) {
            diagnosticsLogger.recordStructured("i2pd", "i2pd process did not terminate")
        }
        return capture.generation
    }

    override fun kill(reason: String) {
        val kill =
            synchronized(stateLock) {
                lifecycleGeneration = hooks.nextGeneration()
                clearPublishedStateLocked()
                val active = process
                currentSnapshot =
                    I2pdSnapshot(
                        if (active == null && currentSnapshot.state == I2pdState.IDLE) {
                            I2pdState.IDLE
                        } else {
                            I2pdState.KILLED
                        },
                    )
                active?.let { running ->
                    I2pdStopSession(lifecycleGeneration, running)
                } ?: run {
                    processGeneration = NO_GENERATION
                    null
                }
            } ?: return
        runCatching { kill.process.destroyForcibly() }
        synchronized(stateLock) {
            if (lifecycleGeneration == kill.generation && process === kill.process) {
                if (kill.process.isAlive) {
                    currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
                } else {
                    process = null
                    processGeneration = NO_GENERATION
                }
            }
        }
        diagnosticsLogger.recordStructured("i2pd", "i2pd process killed", "reason=$reason")
    }

    override fun snapshot(): I2pdSnapshot = currentSnapshot

    @Suppress("LongParameterList")
    private fun publishStartedProcess(
        generation: Long,
        started: Process,
        gate: I2pdSocksGate,
        endpoints: I2pdEndpoints,
        fingerprint: Int,
        webConsolePort: Int,
        webConsolePassword: String,
    ): Boolean =
        synchronized(stateLock) {
            if (lifecycleGeneration != generation || process != null) {
                false
            } else {
                process = started
                processGeneration = generation
                socksGate = gate
                appliedConfigFingerprint = fingerprint
                I2pdWebConsole.endpoint = I2pdWebConsoleEndpoint(webConsolePort, webConsolePassword)
                I2pdSocksProxy.endpoint =
                    I2pdSocksProxyEndpoint(
                        port = endpoints.socksPort,
                        username = endpoints.socksUsername,
                        password = endpoints.socksPassword,
                    )
                FoxholeVpnRuntimeBridge.updateI2pPhase(
                    I2pPhaseSnapshot(
                        phase = I2pNetworkPhase.STARTING,
                        startedAt = System.currentTimeMillis(),
                    ),
                )
                true
            }
        }

    private fun reapOrphansBeforeFirstStart(
        idleAtEntry: Boolean,
        paths: I2pdRuntimePaths,
    ) {
        if (!idleAtEntry || !I2pdStartupOrphanReap.claim()) {
            return
        }
        val reaped =
            runCatching {
                hooks.reapStartupOrphans(paths.executablePath, paths.dataDirectory)
            }.getOrDefault(0)
        if (reaped > 0) {
            diagnosticsLogger.recordStructured(
                "i2pd",
                "i2pd orphan from a previous app process reaped at start",
                "count=$reaped",
            )
        }
    }

    private fun applyAddressBook(
        dataDirectory: File,
        entries: List<I2pAddressBookEntry>,
    ) {
        File(dataDirectory, ADDRESSBOOK_DIR_NAME).deleteRecursively()
        val hostsFile = File(dataDirectory, HOSTS_FILE_NAME)
        if (entries.isEmpty()) {
            hostsFile.delete()
        } else {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText(buildI2pdHostsLines(entries).joinToString(separator = "\n", postfix = "\n"))
        }

        diagnosticsLogger.recordStructured("i2pd", "i2pd addressbook applied", "hosts=${entries.size}")
    }

    private fun writeConf(
        dataDirectory: File,
        httpProxyPort: Int,
        socksPort: Int,
        relayTransitTraffic: Boolean,
        transitBandwidth: String,
        transitTunnelsLimit: Int,
        webConsolePort: Int,
        webConsolePassword: String,
    ): File {
        val content =
            buildI2pdConfLines(
                httpProxyPort = httpProxyPort,
                socksPort = socksPort,
                relayTransitTraffic = relayTransitTraffic,
                transitBandwidth = transitBandwidth,
                transitTunnelsLimit = transitTunnelsLimit,
                webConsolePort = webConsolePort,
                webConsolePassword = webConsolePassword,
            ).joinToString(separator = "\n", postfix = "\n")
        return File(dataDirectory, CONF_FILE_NAME).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private fun drainProcessOutput(
        activeProcess: Process,
        generation: Long,
    ) {
        Thread(
            {
                runCatching {
                    activeProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            line.trim().takeIf(String::isNotBlank)?.let { message ->
                                val current =
                                    synchronized(stateLock) {
                                        if (isCurrentProcessLocked(activeProcess, generation)) {
                                            establishedNetworkTunnels = establishedNetworkTunnels.after(message)
                                            advanceI2pPhaseFromLogLine(message)
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                if (current && isJournalWorthyI2pdLine(message)) {
                                    diagnosticsLogger.record("i2pd", message)
                                }
                            }
                        }
                    }
                }
                markUnexpectedExit(activeProcess, generation)
            },
            "FoxHoleI2pdProcessLog",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun markUnexpectedExit(
        exitedProcess: Process,
        generation: Long,
    ) {
        val unexpected =
            synchronized(stateLock) {
                if (!isCurrentProcessLocked(exitedProcess, generation)) {
                    false
                } else {
                    process = null
                    processGeneration = NO_GENERATION
                    clearPublishedStateLocked()
                    currentSnapshot = I2pdSnapshot(I2pdState.KILLED)
                    true
                }
            }
        if (!unexpected) {
            return
        }
        diagnosticsLogger.recordStructured(
            "i2pd",
            "i2pd process exited unexpectedly",
            "exit_code=${runCatching { exitedProcess.exitValue() }.getOrNull() ?: "unknown"}",
        )
        unexpectedExitListener?.invoke()
    }

    override fun setUnexpectedExitListener(listener: (() -> Unit)?) {
        unexpectedExitListener = listener
    }

    private fun requireCurrentGeneration(generation: Long) {
        if (synchronized(stateLock) { lifecycleGeneration != generation }) {
            cancelI2pdStart()
        }
    }

    private fun cancelI2pdStart(): Nothing = throw CancellationException("i2pd start superseded")

    private fun createStartConfiguration(settings: I2pSettings): I2pdStartConfiguration {
        val bandwidthChar = settings.transitBandwidth.i2pdBandwidthChar()
        val effectiveRelay =
            settings.relayTransitTraffic && (settings.allowRelayOnCellular || !isMeteredNetwork())
        if (settings.relayTransitTraffic && !effectiveRelay) {
            diagnosticsLogger.recordStructured(
                "i2pd",
                "i2pd transit relay suppressed on metered network",
            )
        }
        return I2pdStartConfiguration(
            bandwidthChar = bandwidthChar,
            effectiveRelay = effectiveRelay,
            fingerprint = i2pStartupConfigFingerprint(
                entries = settings.addressBook,
                relayTransitTraffic = effectiveRelay,
                transitBandwidth = bandwidthChar,
                transitTunnelsLimit = settings.transitTunnelsLimit,
            ),
        )
    }

    private fun allocateStartEndpoints(): I2pdStartEndpointAllocation =
        I2pdStartEndpointAllocation(
            endpoints =
            I2pdEndpoints(
                httpProxyPort = hooks.allocateLoopbackPort(),
                socksPort = 0,
                socksUsername = I2PD_SOCKS_PROXY_USER,
                socksPassword = hooks.generateSocksPassword(),
            ),
            routerSocksPort = hooks.allocateLoopbackPort(),
            webConsolePort = hooks.allocateLoopbackPort(),
            webConsolePassword = hooks.generateWebConsolePassword(),
        )

    private fun cleanupFailedStart(
        generation: Long,
        startedProcess: Process?,
    ) {
        if (startedProcess?.isAlive == true) {
            runCatching { startedProcess.destroyForcibly() }
        }
        synchronized(stateLock) {
            if (lifecycleGeneration != generation) {
                if (startedProcess?.isAlive == true && process == null) {
                    process = startedProcess
                    processGeneration = lifecycleGeneration
                    currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
                }
                return
            }
            if (startedProcess == null || process === startedProcess) {
                if (startedProcess?.isAlive == true) {
                    process = startedProcess
                    processGeneration = generation
                } else {
                    process = null
                    processGeneration = NO_GENERATION
                }
            }
            clearPublishedStateLocked()
            currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
        }
    }

    private fun retainIfTerminationFailed(activeProcess: Process) {
        runCatching { activeProcess.destroyForcibly() }
        if (!activeProcess.isAlive) {
            return
        }
        synchronized(stateLock) {
            if (process == null) {
                process = activeProcess
                processGeneration = lifecycleGeneration
                currentSnapshot = I2pdSnapshot(I2pdState.ERROR)
            }
        }
    }

    private fun isCurrentProcess(
        expectedProcess: Process,
        generation: Long,
    ): Boolean = synchronized(stateLock) { isCurrentProcessLocked(expectedProcess, generation) }

    private fun isCurrentProcessLocked(
        expectedProcess: Process,
        generation: Long,
    ): Boolean =
        lifecycleGeneration == generation &&
            processGeneration == generation &&
            process === expectedProcess

    private fun recordProxyReady(session: I2pdReadySession): Boolean =
        synchronized(stateLock) {
            if (!isCurrentProcessLocked(session.process, session.generation) || !session.process.isAlive) {
                false
            } else {
                readyProxyGeneration = session.generation
                true
            }
        }

    private fun clearPublishedStateLocked() {
        socksGate?.let { gate -> runCatching { gate.close() } }
        socksGate = null
        appliedConfigFingerprint = null
        readyProxyGeneration = NO_GENERATION
        establishedNetworkTunnels = I2pdEstablishedNetworkTunnels()
        I2pdWebConsole.endpoint = null
        I2pdSocksProxy.endpoint = null
        FoxholeVpnRuntimeBridge.updateI2pPhase(I2pPhaseSnapshot())
    }

    private companion object {
        const val CONF_FILE_NAME = "i2pd.conf"
        const val HOSTS_FILE_NAME = "hosts.txt"
        const val ADDRESSBOOK_DIR_NAME = "addressbook"
        const val NO_GENERATION = -1L
    }
}

private data class I2pdReadySession(
    val generation: Long,
    val process: Process,
    val endpoints: I2pdEndpoints,
)

private data class I2pdReadyProbe(
    val sessionCurrent: () -> Boolean,
    val listenerReady: () -> Boolean,
    val networkTunnelPairReady: () -> Boolean,
    val recordReady: () -> Boolean,
) {
    suspend fun await(timeoutMs: Long): I2pdReadyProbeOutcome {
        var listenerUp = false
        var pairReady = false
        val ready =
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                while (sessionCurrent()) {
                    if (!listenerUp) {
                        listenerUp = listenerReady()
                    }
                    if (!sessionCurrent()) {
                        return@withTimeoutOrNull false
                    }
                    if (listenerUp) {
                        pairReady = networkTunnelPairReady()
                        if (pairReady && recordReady()) {
                            return@withTimeoutOrNull true
                        }
                    }
                    delay(I2PD_READY_POLL_MS)
                }
                false
            } == true
        return I2pdReadyProbeOutcome(
            ready = ready,
            listenerUp = listenerUp,
            networkTunnelPairReady = pairReady,
        )
    }
}

private data class I2pdReadyProbeOutcome(
    val ready: Boolean,
    val listenerUp: Boolean,
    val networkTunnelPairReady: Boolean,
)

private data class I2pdEstablishedNetworkTunnels(
    val inbound: Boolean = false,
    val outbound: Boolean = false,
) {
    val ready: Boolean
        get() = inbound && outbound

    fun after(line: String): I2pdEstablishedNetworkTunnels =
        when (i2pdEstablishedNetworkTunnelDirection(line)) {
            I2pdNetworkTunnelDirection.INBOUND -> copy(inbound = true)
            I2pdNetworkTunnelDirection.OUTBOUND -> copy(outbound = true)
            null -> this
        }
}

private enum class I2pdNetworkTunnelDirection {
    INBOUND,
    OUTBOUND,
}

private data class I2pdStartConfiguration(
    val bandwidthChar: String,
    val effectiveRelay: Boolean,
    val fingerprint: Int,
)

private data class I2pdStartEndpointAllocation(
    val endpoints: I2pdEndpoints,
    val routerSocksPort: Int,
    val webConsolePort: Int,
    val webConsolePassword: String,
)

private data class I2pdStopSession(
    val generation: Long,
    val process: Process,
)

private data class I2pdStopCapture(
    val generation: Long,
    val process: Process?,
)

private fun allocateI2pdLoopbackPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket -> socket.localPort }

internal fun i2pdReadyOutcome(
    ready: Boolean,
    listenerUp: Boolean,
): String =
    when {
        ready -> "i2pd proxy ready"
        listenerUp -> "i2pd proxy up but tunnels not built"
        else -> "i2pd proxy wait failed"
    }

private fun i2pdLocalPortAccepts(port: Int): Boolean =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), I2PD_READY_CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

private fun i2pdAuthenticatedSocksProxyReady(endpoints: I2pdEndpoints): Boolean =
    runCatching {
        val username = endpoints.socksUsername.toByteArray(Charsets.UTF_8)
        val password = endpoints.socksPassword.toByteArray(Charsets.UTF_8)
        require(username.isNotEmpty() && username.size <= 255)
        require(password.isNotEmpty() && password.size <= 255)
        Socket().use { socket ->
            socket.soTimeout = I2PD_READY_CONNECT_TIMEOUT_MS
            socket.connect(
                InetSocketAddress("127.0.0.1", endpoints.socksPort),
                I2PD_READY_CONNECT_TIMEOUT_MS,
            )
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            require(input.read() == 0x05 && input.read() == 0x02)
            output.write(0x01)
            output.write(username.size)
            output.write(username)
            output.write(password.size)
            output.write(password)
            output.flush()
            input.read() == 0x01 && input.read() == 0x00
        }
    }.getOrDefault(false)

private fun newI2pdWebConsolePassword(): String {
    val bytes = ByteArray(WEB_CONSOLE_PASSWORD_BYTES)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

private fun newI2pdSocksPassword(): String = newI2pdWebConsolePassword()

private const val I2PD_READY_CONNECT_TIMEOUT_MS = 300
private const val I2PD_READY_POLL_MS = 500L
private const val WEB_CONSOLE_PASSWORD_BYTES = 16

internal fun isJournalWorthyI2pdLine(line: String): Boolean {
    val lower = line.lowercase()
    return I2PD_JOURNAL_MARKERS.any { marker -> lower.contains(marker) }
}

internal val I2PD_JOURNAL_MARKERS =
    listOf(
        "reseed",
        "netdb",
        "tunnels:",
        "tunnel:",
        "transports",
        "router:",
        "daemon",
        "clients:",
        "addressbook",
        "error",
        "warn",
        "critical",
        "unrecognised option",
        "unrecognized option",
        "missing/unreadable config",
    )

internal fun advanceI2pPhaseFromLogLine(line: String) {
    val previous = FoxholeVpnRuntimeBridge.i2pPhase.value
    if (previous.phase == I2pNetworkPhase.OFFLINE) {
        return
    }
    val next = i2pPhaseAfterLogLine(previous, line.lowercase())
    if (next != previous) {
        FoxholeVpnRuntimeBridge.updateI2pPhase(next)
    }
}

private fun i2pPhaseAfterLogLine(
    previous: I2pPhaseSnapshot,
    lower: String,
): I2pPhaseSnapshot {
    val tunnelCreated = i2pdEstablishedNetworkTunnelDirection(lower) != null
    return when {
        previous.phase == I2pNetworkPhase.CONNECTED && tunnelCreated ->
            previous.copy(tunnelsBuilt = previous.tunnelsBuilt + 1)
        previous.phase == I2pNetworkPhase.CONNECTED -> previous
        tunnelCreated ->
            previous.copy(
                phase = I2pNetworkPhase.BUILDING_TUNNELS,
                tunnelsBuilt = previous.tunnelsBuilt + 1,
            )
        lower.contains("tunnels:") || lower.contains("tunnel:") ->
            previous.copy(phase = I2pNetworkPhase.BUILDING_TUNNELS)
        lower.contains("reseed") || lower.contains("netdb") ->
            if (previous.phase == I2pNetworkPhase.STARTING) {
                previous.copy(phase = I2pNetworkPhase.DISCOVERING_PEERS)
            } else {
                previous
            }
        else -> previous
    }
}

private fun i2pdEstablishedNetworkTunnelDirection(line: String): I2pdNetworkTunnelDirection? =
    when (I2PD_NETWORK_TUNNEL_CREATED_REGEX.find(line.lowercase())?.groupValues?.getOrNull(1)) {
        "inbound" -> I2pdNetworkTunnelDirection.INBOUND
        "outbound" -> I2pdNetworkTunnelDirection.OUTBOUND
        else -> null
    }

private val I2PD_NETWORK_TUNNEL_CREATED_REGEX =
    Regex("""tunnel:\s+(inbound|outbound)\s+tunnel\s+\d+\s+has\s+been\s+created""")
