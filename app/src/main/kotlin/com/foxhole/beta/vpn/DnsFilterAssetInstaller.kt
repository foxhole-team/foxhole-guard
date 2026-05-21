package com.foxhole.beta.vpn

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class DnsFilterRuntimePaths(
    val adGuardDnsFilterPath: String,
    val adGuardVpnCompatibilityDomains: List<String> = emptyList(),
)

class DnsFilterAssetInstaller(
    private val appContext: Context,
    private val json: Json,
) : DnsFilterRuleSetStore {
    suspend fun prepare(): DnsFilterRuntimePaths =
        withContext(Dispatchers.IO) {
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME).apply { mkdirs() }
            val verifiedTarget = File(targetDir, VERIFIED_DNS_FILTER_FILE_NAME)
            val verifiedManifest = File(targetDir, VERIFIED_DNS_FILTER_MANIFEST_NAME)
            val target = File(targetDir, ADGUARD_DNS_FILTER_FILE_NAME)
            if (!target.isValidAdGuardDnsFilter()) {
                appContext.assets.open(ADGUARD_DNS_FILTER_ASSET_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            require(target.isValidAdGuardDnsFilter()) { "bundled AdGuard DNS filter checksum mismatch" }
            DnsFilterRuntimePaths(
                adGuardDnsFilterPath = verifiedTarget.takeIf { it.isValidVerifiedDnsFilter(verifiedManifest) }?.absolutePath
                    ?: target.absolutePath,
                adGuardVpnCompatibilityDomains = loadAdGuardVpnCompatibilityDomains(),
            )
        }

    override suspend fun installVerifiedDnsRuleSet(
        ruleSetBytes: ByteArray,
        manifest: DnsFilterManifest,
    ): String =
        withContext(Dispatchers.IO) {
            require(ruleSetBytes.size.toLong() == manifest.artifact.size) { "verified rule set size mismatch" }
            require(ruleSetBytes.isSingBoxSrs()) { "verified rule set header mismatch" }
            require(ruleSetBytes.sha256Hex() == manifest.artifact.sha256) { "verified rule set sha256 mismatch" }
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME).apply { mkdirs() }
            val target = File(targetDir, VERIFIED_DNS_FILTER_FILE_NAME)
            val temp = File.createTempFile(VERIFIED_DNS_FILTER_FILE_NAME, ".tmp", targetDir)
            runCatching {
                temp.writeBytes(ruleSetBytes)
                require(temp.isValidRuleSet(manifest.artifact.size, manifest.artifact.sha256)) {
                    "verified rule set temp validation failed"
                }
                moveReplacing(temp, target)
                require(target.isValidRuleSet(manifest.artifact.size, manifest.artifact.sha256)) {
                    "verified rule set install validation failed"
                }
                File(targetDir, VERIFIED_DNS_FILTER_MANIFEST_NAME).writeText(
                    json.encodeToString(manifest),
                    Charsets.UTF_8,
                )
                target.absolutePath
            }.onFailure {
                temp.delete()
            }.getOrThrow()
        }

    private fun File.isValidAdGuardDnsFilter(): Boolean =
        isValidRuleSet(ADGUARD_DNS_FILTER_SIZE_BYTES, ADGUARD_DNS_FILTER_SHA256)

    private fun File.isValidVerifiedDnsFilter(manifestFile: File): Boolean =
        runCatching {
            val manifest = json.decodeFromString<DnsFilterManifest>(manifestFile.readText(Charsets.UTF_8))
            isValidRuleSet(manifest.artifact.size, manifest.artifact.sha256)
        }.getOrDefault(false)

    private fun File.isValidRuleSet(
        sizeBytes: Long,
        sha256: String,
    ): Boolean =
        isFile &&
            length() == sizeBytes &&
            hasSingBoxSrsHeader() &&
            sha256Hex() == sha256

    private fun File.hasSingBoxSrsHeader(): Boolean =
        runCatching {
            inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == header.size && header.isSingBoxSrs()
            }
        }.getOrDefault(false)

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun loadAdGuardVpnCompatibilityDomains(): List<String> =
        runCatching {
            appContext.assets.open(ADGUARD_VPN_COMPATIBILITY_ASSET_PATH)
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .map(String::trim)
                        .filter { line -> line.isNotBlank() && !line.startsWith("#") }
                        .map { line -> line.removePrefix("*.").removePrefix(".").lowercase() }
                        .distinct()
                        .toList()
                }
        }.getOrDefault(emptyList())

    private fun moveReplacing(
        source: File,
        target: File,
    ) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TARGET_DIR_NAME = "dns-rule-sets"
        const val ADGUARD_DNS_FILTER_FILE_NAME = "adguard-dns-filter.srs"
        const val ADGUARD_DNS_FILTER_ASSET_PATH = "rule-sets/$ADGUARD_DNS_FILTER_FILE_NAME"
        const val ADGUARD_VPN_COMPATIBILITY_ASSET_PATH = "rule-sets/adguard-vpn-compatibility-allowlist.txt"
        const val ADGUARD_DNS_FILTER_SIZE_BYTES = 1_450_468L
        const val ADGUARD_DNS_FILTER_SHA256 = "ccb39947545fbdc4dc3d0660e532f28daf3029c91891fe573c92c0b1caaf951f"
        const val VERIFIED_DNS_FILTER_FILE_NAME = "adguard-dns-filter.verified.srs"
        const val VERIFIED_DNS_FILTER_MANIFEST_NAME = "adguard-dns-filter.verified.manifest.json"
    }
}
