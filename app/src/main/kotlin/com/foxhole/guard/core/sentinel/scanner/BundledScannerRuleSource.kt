package com.foxhole.guard.core.sentinel.scanner

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class BundledScannerRuleSource(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "sentinel-static-rules")
    private var verified: ScannerYaraSource? = null

    suspend fun current(): ScannerYaraSource = withContext(Dispatchers.IO) {
        val catalogBytes = appContext.assets.open(CATALOG).use(::readBoundedScannerSource)
        val bytes = appContext.assets.open(RULES).use(::readBoundedScannerSource)
        val catalog = validateScannerRuleCatalog(catalogBytes, bytes)
        val hash = catalog.sha256
        val target = File(directory, "$hash.yar")
        val cached = verified
        if (cached != null && cached.sha256 == hash && matchesStoredSource(cached, hash)) return@withContext cached
        check(directory.isDirectory || directory.mkdirs())
        val staged = File.createTempFile("rules-", ".stage", directory)
        try {
            FileOutputStream(staged).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
        ScannerYaraSource(target, hash, bytes.size).also { verified = it }
    }

    private fun matchesStoredSource(source: ScannerYaraSource, hash: String): Boolean =
        source.file.isFile && scannerRuleDigest(source.file.inputStream().use(::readBoundedScannerSource)) == hash

    private companion object {
        const val RULES = "sentinel/static-reference-v1.yar"
        const val CATALOG = "sentinel/static-reference-v1.catalog.json"
    }
}
