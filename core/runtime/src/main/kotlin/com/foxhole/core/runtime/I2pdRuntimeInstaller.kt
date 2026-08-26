package com.foxhole.core.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class I2pdRuntimePaths(
    val executablePath: String,
    val dataDirectory: String,
)

internal class I2pdRuntimeUnavailableException(message: String) : IllegalStateException(message)

/** Prepares the packaged i2pd executable, private data, reseed certificates, and runtime paths. */
class I2pdRuntimeInstaller(
    context: Context,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
) {
    private val appContext = context.applicationContext
    private val installMutex = Mutex()

    suspend fun prepare(): I2pdRuntimePaths =
        installMutex.withLock {
            withContext(Dispatchers.IO) {
                val executable =
                    nativeI2pdExecutable()
                        ?: run {
                            recordPreflight("native_i2pd=false")
                            throw I2pdRuntimeUnavailableException("i2pd native executable is missing for this device ABI")
                        }
                if (!executable.isFile || !executable.ensureExecutable()) {
                    recordPreflight("native_i2pd=true", "native_i2pd_executable=false")
                    throw I2pdRuntimeUnavailableException("i2pd executable could not be prepared")
                }
                val dataDirectory = File(appContext.filesDir, DATA_DIR_NAME).apply { mkdirs() }
                val certificatesReady = copyBundledCertificates(dataDirectory)
                recordPreflight(
                    "native_i2pd=true",
                    "native_i2pd_executable=${executable.canExecute()}",
                    "certificates=$certificatesReady",
                    "data_dir_writable=${dataDirectory.canWrite()}",
                )
                I2pdRuntimePaths(
                    executablePath = executable.absolutePath,
                    dataDirectory = dataDirectory.absolutePath,
                )
            }
        }

    private fun nativeI2pdExecutable(): File? =
        File(appContext.applicationInfo.nativeLibraryDir, I2PD_NATIVE_LIBRARY_NAME).takeIf(File::isFile)

    private fun File.ensureExecutable(): Boolean = canExecute() || setExecutable(true, true)

    private fun copyBundledCertificates(dataDirectory: File): Boolean {
        val assetRoot = "$ASSET_DIR_NAME/certificates"
        if (appContext.assets.list(assetRoot).isNullOrEmpty()) {
            return false
        }
        return runCatching {
            copyAssetTree(assetRoot, File(dataDirectory, "certificates"))
            true
        }.getOrDefault(false)
    }

    private fun copyAssetTree(
        assetPath: String,
        target: File,
    ) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun recordPreflight(vararg details: String) {
        diagnosticsLogger?.recordStructured(tag = "i2pd", headline = "i2pd runtime preflight", *details)
    }

    private companion object {
        const val I2PD_NATIVE_LIBRARY_NAME = "libi2pd.so"
        const val DATA_DIR_NAME = "i2pd-data"
        const val ASSET_DIR_NAME = "i2pd"
    }
}
