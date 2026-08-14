package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSignedDnsRuleSetUpdate
import com.foxhole.core.runtime.DnsFilterRuntimePaths
import com.foxhole.guard.core.data.deleteLocalDataTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private data class SignedDnsRuleSetBundle(
    val manifest: DnsFilterManifest,
    val update: FoxCoreSignedDnsRuleSetUpdate,
)

class DnsFilterAssetInstaller(
    private val appContext: Context,
    private val json: Json,
) : DnsFilterRuleSetStore {
    suspend fun prepareVerifiedOrNull(): DnsFilterRuntimePaths? =
        withContext(Dispatchers.IO) {
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME)
            retireObsoleteCacheFiles(targetDir)
            val signedBundle = loadSignedBundleOrNull(targetDir) ?: return@withContext null
            val artifact = File(targetDir, SIGNED_DNS_FILTER_FILE_NAME)
            DnsFilterRuntimePaths(
                adGuardDnsFilterPath = artifact.absolutePath,
                foxCoreBootstrap =
                FoxCoreDnsRuleSetBootstrap(
                    name = FOXCORE_DNS_RULE_SET_NAME,
                    artifactPath = artifact.absolutePath,
                    artifactSha256 = signedBundle.manifest.artifact.sha256,
                    publicKeyBase64 = FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64,
                    minimumSequence = signedBundle.manifest.sequence,
                    signedUpdate = signedBundle.update,
                ),
                adGuardVpnCompatibilityDomains = loadAdGuardVpnCompatibilityDomains(),
            )
        }

    override suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String =
        withContext(Dispatchers.IO) {
            val manifest = ruleSet.manifest
            require(manifest.name == FOXCORE_DNS_RULE_SET_NAME)
            require(manifest.format == FOXCORE_DNS_FORMAT)
            require(ruleSet.manifestBytes.size in 1..MAX_MANIFEST_BYTES)
            require(ruleSet.signatureBytes.size in 1..MAX_SIGNATURE_BYTES)
            require(
                ruleSet.artifactBytes.isValidRuleSet(
                    manifest.artifact.size,
                    manifest.artifact.sha256,
                ),
            )
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME).apply { mkdirs() }
            val artifact = File(targetDir, SIGNED_DNS_FILTER_FILE_NAME)
            val signature = File(targetDir, SIGNED_DNS_FILTER_SIGNATURE_NAME)
            val manifestFile = File(targetDir, SIGNED_DNS_FILTER_MANIFEST_NAME)

            // The manifest is the commit marker and is replaced last. A crash
            // before then leaves the previous complete bundle active; a crash
            // after then sees all three new files.
            writeReplacing(targetDir, artifact, ruleSet.artifactBytes)
            writeReplacing(targetDir, signature, ruleSet.signatureBytes)
            writeReplacing(targetDir, manifestFile, ruleSet.manifestBytes)
            require(loadSignedBundleOrNull(targetDir) != null) {
                "signed DNS update did not survive persistence validation"
            }
            artifact.absolutePath
        }

    suspend fun installedManifestOrNull(): DnsFilterManifest? =
        withContext(Dispatchers.IO) {
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME)
            loadSignedBundleOrNull(targetDir)?.manifest
        }

    suspend fun installedRuleSetsOrEmpty(): InstalledDnsRuleSets =
        withContext(Dispatchers.IO) {
            val targetDir = File(appContext.filesDir, TARGET_DIR_NAME)
            val manifest = loadSignedBundleOrNull(targetDir)?.manifest
                ?: return@withContext InstalledDnsRuleSets()
            InstalledDnsRuleSets(
                mapOf(
                    com.foxhole.core.model.DnsFilterCategory.ADS.name to
                        InstalledDnsRuleSet(
                            tag = manifest.name,
                            sha256 = manifest.artifact.sha256,
                            size = manifest.artifact.size,
                            sequence = manifest.sequence,
                        ),
                ),
            )
        }

    suspend fun clearLocalCache(): Int =
        withContext(Dispatchers.IO) {
            deleteDnsFilterCache(File(appContext.filesDir, TARGET_DIR_NAME))
        }

    private fun loadSignedBundleOrNull(targetDir: File): SignedDnsRuleSetBundle? =
        runCatching {
            val manifestFile = File(targetDir, SIGNED_DNS_FILTER_MANIFEST_NAME)
            val signature = File(targetDir, SIGNED_DNS_FILTER_SIGNATURE_NAME)
            require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES.toLong())
            require(signature.isFile && signature.length() in 1..MAX_SIGNATURE_BYTES.toLong())
            val manifestBytes = manifestFile.readBytes()
            val signatureBytes = signature.readBytes()
            requireFoxholeDbManifestSignature(manifestBytes, signatureBytes)
            val manifest =
                json.decodeFromString<DnsFilterManifest>(manifestBytes.toString(Charsets.UTF_8)).also { decoded ->
                    require(decoded.schema == 2)
                    require(decoded.name == FOXCORE_DNS_RULE_SET_NAME)
                    require(decoded.format == FOXCORE_DNS_FORMAT)
                    require(decoded.artifact.file == EXPECTED_DNS_FILTER_ARTIFACT_FILE)
                    require(
                        decoded.keySha256.equals(
                            FOXHOLE_DB_MANIFEST_PUBLIC_KEY_SHA256,
                            ignoreCase = true,
                        ),
                    )
                }
            val artifact = File(targetDir, SIGNED_DNS_FILTER_FILE_NAME)
            require(artifact.isValidRuleSet(manifest.artifact.size, manifest.artifact.sha256))
            SignedDnsRuleSetBundle(
                manifest = manifest,
                update = FoxCoreSignedDnsRuleSetUpdate(
                    manifestPath = manifestFile.absolutePath,
                    signaturePath = signature.absolutePath,
                    artifactPath = artifact.absolutePath,
                ),
            )
        }.getOrNull()

    private fun File.isValidRuleSet(
        sizeBytes: Long,
        sha256: String,
    ): Boolean =
        isFile &&
            length() == sizeBytes &&
            hasFoxCoreDnsFstHeader() &&
            sha256Hex().equals(sha256, ignoreCase = true)

    private fun ByteArray.isValidRuleSet(
        sizeBytes: Long,
        sha256: String,
    ): Boolean =
        size.toLong() == sizeBytes &&
            isFoxCoreDnsFst() &&
            sha256Hex().equals(sha256, ignoreCase = true)

    private fun File.hasFoxCoreDnsFstHeader(): Boolean =
        runCatching {
            inputStream().use { input ->
                val header = ByteArray(FOXCORE_DNS_FST_MAGIC.size)
                input.read(header) == header.size &&
                    header.contentEquals(FOXCORE_DNS_FST_MAGIC)
            }
        }.getOrDefault(false)

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
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

    private fun writeReplacing(
        targetDir: File,
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File.createTempFile(target.name, ".tmp", targetDir)
        runCatching {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            temporary.setReadable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(false, false)
            temporary.setWritable(true, true)
            moveReplacing(temporary, target)
        }.onFailure {
            temporary.delete()
        }.getOrThrow()
    }

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

    private fun retireObsoleteCacheFiles(targetDir: File) {
        listOf(
            EXPECTED_DNS_FILTER_ARTIFACT_FILE,
            "adguard-dns-filter.verified.srs",
            "foxhole-dns-categories.verified.manifest.json",
        ).forEach { name ->
            File(targetDir, name).takeIf(File::isFile)?.delete()
        }
    }

    internal companion object {
        const val TARGET_DIR_NAME = "dns-rule-sets"
        const val FOXCORE_DNS_RULE_SET_NAME = "foxhole-adguard-dns-filter"
        const val FOXCORE_DNS_FORMAT = "foxhole-dns-fst-v1"
        const val EXPECTED_DNS_FILTER_ARTIFACT_FILE = "adguard-dns-filter.fhds"
        const val SIGNED_DNS_FILTER_FILE_NAME = "adguard-dns-filter.signed.fhds"
        const val SIGNED_DNS_FILTER_MANIFEST_NAME = "adguard-dns-filter.signed.manifest.json"
        const val SIGNED_DNS_FILTER_SIGNATURE_NAME = "adguard-dns-filter.signed.manifest.sig"
        const val ADGUARD_VPN_COMPATIBILITY_ASSET_PATH =
            "rule-sets/adguard-vpn-compatibility-allowlist.txt"
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_SIGNATURE_BYTES = 256
        const val FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUucWYOJ+RmNoGzlv6lyQ7TdvK1Op" +
                "6TRMy+ADsghRHXfKh8gIytQMTq0hKq7TB1GRmVeyysYX3kpmuGGz1buayA=="
        val FOXCORE_DNS_FST_MAGIC =
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

internal fun deleteDnsFilterCache(targetDir: File): Int =
    deleteLocalDataTarget(targetDir)
