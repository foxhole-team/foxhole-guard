package com.foxhole.guard.core.data

import android.content.Context
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.updateAppTrafficStatsEnabled
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class LocalDataRepository(
    context: Context,
    databaseProvider: () -> ProfileDatabase,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val profileSecretStore: ProfileSecretStore,
    private val dnsFilterAssetInstaller: DnsFilterAssetInstaller,
    private val networkActivityClearer: (suspend () -> Unit)? = null,
    private val prepareFactoryReset: (suspend () -> Unit)? = null,
    private val factoryDatabaseClearer: (suspend () -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val database: ProfileDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED, databaseProvider)

    suspend fun clearDiagnostics(): LocalDataClearResult =
        withContext(Dispatchers.IO) {
            LocalDataClearResult(deletedFiles = diagnosticsLogger.clearLocalFiles())
        }

    suspend fun clearNetworkActivity(): LocalDataClearResult =
        withContext(Dispatchers.IO) {
            networkActivityClearer?.invoke() ?: database.anomalyDao().deleteNetworkActivityEvents()
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

    suspend fun clearProfilesAndSecrets(): LocalDataClearResult = withProfileMutation { owner ->
        owner.secrets(settingsRepository.profileMutationCoordinator.secretMutex) {
            settingsRepository.withRestoreSettings(owner) { session ->
                session.update { current ->
                    current.copy(
                        lastActiveProfile = null,
                        profileTrafficTotals = emptyList(),
                        smartProfilePreferences = emptyList(),
                    )
                }
                executeSettledLocalReset(
                    commitSettings = {
                        session.persist()
                        session.publishCommitted()
                        owner.markRestoreCommitted()
                    },
                    clearOwnedData = {
                        database.profileDao().deleteAll()
                        LocalDataClearResult(deletedFiles = profileSecretStore.deleteAll())
                    },
                )
            }
        }
    }

    suspend fun factoryReset(): LocalDataClearResult = withProfileMutation { owner ->
        owner.secrets(settingsRepository.profileMutationCoordinator.secretMutex) {
            settingsRepository.withRestoreSettings(owner) { session ->
                session.update { settingsRepository.storage.defaultSettings() }
                executeSettledLocalReset(
                    commitSettings = {
                        session.persist()
                        session.publishCommitted()
                        owner.markRestoreCommitted()
                    },
                    clearOwnedData = {
                        prepareFactoryReset?.invoke()
                        factoryDatabaseClearer?.invoke() ?: database.clearAllTables()
                        val deleted = profileSecretStore.deleteAll() + diagnosticsLogger.clearLocalFiles() +
                            dnsFilterAssetInstaller.clearLocalCache() + deleteLocalDataTargets(runtimeFactoryResetFileTargets(appContext))
                        LocalDataClearResult(deletedFiles = deleted)
                    },
                )
            }
        }
    }

    private suspend fun <T> withProfileMutation(block: suspend (ProfileMutationLease) -> T): T =
        settingsRepository.profileMutationCoordinator.write(
            onEffectFailure = { error ->
                diagnosticsLogger.recordFailure(
                    "settings",
                    "post-commit effect failed error=${error.javaClass.simpleName}",
                )
            },
            block = block,
        )
}

data class LocalDataClearResult(
    val deletedFiles: Int = 0,
)

internal fun factoryResetFileTargets(context: Context): List<File> =
    buildList {
        add(File(context.filesDir, PROFILE_SECRETS_DIR_NAME))
        add(File(context.filesDir, DIAGNOSTICS_JOURNAL_DIR_NAME))

        add(File(context.filesDir, CLI_TERMINAL_JOURNAL_DIR_NAME))
        add(File(context.cacheDir, DIAGNOSTICS_EXPORT_DIR_NAME))
        add(File(context.filesDir, DNS_RULE_SETS_DIR_NAME))
        add(File(context.filesDir, PROFILE_DB_PASSPHRASE_PATH))

        add(File(context.filesDir, SECURE_DIR_NAME))
        add(File(context.filesDir, GUARD_DIR_NAME))
        addAll(databaseFiles(context, PROFILE_SECURE_DB_NAME))
        addAll(databaseFiles(context, LEGACY_PROFILE_DB_NAME))
    }

internal fun runtimeFactoryResetFileTargets(context: Context): List<File> {
    val activeDatabaseFiles =
        databaseFiles(context, PROFILE_SECURE_DB_NAME)
            .mapTo(mutableSetOf(), File::getAbsolutePath)
    val activePassphraseFile = File(context.filesDir, PROFILE_DB_PASSPHRASE_PATH).absolutePath
    return factoryResetFileTargets(context).filterNot { file ->
        file.absolutePath == activePassphraseFile || file.absolutePath in activeDatabaseFiles
    }
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

private const val CLI_TERMINAL_JOURNAL_DIR_NAME = "cli-terminal-journal"
private const val DIAGNOSTICS_EXPORT_DIR_NAME = "diagnostics-export"
private const val DNS_RULE_SETS_DIR_NAME = "dns-rule-sets"
private const val PROFILE_DB_PASSPHRASE_PATH = "keys/profile-db.passphrase"
private const val SECURE_DIR_NAME = "secure"
private const val GUARD_DIR_NAME = "guard"
private const val PROFILE_SECURE_DB_NAME = "foxhole.secure.db"
private const val LEGACY_PROFILE_DB_NAME = "foxhole.db"

internal suspend fun <T> executeSettledLocalReset(
    commitSettings: suspend () -> Unit,
    clearOwnedData: suspend () -> T,
): T {
    currentCoroutineContext().ensureActive()
    var outcome: Result<T>? = null
    try {
        withContext(NonCancellable + Dispatchers.IO) {
            outcome = try {
                commitSettings()
                Result.success(clearOwnedData())
            } catch (error: Throwable) { Result.failure(error) }
        }
    } catch (handoffFailure: Throwable) {
        val primary = outcome?.exceptionOrNull()
        if (primary != null && primary !== handoffFailure) primary.addSuppressed(handoffFailure)
        outcome = Result.failure(primary ?: handoffFailure)
    }
    val result = checkNotNull(outcome).getOrThrow()
    currentCoroutineContext().ensureActive()
    return result
}
