package com.foxhole.core.runtime

import android.net.Network
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns direct JNI engine operations, including their bounded blocking-call policy.
 *
 * [FoxCoreRuntime] remains the state/TUN owner. Keeping translation, preflight, start, policy
 * reload and stop calls here makes the boundary explicit: callers must invoke these operations
 * outside their transition mutex and commit the result separately under generation ownership.
 */
internal class FoxCoreNativeEngineOperations(
    private val native: FoxCoreNativeApi,
    private val diagnostics: RuntimeDiagnosticsSink,
    private val translator: FoxCoreConfigTranslator,
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val starter = FoxCoreNativeSessionStarter(native, diagnostics)

    fun translate(
        session: VpnSession,
        expectedPolicyRevision: Long?,
    ): FoxCoreSessionConfig? =
        try {
            translator.requirePrepared(session, expectedPolicyRevision)
        } catch (error: FoxCoreConfigTranslationException) {
            diagnostics.recordStructured(
                "foxcore",
                "prepared config rejected",
                "reason=${error.rejection.name.lowercase()}",
                "path=${error.path}",
                error.explanation?.let { "detail=$it" },
            )
            null
        } catch (_: Exception) {
            diagnostics.record("foxcore", "prepared config validation failed")
            null
        }

    fun preflight(): Result<Unit> = preflightNative(native, diagnostics, json)

    fun start(
        tun: ParcelFileDescriptor,
        network: Network,
        host: RuntimeServiceHost,
        translated: FoxCoreSessionConfig,
    ): ActiveFoxCoreSession? =
        starter
            .start(
                tun = tun,
                network = network,
                host = host,
                translated = translated,
            )?.let { started ->
                ActiveFoxCoreSession(
                    handle = started.handle,
                    tun = tun,
                    masterTunFd = tun.fd,
                    nativeTunFd = started.nativeTunFd,
                    host = host,
                    translated = translated,
                    immutableFingerprint = started.immutableFingerprint,
                    policyRevision = started.policyRevision,
                    networkHandle = network.networkHandle,
                    dnsServers = emptyList(),
                )
            }

    fun immutableFingerprint(translated: FoxCoreSessionConfig): String? =
        starter.immutableEngineFingerprint(
            configJson = translated.engineConfigJson,
            dnsRuleSetBootstrap = translated.dnsRuleSetBootstrap,
        )

    fun reloadPolicy(
        current: ActiveFoxCoreSession,
        translated: FoxCoreSessionConfig,
    ): PolicyReloadOutcome =
        attemptPolicyReload(
            native = native,
            handle = current.handle,
            policyJson = translated.policyConfigJson,
            diagnosticsLogger = diagnostics,
        )

    suspend fun stop(
        handle: Long,
        policy: RuntimeStopPolicy,
    ): NativeStopOutcome {
        val result = AtomicInteger(FoxholeNativeEngine.STOP_PANICKED)
        val completed =
            runBlockingRuntimeClose(policy.totalGracefulTimeoutMs) {
                result.set(native.stop(handle))
            }
        val stopped =
            completed &&
                result.get() in
                setOf(
                    FoxholeNativeEngine.STOPPED,
                    FoxholeNativeEngine.ALREADY_STOPPED,
                    FoxholeNativeEngine.STOP_UNKNOWN_HANDLE,
                )
        return if (stopped || !policy.forceKillAfterTimeout) {
            NativeStopOutcome(stopped = stopped, escalated = false)
        } else {
            NativeStopOutcome(
                stopped = forceKill(handle),
                escalated = true,
            )
        }
    }

    /**
     * STOP_TIMED_OUT means native already removed the handle before quarantining its worker.
     * Kotlin timeout and STOP_PANICKED remain failures because ownership was never released.
     */
    suspend fun forceKill(handle: Long): Boolean {
        val result = AtomicInteger(FoxholeNativeEngine.STOP_PANICKED)
        val completed =
            runBlockingRuntimeClose(RUNTIME_FORCE_KILL_TIMEOUT_MS) {
                result.set(native.forceKill(handle))
            }
        return completed && forceKillReleasedHandle(result.get())
    }

    fun recordUnavailableOutbounds(handle: Long) {
        val unavailable =
            runCatching { native.stats(handle) }
                .getOrNull()
                ?.let(::parseNativeUnavailableOutbounds)
                .orEmpty()
        unavailable.forEach { outbound ->
            diagnostics.recordStructured(
                "foxcore",
                "native outbound unavailable",
                "id=${outbound.id}",
                "kind=${outbound.kind}",
                "reason=${outbound.reason}",
                "attempts=${outbound.attempts}",
                outbound.message.takeIf { outbound.kind == "tor" }?.let { "message=$it" },
            )
        }
    }
}
