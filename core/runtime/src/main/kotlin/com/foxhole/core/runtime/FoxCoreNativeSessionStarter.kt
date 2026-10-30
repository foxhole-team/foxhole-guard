package com.foxhole.core.runtime

import android.net.Network
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSessionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

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
        dnsContinuation: Long? = null,
    ): FoxCoreNativeSessionStart? {
        if (dnsContinuation != null && dnsContinuation <= 0L) return null
        val immutableFingerprint =
            immutableEngineFingerprint(
                configJson = translated.engineConfigJson,
                dnsRuleSetBootstrap = translated.dnsRuleSetBootstrap,
            ) ?: return null
        val dnsPayload = dnsStartPayload(translated.dnsRuleSetBootstrap) ?: return null
        val nativeHandle =
            startHandle(
                tun = tun,
                network = network,
                host = host,
                translated = translated,
                dnsPayload = dnsPayload,
                dnsContinuation = dnsContinuation,
            ) ?: return null
        return FoxCoreNativeSessionStart(
            handle = nativeHandle.handle,
            nativeTunFd = nativeHandle.tunFd,
            immutableFingerprint = immutableFingerprint,
            policyRevision = INITIAL_POLICY_REVISION,
        )
    }

    fun immutableEngineFingerprint(
        configJson: String,
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap?,
    ): String? =
        runCatching {
            val root = json.parseToJsonElement(configJson).jsonObject
            val immutable =
                buildJsonObject {
                    IMMUTABLE_ENGINE_KEYS.forEach { key ->
                        root[key]?.let { value -> put(key, value) }
                    }
                    put(
                        DNS_RULE_SET_TRUST_KEY,
                        dnsRuleSetBootstrap?.let { bootstrap ->
                            buildJsonObject {
                                put("name", bootstrap.name)
                                put("public_key", bootstrap.publicKeyBase64)
                            }
                        } ?: JsonNull,
                    )
                }
            sha256(immutable.toString().toByteArray(Charsets.UTF_8))
        }.getOrNull()

    private fun dnsStartPayload(bootstrap: FoxCoreDnsRuleSetBootstrap?): FoxCoreDnsStartPayload? {
        if (bootstrap == null) {
            return FoxCoreDnsStartPayload.None
        }
        return runCatching {
            val update = bootstrap.signedUpdate
            val artifact =
                readVerifiedDnsArtifact(
                    path = update?.artifactPath ?: bootstrap.artifactPath,
                    expectedSha256 = bootstrap.artifactSha256,
                )
            if (update == null) {
                FoxCoreDnsStartPayload.Trusted(
                    name = bootstrap.name,
                    artifact = artifact,
                )
            } else {
                FoxCoreDnsStartPayload.Signed(
                    name = bootstrap.name,
                    manifest = readBoundedFile(update.manifestPath, MAX_DNS_RULE_SET_MANIFEST_BYTES),
                    signature = readBoundedFile(update.signaturePath, MAX_DNS_RULE_SET_SIGNATURE_BYTES),
                    artifact = artifact,
                )
            }
        }.onFailure {
            diagnosticsLogger.record("dns", "FoxCore DNS start payload rejected")
        }.getOrNull()
    }

    private fun startHandle(
        tun: ParcelFileDescriptor,
        network: Network,
        host: RuntimeServiceHost,
        translated: FoxCoreSessionConfig,
        dnsPayload: FoxCoreDnsStartPayload,
        dnsContinuation: Long?,
    ): FoxCoreNativeHandle? {
        val duplicatedFd =
            runCatching { ParcelFileDescriptor.dup(tun.fileDescriptor).detachFd() }
                .getOrNull()
                ?: return null
        val handle =
            invokeNativeStartWithDiagnostics(diagnosticsLogger) {
                invokeFoxCoreNativeStart(
                    native = native,
                    tunFd = duplicatedFd,
                    configJson = translated.engineConfigJson,
                    networkHandle = network.networkHandle,
                    dnsPayload = dnsPayload,
                    host = host,
                    dnsContinuation = dnsContinuation,
                )
            }
        if (dnsContinuation != null && handle != null) rejectPoisonedDnsContinuityStart(handle)
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

    private fun readVerifiedDnsArtifact(
        path: String,
        expectedSha256: String,
    ): ByteArray {
        val bytes = readBoundedFile(path, MAX_DNS_RULE_SET_BYTES)
        require(bytes.size >= FOXCORE_DNS_RULE_SET_MAGIC.size)
        require(bytes.startsWith(FOXCORE_DNS_RULE_SET_MAGIC))
        require(sha256(bytes).equals(expectedSha256, ignoreCase = true))
        return bytes
    }

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

    private companion object {
        const val INITIAL_POLICY_REVISION = 1L
        const val MAX_DNS_RULE_SET_BYTES = 64L * 1024L * 1024L
        const val MAX_DNS_RULE_SET_MANIFEST_BYTES = 64L * 1024L
        const val MAX_DNS_RULE_SET_SIGNATURE_BYTES = 256L
        const val DNS_RULE_SET_TRUST_KEY = "dns_rule_set_trust"

        val IMMUTABLE_ENGINE_KEYS = listOf("schema_version", "outbound", "outbounds", "tun", "runtime", "bootstrap_dns")
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

internal sealed interface FoxCoreDnsStartPayload {
    data object None : FoxCoreDnsStartPayload

    data class Trusted(
        val name: String,
        val artifact: ByteArray,
    ) : FoxCoreDnsStartPayload

    data class Signed(
        val name: String,
        val manifest: ByteArray,
        val signature: ByteArray,
        val artifact: ByteArray,
    ) : FoxCoreDnsStartPayload
}

internal fun invokeFoxCoreNativeStart(
    native: FoxCoreNativeApi,
    tunFd: Int,
    configJson: String,
    networkHandle: Long,
    dnsPayload: FoxCoreDnsStartPayload,
    host: RuntimeServiceHost,
    dnsContinuation: Long? = null,
): Long {
    if (dnsContinuation != null) {
        require(dnsContinuation > 0L) { "DNS continuation token must be positive" }
        return when (dnsPayload) {
            FoxCoreDnsStartPayload.None -> native.startOnNetworkWithDnsContinuity(
                tunFd, configJson, networkHandle, dnsContinuation, null, null, null, null, false, host,
            )
            is FoxCoreDnsStartPayload.Trusted -> native.startOnNetworkWithDnsContinuity(
                tunFd, configJson, networkHandle, dnsContinuation, dnsPayload.name, null, null, dnsPayload.artifact, true, host,
            )
            is FoxCoreDnsStartPayload.Signed -> native.startOnNetworkWithDnsContinuity(
                tunFd, configJson, networkHandle, dnsContinuation, dnsPayload.name, dnsPayload.manifest, dnsPayload.signature, dnsPayload.artifact, false, host,
            )
        }
    }
    return when (dnsPayload) {
        FoxCoreDnsStartPayload.None ->
            native.startWithNetwork(tunFd, configJson, networkHandle, host)
        is FoxCoreDnsStartPayload.Trusted ->
            native.startWithNetworkAndTrustedDnsRuleSet(
                tunFd,
                configJson,
                networkHandle,
                dnsPayload.name,
                dnsPayload.artifact,
                host,
            )
        is FoxCoreDnsStartPayload.Signed ->
            native.startWithNetworkAndDnsRuleSet(
                tunFd,
                configJson,
                networkHandle,
                dnsPayload.name,
                dnsPayload.manifest,
                dnsPayload.signature,
                dnsPayload.artifact,
                host,
            )
    }
}
