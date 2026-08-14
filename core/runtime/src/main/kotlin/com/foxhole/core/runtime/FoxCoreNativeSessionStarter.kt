package com.foxhole.core.runtime

import android.net.Network
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Performs the security-sensitive handoff from the Android-owned TUN to one FoxCore JNI session.
 *
 * The caller retains the master descriptor. FoxCore receives only a duplicate and owns that
 * duplicate as soon as the JNI start call is entered.
 */
internal class FoxCoreNativeSessionStarter(
    private val native: FoxCoreNativeApi,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    private val json = Json { ignoreUnknownKeys = false }

    fun start(
        tun: ParcelFileDescriptor,
        network: Network,
        host: RuntimeServiceHost,
        translated: FoxCoreSessionConfig,
    ): FoxCoreNativeSessionStart? {
        val immutableFingerprint =
            immutableEngineFingerprint(translated.engineConfigJson) ?: return null
        val dnsPayload = trustedDnsPayload(translated.dnsRuleSetBootstrap) ?: return null
        val nativeHandle =
            startHandle(
                tun = tun,
                network = network,
                host = host,
                translated = translated,
                dnsPayload = dnsPayload,
            ) ?: return null
        return FoxCoreNativeSessionStart(
            handle = nativeHandle.handle,
            nativeTunFd = nativeHandle.tunFd,
            immutableFingerprint = immutableFingerprint,
            policyRevision = installSignedDnsUpdate(nativeHandle.handle, translated.dnsRuleSetBootstrap),
        )
    }

    fun immutableEngineFingerprint(configJson: String): String? =
        runCatching {
            val root = json.parseToJsonElement(configJson).jsonObject
            val immutable =
                buildJsonObject {
                    IMMUTABLE_ENGINE_KEYS.forEach { key ->
                        root[key]?.let { value -> put(key, value) }
                    }
                }
            sha256(immutable.toString().toByteArray(Charsets.UTF_8))
        }.getOrNull()

    private fun trustedDnsPayload(bootstrap: FoxCoreDnsRuleSetBootstrap?): TrustedDnsPayload? {
        if (bootstrap == null) {
            return TrustedDnsPayload()
        }
        return loadTrustedDnsRuleSet(bootstrap.artifactPath, bootstrap.artifactSha256)
            ?.let(::TrustedDnsPayload)
    }

    private fun startHandle(
        tun: ParcelFileDescriptor,
        network: Network,
        host: RuntimeServiceHost,
        translated: FoxCoreSessionConfig,
        dnsPayload: TrustedDnsPayload,
    ): FoxCoreNativeHandle? {
        val duplicatedFd =
            runCatching { ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd() }
                .getOrNull()
                ?: return null
        val handle =
            runCatching {
                translated.dnsRuleSetBootstrap?.let { bootstrap ->
                    native.startWithNetworkAndTrustedDnsRuleSet(
                        tunFd = duplicatedFd,
                        configJson = translated.engineConfigJson,
                        networkHandle = network.networkHandle,
                        name = bootstrap.name,
                        artifact = requireNotNull(dnsPayload.artifact),
                        host = host,
                    )
                } ?: native.startWithNetwork(
                    tunFd = duplicatedFd,
                    configJson = translated.engineConfigJson,
                    networkHandle = network.networkHandle,
                    host = host,
                )
            }.onFailure { error ->
                // Do not close duplicatedFd here. Rust owns it from JNI entry onward, and closing
                // the raw integer after rejection could hit a descriptor that the OS already reused.
                diagnosticsLogger.recordStructured(
                    "foxcore",
                    "native start rejected",
                    "reason=${nativeStartFailureReason(error)}",
                )
            }.getOrNull()
        val acceptedHandle = handle?.takeIf { candidate -> candidate > 0L }
        if (acceptedHandle == null) {
            diagnosticsLogger.record("foxcore", "native start returned no handle")
            return null
        }
        return FoxCoreNativeHandle(
            handle = acceptedHandle,
            tunFd = duplicatedFd,
        )
    }

    private fun loadTrustedDnsRuleSet(
        path: String,
        expectedSha256: String,
    ): ByteArray? =
        runCatching {
            val file = File(path)
            require(file.isFile)
            require(file.length() in 1..MAX_DNS_RULE_SET_BYTES)
            val bytes = file.readBytes()
            require(bytes.size >= FOXCORE_DNS_RULE_SET_MAGIC.size)
            require(bytes.startsWith(FOXCORE_DNS_RULE_SET_MAGIC))
            require(sha256(bytes).equals(expectedSha256, ignoreCase = true))
            bytes
        }.onFailure {
            diagnosticsLogger.record("dns", "trusted FoxCore DNS rule set rejected")
        }.getOrNull()

    private fun installSignedDnsUpdate(
        handle: Long,
        bootstrap: FoxCoreDnsRuleSetBootstrap?,
    ): Long =
        bootstrap
            ?.signedUpdate
            ?.let { update ->
                runCatching {
                    native.installDnsRuleSet(
                        handle = handle,
                        name = bootstrap.name,
                        manifest = readBoundedFile(update.manifestPath, MAX_DNS_RULE_SET_MANIFEST_BYTES),
                        signature = readBoundedFile(update.signaturePath, MAX_DNS_RULE_SET_SIGNATURE_BYTES),
                        artifact = readBoundedFile(update.artifactPath, MAX_DNS_RULE_SET_BYTES),
                    )
                }.onFailure {
                    // The Android-verified artifact remains active. A stale, corrupt or
                    // rolled-back update must neither fail open nor tear down a healthy tunnel.
                    diagnosticsLogger.record("dns", "signed FoxCore DNS update rejected")
                }.getOrNull()
            }
            ?.takeIf { revision -> revision > 0L }
            ?: INITIAL_POLICY_REVISION

    private fun readBoundedFile(
        path: String,
        maxBytes: Long,
    ): ByteArray {
        val file = File(path)
        require(file.isFile)
        require(file.length() in 1..maxBytes)
        return file.readBytes()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun nativeStartFailureReason(error: Throwable): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "previous foxcore worker" in message -> "worker_lease_busy"
            message.containsAny("start named loopback inbound", "address already in use") ->
                "loopback_inbound_start_failed"
            "start runtime control proxy" in message -> "control_proxy_start_failed"
            message.containsAny("uid/package routing", "flow attribution") ->
                "flow_attribution_unavailable"
            "initialization timed out" in message -> "initialization_timeout"
            "initialize outbounds" in message -> "outbound_initialization_failed"
            "initialize dns policy" in message -> "dns_policy_initialization_failed"
            "open tun descriptor" in message -> "tun_open_failed"
            "engine config" in message -> "config_parse_failed"
            "panic inside foxcore" in message -> "native_panic"
            error is SecurityException -> "security_exception"
            error is IllegalArgumentException -> "invalid_argument"
            else -> "native_exception"
        }
    }

    private fun String.containsAny(vararg fragments: String): Boolean = fragments.any(::contains)

    private companion object {
        const val INITIAL_POLICY_REVISION = 1L
        const val MAX_DNS_RULE_SET_BYTES = 64L * 1024L * 1024L
        const val MAX_DNS_RULE_SET_MANIFEST_BYTES = 64L * 1024L
        const val MAX_DNS_RULE_SET_SIGNATURE_BYTES = 256L

        val IMMUTABLE_ENGINE_KEYS = listOf("schema_version", "outbound", "outbounds", "tun", "runtime")
        val FOXCORE_DNS_RULE_SET_MAGIC =
            byteArrayOf(
                'F'.code.toByte(),
                'H'.code.toByte(),
                'D'.code.toByte(),
                'N'.code.toByte(),
                'S'.code.toByte(),
                '1'.code.toByte(),
                0,
                0,
            )
    }
}

internal data class FoxCoreNativeSessionStart(
    val handle: Long,
    val nativeTunFd: Int,
    val immutableFingerprint: String,
    val policyRevision: Long,
)

private data class FoxCoreNativeHandle(
    val handle: Long,
    val tunFd: Int,
)

private data class TrustedDnsPayload(
    val artifact: ByteArray? = null,
)
