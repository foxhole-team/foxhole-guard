package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSignedDnsRuleSetUpdate
import com.foxhole.core.runtime.DnsFilterRuntimePaths
import com.foxhole.guard.core.data.deleteLocalDataTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    internal val categorizedStore = CategorizedDnsStagedStore(File(appContext.filesDir, "dns-categories"))
    private val packagedCategorizedStore =
        PackagedCategorizedDnsSeedStore(File(appContext.filesDir, "dns-categories-apk"))

    internal suspend fun prepareCategorized(settings: DnsSettings): CategorizedDnsPreparation =
        withContext(Dispatchers.IO) {
            val selection = settings.categorizedDnsSelection()
            if (selection.categories.isEmpty()) return@withContext CategorizedDnsPreparation.Disabled
            val signedPresent = try {
                categorizedStore.pendingOrNull(selection.variant) != null || categorizedStore.activeOrNull(selection.variant) != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.SIGNED_DATA_INVALID)
            }
            if (signedPresent) {
                val paths = prepareCategorizedOrNull(settings)
                return@withContext paths?.let(CategorizedDnsPreparation::Ready)
                    ?: CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.SIGNED_DATA_INVALID)
            }
            preparePackagedCategorized(selection)
        }

    private suspend fun preparePackagedCategorized(selection: CategorizedDnsSelection): CategorizedDnsPreparation {
        val catalogBytes = try {
            readPackagedDnsAsset("dns-categories/catalog.json", 16384)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.FileNotFoundException) {
            return CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.CATALOG_MISSING)
        } catch (_: Exception) {
            return CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.CATALOG_INVALID)
        }
        return try {
            val catalog = CategorizedDnsApkContract.parse(catalogBytes)
            val entry = catalog.variants.getValue(selection.variant)
            if (!entry.availableCategories().containsAll(selection.categories)) {
                return CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.REQUIRED_CATEGORY_MISSING)
            }
            readPackagedDnsAsset(entry.sourceInfoAsset, 65536)
            val artifact = packagedCategorizedStore.prepare(entry) {
                readPackagedDnsAsset(entry.path, entry.size.toInt())
            }
            currentCoroutineContext().ensureActive()
            CategorizedDnsPreparation.Ready(
                DnsFilterRuntimePaths(
                    adGuardDnsFilterPath = artifact.absolutePath,
                    foxCoreBootstrap = FoxCoreDnsRuleSetBootstrap(
                        name = entry.name,
                        artifactPath = artifact.absolutePath,
                        artifactSha256 = entry.sha256,
                        publicKeyBase64 = FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64,
                        categoryMinimumSequence = 0uL,
                        availableCategories = entry.availableCategories(),
                        categoryVariant = selection.variant,
                    ),
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CategorizedDnsPreparation.Unavailable(CategorizedDnsUnavailable.CATALOG_INVALID)
        }
    }

    private suspend fun readPackagedDnsAsset(path: String, limit: Int): ByteArray {
        require(limit in 1..67108864)
        return appContext.assets.open(path).use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(limit, 16384))
            val buffer = ByteArray(16384)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() <= limit - size) { "packaged DNS asset too large" }
                output.write(buffer, 0, size)
            }
            require(output.size() > 0)
            output.toByteArray()
        }
    }

    internal suspend fun prepareCategorizedOrNull(settings: DnsSettings): DnsFilterRuntimePaths? =
        withContext(Dispatchers.IO) {
            val selection = settings.categorizedDnsSelection()
            if (selection.categories.isEmpty()) return@withContext null
            try {
                val stage = categorizedStore.pendingOrNull(selection.variant)
                    ?: categorizedStore.activeOrNull(selection.variant)
                    ?: return@withContext null
                val capture = categorizedStore.readBundle(stage)
                val manifestBytes = capture.manifest
                val signatureFile = File(stage.directory, "manifest.json.sig")
                requireFoxholeDbManifestSignature(manifestBytes, capture.signature)
                val manifest = CategorizedDnsContract.parseVerifiedManifest(
                    manifestBytes,
                    (System.currentTimeMillis() / 1_000L).toULong(),
                    FOXHOLE_DB_MANIFEST_PUBLIC_KEY_SHA256,
                )
                selection.requireAvailable(manifest)
                stage.requireAccepts(manifest, manifestBytes)
                val artifact = File(stage.directory, CategorizedDnsContract.ARTIFACT_NAME)
                requireCategorizedArtifactHeader(capture.artifact, manifest)
                currentCoroutineContext().ensureActive()
                DnsFilterRuntimePaths(
                    adGuardDnsFilterPath = artifact.absolutePath,
                    foxCoreBootstrap = FoxCoreDnsRuleSetBootstrap(
                        name = manifest.name,
                        artifactPath = artifact.absolutePath,
                        artifactSha256 = manifest.artifact.sha256,
                        publicKeyBase64 = FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64,
                        signedUpdate = FoxCoreSignedDnsRuleSetUpdate(
                            manifestPath = File(stage.directory, "manifest.json").absolutePath,
                            signaturePath = signatureFile.absolutePath,
                            artifactPath = artifact.absolutePath,
                        ),
                        categoryMinimumSequence = manifest.sequence,
                        availableCategories = CategorizedDnsContract.categories(manifest),
                        categoryVariant = selection.variant,
                        stagedBundleToken = stage.token,
                        manifestSha256 = stage.manifestSha256,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    internal suspend fun captureCategorizedBootstrapReadLease(
        bootstrap: FoxCoreDnsRuleSetBootstrap,
    ): CategorizedDnsReadLease? {
        val variant = bootstrap.categoryVariant ?: return null
        val stage = categorizedStore.pendingOrNull(variant) ?: categorizedStore.activeOrNull(variant) ?: return null
        if (!bootstrap.matches(stage)) return null
        return categorizedStore.acquireReadLease(stage)
    }

    private fun FoxCoreDnsRuleSetBootstrap.matches(stage: CategorizedDnsStage): Boolean {
        val expected = listOf(
            stage.token,
            stage.name,
            stage.manifestSha256,
            stage.artifactSha256,
            stage.sequence.toString(),
            File(stage.directory, CategorizedDnsContract.ARTIFACT_NAME).absolutePath
        )
        val captured = listOf(
            stagedBundleToken,
            name,
            manifestSha256,
            artifactSha256,
            categoryMinimumSequence?.toString(),
            artifactPath
        )
        return captured == expected
    }

    internal suspend fun promoteCategorizedBootstrap(
        bootstrap: FoxCoreDnsRuleSetBootstrap,
        isOwnerCurrent: () -> Boolean,
    ): CategorizedDnsPromotion {
        val variant = bootstrap.categoryVariant ?: return CategorizedDnsPromotion.SUPERSEDED
        val stage = categorizedStore.pendingOrNull(variant) ?: return CategorizedDnsPromotion.SUPERSEDED
        if (!bootstrap.matches(stage)) return CategorizedDnsPromotion.SUPERSEDED
        return categorizedStore.promote(stage, isOwnerCurrent)
    }

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
            deleteDnsFilterCache(File(appContext.filesDir, TARGET_DIR_NAME)) +
                deleteDnsFilterCache(File(appContext.filesDir, "dns-categories")) +
                deleteDnsFilterCache(File(appContext.filesDir, "dns-categories-apk"))
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
        val MAX_SIGNATURE_BYTES = MAX_FOXHOLE_DB_SIGNATURE_BYTES.toInt()

        val FOXCORE_DNS_UPDATE_PUBLIC_KEY_BASE64 = FOXHOLE_DB_MANIFEST_PUBLIC_KEY_DER_BASE64
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
