package com.foxhole.core.runtime

import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class NativeUnavailableOutbound(
    val id: String,
    val kind: String,
    val reason: String,
    val message: String,
    val attempts: Int,
)

internal fun parseNativeUnavailableOutbounds(source: String): List<NativeUnavailableOutbound> =
    runCatching {
        val root = Json.parseToJsonElement(source).jsonObject
        (root["unavailable"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val unavailable = element as? JsonObject ?: return@mapNotNull null
                val id = unavailable.nativeString("id").orEmpty()
                val kind = unavailable.nativeString("kind").orEmpty()
                val reason = unavailable.nativeString("reason").orEmpty()
                val message = unavailable.nativeString("message").orEmpty()
                val requiredFields = listOf(id, kind, reason, message)
                if (requiredFields.any(String::isBlank)) {
                    null
                } else {
                    NativeUnavailableOutbound(
                        id = id,
                        kind = kind,
                        reason = reason,
                        message = message,
                        attempts = unavailable["attempts"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1,
                    )
                }
            }
    }.getOrDefault(emptyList())

private fun JsonObject.nativeString(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)

@Suppress("TooManyFunctions")
internal interface FoxCoreNativeApi {
    fun version(): String

    fun abiVersion(): Int

    fun capabilities(): String

    fun startWithNetwork(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        host: RuntimeServiceHost,
    ): Long

    fun startWithNetworkAndDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long = 0L

    fun startWithNetworkAndTrustedDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long

    fun installDnsRuleSet(
        handle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): Long

    fun stop(handle: Long): Int

    fun forceKill(handle: Long): Int

    fun reloadPolicy(
        handle: Long,
        policyJson: String,
    ): Long

    fun lastPolicyError(handle: Long): String

    fun stats(handle: Long): String

    fun connections(handle: Long): String

    fun trafficMap(handle: Long): String = connections(handle)

    fun drainTrafficEvents(
        handle: Long,
        max: Int,
    ): String

    fun drainEvents(
        handle: Long,
        max: Int,
    ): String

    fun networkChanged(handle: Long)

    fun networkChangedWithHandle(
        handle: Long,
        networkHandle: Long,
    )

    fun confirmLanNetwork(
        handle: Long,
        networkHandle: Long,
        localAddress: String,
        interfaceName: String,
        transport: String,
    ): Int = LAN_PROXY_NOT_LINKED

    fun startLanProxy(
        handle: Long,
        configJson: String,
    ): Int = LAN_PROXY_NOT_LINKED

    fun stopLanProxy(handle: Long): Int = LAN_PROXY_NOT_LINKED

    fun revokeFlows(
        handle: Long,
        targetJson: String,
    ): Int = LAN_PROXY_NOT_LINKED

    /** Empty when the core cannot be asked; never null. */
    fun lanProxyStatus(handle: Long): String = ""

    fun startLoopbackInbound(
        handle: Long,
        configJson: String,
    ): Int = LAN_PROXY_NOT_LINKED

    fun stopLoopbackInbound(
        handle: Long,
        name: String,
    ): Int = LAN_PROXY_NOT_LINKED

    /** Empty when the core cannot be asked; never null. */
    fun loopbackInbounds(handle: Long): String = ""
}

const val LAN_PROXY_NOT_LINKED: Int = Int.MIN_VALUE

internal object JniFoxCoreNativeApi : FoxCoreNativeApi {
    override fun version(): String = FoxholeNativeEngine.nativeVersion()

    override fun abiVersion(): Int = FoxholeNativeEngine.nativeAbiVersion()

    override fun capabilities(): String = FoxholeNativeEngine.nativeCapabilities()

    override fun startWithNetwork(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        host: RuntimeServiceHost,
    ): Long =
        FoxholeNativeEngine.nativeStartWithNetwork(
            tunFd,
            configJson,
            networkHandle,
            host,
        )

    override fun startWithNetworkAndDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long =
        FoxholeNativeEngine.nativeStartWithNetworkAndDnsRuleSet(
            tunFd,
            configJson,
            networkHandle,
            name,
            manifest,
            signature,
            artifact,
            host,
        )

    override fun startWithNetworkAndTrustedDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long =
        FoxholeNativeEngine.nativeStartWithNetworkAndTrustedDnsRuleSet(
            tunFd,
            configJson,
            networkHandle,
            name,
            artifact,
            host,
        )

    override fun installDnsRuleSet(
        handle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): Long =
        FoxholeNativeEngine.nativeInstallDnsRuleSet(
            handle,
            name,
            manifest,
            signature,
            artifact,
        )

    override fun stop(handle: Long): Int = FoxholeNativeEngine.nativeStop(handle)

    override fun forceKill(handle: Long): Int = FoxholeNativeEngine.nativeForceKill(handle)

    override fun reloadPolicy(
        handle: Long,
        policyJson: String,
    ): Long = FoxholeNativeEngine.nativeReloadPolicy(handle, policyJson)

    override fun lastPolicyError(handle: Long): String =
        FoxholeNativeEngine.nativeLastPolicyError(handle).orEmpty()

    override fun stats(handle: Long): String = FoxholeNativeEngine.nativeStats(handle)

    override fun connections(handle: Long): String = FoxholeNativeEngine.nativeConnections(handle)

    override fun trafficMap(handle: Long): String = FoxholeNativeEngine.nativeTrafficMap(handle)

    override fun drainTrafficEvents(
        handle: Long,
        max: Int,
    ): String = FoxholeNativeEngine.nativeDrainTrafficEvents(handle, max)

    override fun drainEvents(
        handle: Long,
        max: Int,
    ): String = FoxholeNativeEngine.nativeDrainEvents(handle, max)

    override fun networkChanged(handle: Long) {
        FoxholeNativeEngine.nativeNetworkChanged(handle)
    }

    override fun networkChangedWithHandle(
        handle: Long,
        networkHandle: Long,
    ) {
        FoxholeNativeEngine.nativeNetworkChangedWithHandle(handle, networkHandle)
    }

    override fun confirmLanNetwork(
        handle: Long,
        networkHandle: Long,
        localAddress: String,
        interfaceName: String,
        transport: String,
    ): Int =
        whenLanProxyLinked {
            FoxholeNativeEngine.nativeConfirmLanNetwork(
                handle,
                networkHandle,
                localAddress,
                interfaceName,
                transport,
            )
        }

    override fun startLanProxy(
        handle: Long,
        configJson: String,
    ): Int = whenLanProxyLinked { FoxholeNativeEngine.nativeStartLanProxy(handle, configJson) }

    override fun stopLanProxy(handle: Long): Int = whenLanProxyLinked { FoxholeNativeEngine.nativeStopLanProxy(handle) }

    override fun revokeFlows(
        handle: Long,
        targetJson: String,
    ): Int = whenLanProxyLinked { FoxholeNativeEngine.nativeRevokeFlows(handle, targetJson) }

    override fun lanProxyStatus(handle: Long): String =
        runCatching { FoxholeNativeEngine.nativeLanProxyStatus(handle).orEmpty() }
            .getOrDefault("")

    override fun startLoopbackInbound(
        handle: Long,
        configJson: String,
    ): Int = whenLanProxyLinked { FoxholeNativeEngine.nativeStartLoopbackInbound(handle, configJson) }

    override fun stopLoopbackInbound(
        handle: Long,
        name: String,
    ): Int = whenLanProxyLinked { FoxholeNativeEngine.nativeStopLoopbackInbound(handle, name) }

    override fun loopbackInbounds(handle: Long): String =
        runCatching { FoxholeNativeEngine.nativeLoopbackInbounds(handle).orEmpty() }
            .getOrDefault("")

    private inline fun whenLanProxyLinked(call: () -> Int): Int =
        try {
            call()
        } catch (_: UnsatisfiedLinkError) {
            LAN_PROXY_NOT_LINKED
        }
}

internal data class ActiveFoxCoreSession(
    val sessionIdentity: Any = Any(),
    val handle: Long,
    val tun: ParcelFileDescriptor,
    val masterTunFd: Int,
    val nativeTunFd: Int,
    val host: RuntimeServiceHost,
    val translated: FoxCoreSessionConfig,
    val immutableFingerprint: String,
    val policyRevision: Long,
    val networkHandle: Long,
    val dnsServers: List<String>,
)

internal data class NativeStopOutcome(
    val stopped: Boolean,
    val escalated: Boolean,
    val forceStopOutcome: NativeForceStopOutcome = NativeForceStopOutcome.NOT_ATTEMPTED,
) {
    val processPoisoned: Boolean
        get() = forceStopOutcome.processPoisoned
}

enum class FoxCoreRuntimeFailure(
    val code: String,
) {
    ALREADY_RUNNING("already_running"),
    NOT_RUNNING("not_running"),
    CONFIG_REJECTED("config_rejected"),
    NATIVE_UNAVAILABLE("native_unavailable"),
    ABI_MISMATCH("abi_mismatch"),
    UNDERLYING_NETWORK_UNAVAILABLE("underlying_network_unavailable"),
    TUN_ESTABLISH_FAILED("tun_establish_failed"),
    TUN_PLAN_CHANGED("tun_plan_changed"),
    NATIVE_START_FAILED("native_start_failed"),
    NATIVE_STOP_FAILED("native_stop_failed"),
    POLICY_RELOAD_FAILED("policy_reload_failed"),

    POLICY_TOR_UNAVAILABLE("policy_tor_unavailable"),

    POLICY_I2P_UNAVAILABLE("policy_i2p_unavailable"),

    POLICY_UNKNOWN_OUTBOUND("policy_unknown_outbound"),

    POLICY_REVISION_CONFLICT("policy_revision_conflict"),

    POLICY_ROUTE_UNSUPPORTED("policy_route_unsupported"),
    ROLLBACK_FAILED("rollback_failed"),
}

class FoxCoreRuntimeException internal constructor(
    val failure: FoxCoreRuntimeFailure,
) : IllegalStateException("foxcore_runtime_${failure.code}")

internal fun foxCoreFailure(failure: FoxCoreRuntimeFailure): Result<Unit> =
    Result.failure(FoxCoreRuntimeException(failure))
