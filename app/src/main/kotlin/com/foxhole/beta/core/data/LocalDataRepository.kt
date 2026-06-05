package com.foxhole.beta.core.data

import android.content.Context
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.vpn.DnsFilterAssetInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalDataRepository(
    context: Context,
    databaseProvider: () -> ProfileDatabase,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val profileSecretStore: ProfileSecretStore,
    private val dnsFilterAssetInstaller: DnsFilterAssetInstaller,
) {
    private val appContext = context.applicationContext
    private val database: ProfileDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED, databaseProvider)

    suspend fun clearDiagnostics(): LocalDataClearResult =
        withContext(Dispatchers.IO) {
            LocalDataClearResult(deletedFiles = diagnosticsLogger.clearLocalFiles())
        }

    suspend fun clearNetworkActivity(): LocalDataClearResult =
        withContext(Dispatchers.IO) {
            database.anomalyDao().deleteNetworkActivityEvents()
            LocalDataClearResult()
        }

    suspend fun clearAppTrafficStats(): LocalDataClearResult {
        settingsRepository.updateAppTrafficStatsEnabled(false)
        return withContext(Dispatchers.IO) {
            val dao = database.anomalyDao()
            dao.deleteAppTrafficWindowsBefore(Long.MAX_VALUE)
            dao.deleteAppBaselines()
            dao.deleteAppAnomalyEvents()
            LocalDataClearResult()
        }
    }

    suspend fun clearProfilesAndSecrets(): LocalDataClearResult {
        settingsRepository.clearProfileLocalData()
        val deletedSecrets =
            withContext(Dispatchers.IO) {
                database.profileDao().deleteAll()
                profileSecretStore.deleteAll()
            }
        return LocalDataClearResult(deletedFiles = deletedSecrets)
    }

    suspend fun factoryReset(): LocalDataClearResult {
        settingsRepository.resetAllLocalSettings()
        return withContext(Dispatchers.IO) {
            database.clearAllTables()
            val deletedFiles =
                profileSecretStore.deleteAll() +
                    diagnosticsLogger.clearLocalFiles() +
                    dnsFilterAssetInstaller.clearLocalCache() +
                    deleteLocalDataTargets(factoryResetFileTargets(appContext))
            LocalDataClearResult(deletedFiles = deletedFiles)
        }
    }
}

data class LocalDataClearResult(
    val deletedFiles: Int = 0,
)

internal fun factoryResetFileTargets(context: Context): List<File> =
    buildList {
        add(File(context.filesDir, PROFILE_SECRETS_DIR_NAME))
        add(File(context.filesDir, DIAGNOSTICS_JOURNAL_DIR_NAME))
        add(File(context.cacheDir, DIAGNOSTICS_EXPORT_DIR_NAME))
        add(File(context.filesDir, DNS_RULE_SETS_DIR_NAME))
        add(File(context.filesDir, PROFILE_DB_PASSPHRASE_PATH))
        addAll(databaseFiles(context, PROFILE_SECURE_DB_NAME))
        addAll(databaseFiles(context, LEGACY_PROFILE_DB_NAME))
    }

internal fun databaseFiles(
    context: Context,
    name: String,
): List<File> =
    databaseFiles(context.getDatabasePath(name))

internal fun databaseFiles(database: File): List<File> =
    listOf(
        database,
        File("${database.absolutePath}-wal"),
        File("${database.absolutePath}-shm"),
        File("${database.absolutePath}-journal"),
    )

internal fun deleteLocalDataTargets(targets: List<File>): Int =
    targets.distinctBy(File::getAbsolutePath).sumOf(::deleteLocalDataTarget)

internal fun deleteLocalDataTarget(target: File): Int =
    when {
        !target.exists() -> 0
        target.isFile -> if (target.delete()) 1 else 0
        else -> target.walkBottomUp().count { file -> file.exists() && file.delete() }
    }

private const val PROFILE_SECRETS_DIR_NAME = "profile-secrets"
private const val DIAGNOSTICS_JOURNAL_DIR_NAME = "diagnostics-journal"
private const val DIAGNOSTICS_EXPORT_DIR_NAME = "diagnostics-export"
private const val DNS_RULE_SETS_DIR_NAME = "dns-rule-sets"
private const val PROFILE_DB_PASSPHRASE_PATH = "keys/profile-db.passphrase"
private const val PROFILE_SECURE_DB_NAME = "foxhole.secure.db"
private const val LEGACY_PROFILE_DB_NAME = "foxhole.db"
