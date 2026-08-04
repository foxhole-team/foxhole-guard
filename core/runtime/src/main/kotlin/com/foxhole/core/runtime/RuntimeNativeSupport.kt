package com.foxhole.core.runtime

import android.content.Context
import android.net.Network
import android.net.VpnService
import com.foxhole.core.model.TransportProtocol
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

interface RuntimeServiceHost {
    val runtimeContext: Context

    fun stopRuntimeService()

    fun protectSocket(socket: Int): Boolean

    fun hasVpnPermission(): Boolean = true

    fun createTunBuilder(): VpnService.Builder? = null

    /**
     * Physical/default network FoxCore must bind before Android installs the VPN routes. Null is
     * a hard start failure: starting without a handle could route bootstrap DNS back into the TUN.
     */
    fun currentUnderlyingNetwork(): Network? = null
}

internal class RuntimeGenerationGuard(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    private val transitionLock = Any()

    // Last generation minted FOR the native runtime, from the shared control-plane clock.
    // Written only under transitionLock so commitIfCurrent's check+publish stays the
    // linearization point for start/reload vs a fail-closed kill; volatile keeps reads lock-free.
    @Volatile
    private var nativeGeneration = 0L

    fun current(): Long = nativeGeneration

    fun next(reason: String): Long =
        synchronized(transitionLock) {
            RuntimeGenerationClock.next().also { generation ->
                nativeGeneration = generation
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "runtime generation advanced",
                    "reason=$reason",
                    "generation=$generation",
                )
            }
        }

    fun isCurrent(generation: Long): Boolean = nativeGeneration == generation

    /**
     * Commits the native-owner publication under the same lock that advances generations: a plain
     * isCurrent() check then a write has a race — a fail-closed kill can advance the generation
     * between the two and a stale start then resurrects the runtime.
     */
    fun commitIfCurrent(
        generation: Long,
        commit: () -> Unit,
    ): Boolean =
        synchronized(transitionLock) {
            if (nativeGeneration != generation) {
                false
            } else {
                commit()
                true
            }
        }

    suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(generation)) {
            throw CancellationException("runtime generation superseded")
        }
    }
}

class RuntimeInstanceStore(
    private val createRuntime: () -> FoxholeRuntime,
) {
    private val lock = Any()

    @Volatile
    private var instance: FoxholeRuntime? = null

    fun get(): FoxholeRuntime {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: createRuntime().also { runtime -> instance = runtime }
        }
    }

    /**
     * Last master TUN fd this store saw, kept across [clear]: the teardown watchdog scans
     * /proc/self/fd after the instance is dropped, so asking the instance is not an option.
     * Without this it cannot tell the by-design master from a descriptor the core failed to
     * close — and its answer to a leak is killing the process (Pixel: every protocol switch
     * ended in SIGKILL). Held here, not on the legacy bridge, which
     * RuntimeStaticSafetyGuardTest fences off for new dependencies.
     */
    @Volatile
    private var lastMasterTunFd: Int? = null

    fun current(): FoxholeRuntime? = instance

    fun nativeSnapshot(): NativeRuntimeSnapshot {
        val live = current()?.nativeSnapshot()
        if (live != null) {
            // Tracks the live value in both directions: fd numbers are reused aggressively, so a
            // stale one would make the watchdog wave through a genuine leak on the same number.
            lastMasterTunFd = live.masterTunFd
            return live
        }
        // No instance: report the descriptor this process may still own, so the caller can
        // recognise it instead of reading it as somebody else's leak.
        return NativeRuntimeSnapshot.NONE.copy(masterTunFd = lastMasterTunFd)
    }

    /** Forget the remembered descriptor; the master behind it is closed. */
    fun forgetMasterTunFd() {
        lastMasterTunFd = null
    }

    fun clear() {
        synchronized(lock) {
            instance = null
        }
    }
}

/**
 * The core's account of the last stop, or a reason it could not be read. Never throws: called on
 * the teardown path, where a diagnostic fetch failure must not become a second failure — and the
 * library may legitimately be absent.
 */
fun foxCoreLastStopDiagnostics(): String =
    runCatching { FoxholeNativeEngine.nativeLastStopDiagnostics().orEmpty() }
        .getOrElse { error -> "unavailable:${error.javaClass.simpleName}" }
        .ifBlank { "unavailable:empty" }

fun VpnSession.runtimeTransportProtocol(): TransportProtocol =
    when (VpnHealthProbeTargetSelector.select(configJson)?.transport) {
        VpnHealthProbeTransport.TCP -> TransportProtocol.TCP
        VpnHealthProbeTransport.UDP -> TransportProtocol.UDP
        null -> TransportProtocol.UNKNOWN
    }
