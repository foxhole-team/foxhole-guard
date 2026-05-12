package com.foxhole.beta.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class DnsFilterRuntimePaths(
    val adGuardDnsFilterPath: String,
    val adGuardVpnCompatibilityDomains: List<String> = emptyList(),
)

class DnsFilterAssetInstaller(
    private val appContext: Context,
) {
    suspend fun prepare(): DnsFilterRuntimePaths =
        withContext(Dispatchers.IO) {
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME).apply { mkdirs() }
            val target = File(targetDir, ADGUARD_DNS_FILTER_FILE_NAME)
            if (!target.isValidAdGuardDnsFilter()) {
                appContext.assets.open(ADGUARD_DNS_FILTER_ASSET_PATH).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            require(target.isValidAdGuardDnsFilter()) { "bundled AdGuard DNS filter checksum mismatch" }
            DnsFilterRuntimePaths(
                adGuardDnsFilterPath = target.absolutePath,
                adGuardVpnCompatibilityDomains = loadAdGuardVpnCompatibilityDomains(),
            )
        }

    private fun File.isValidAdGuardDnsFilter(): Boolean =
        isFile &&
            length() == ADGUARD_DNS_FILTER_SIZE_BYTES &&
            sha256Hex() == ADGUARD_DNS_FILTER_SHA256

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

    private companion object {
        const val TARGET_DIR_NAME = "dns-rule-sets"
        const val ADGUARD_DNS_FILTER_FILE_NAME = "adguard-dns-filter.srs"
        const val ADGUARD_DNS_FILTER_ASSET_PATH = "rule-sets/$ADGUARD_DNS_FILTER_FILE_NAME"
        const val ADGUARD_VPN_COMPATIBILITY_ASSET_PATH = "rule-sets/adguard-vpn-compatibility-allowlist.txt"
        const val ADGUARD_DNS_FILTER_SIZE_BYTES = 1_450_468L
        const val ADGUARD_DNS_FILTER_SHA256 = "ccb39947545fbdc4dc3d0660e532f28daf3029c91891fe573c92c0b1caaf951f"
    }
}
