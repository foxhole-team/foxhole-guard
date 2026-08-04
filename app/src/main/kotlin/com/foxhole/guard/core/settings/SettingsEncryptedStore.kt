package com.foxhole.guard.core.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.UiSettings
import com.foxhole.core.model.migrateStoredProfileSourceToken
import com.foxhole.core.model.migrateStoredProtocolToken
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.readBytesMigratingLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

internal class SettingsEncryptedStore(
    context: Context,
    private val defaultSettings: () -> Settings,
) {
    private val appContext = context.applicationContext
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            // Unknown stored enum values fall back to the field default instead of poisoning the
            // whole settings file (guards every enum against values from newer/older builds).
            coerceInputValues = true
        }
    private val fileCipher = AndroidKeystoreFileCipher("foxhole.settings")
    private val settingsDir = File(appContext.filesDir, "settings").apply { mkdirs() }
    private val settingsFile = File(settingsDir, "settings.json")
    private val legacySettingsFile = appContext.preferencesDataStoreFile("foxhole_settings.preferences_pb")

    suspend fun loadInitialSettings(): Settings =
        withContext(Dispatchers.IO) {
            when (val encryptedResult = readEncryptedResult()) {
                EncryptedSettingsLoadResult.Missing -> {
                    val migrated = (readLegacySettingsOrNull() ?: defaultSettings()).normalized()
                    write(migrated)
                    deleteLegacySettings()
                    migrated
                }

                is EncryptedSettingsLoadResult.Loaded ->
                    // readEncryptedResult already rewrote the file if the on-disk bytes were not
                    // canonical, so a normalized file needs no write here.
                    encryptedResult.settings

                is EncryptedSettingsLoadResult.Corrupt -> {
                    throw SettingsCorruptedException(
                        preservedCopy = encryptedResult.preservedCopy,
                        cause = encryptedResult.cause,
                    )
                }
            }
        }

    // The single source of truth for what a settings file's plaintext looks like: the read path
    // compares against this to decide whether a rewrite is even needed.
    private fun encodeCanonical(value: Settings): String =
        json.encodeToString(Settings.serializer(), value.normalized())

    fun write(value: Settings) {
        fileCipher.writeBytesAtomic(settingsFile, encodeCanonical(value).encodeToByteArray())
    }

    fun readStoredUiOrDefault(defaults: UiSettings): UiSettings =
        runCatching {
            when (val result = readEncryptedResult()) {
                is EncryptedSettingsLoadResult.Loaded -> result.settings.ui
                EncryptedSettingsLoadResult.Missing,
                is EncryptedSettingsLoadResult.Corrupt,
                -> defaults
            }
        }.getOrDefault(defaults)

    private fun readEncryptedResult(): EncryptedSettingsLoadResult =
        readEncryptedSettingsResult(
            settingsFile = settingsFile,
            readPayload = {
                fileCipher.readBytesMigratingLegacy(appContext, settingsFile).decodeToString()
            },
            decodePayload = ::readSettingsPayload,
            sanitizePayload = ::sanitizeStoredThemeModePayload,
            encodeCanonical = ::encodeCanonical,
            rewriteCanonical = ::write,
            preserveCorruptFile = ::preserveCorruptSettingsFile,
        )

    private fun preserveCorruptSettingsFile(): File? =
        settingsFile
            .takeIf(File::exists)
            ?.let { source ->
                val forensicCopy =
                    File(
                        settingsDir,
                        "${source.name}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
                    )
                source.copyTo(forensicCopy, overwrite = false)
                forensicCopy
            }

    private fun readSettingsPayload(payload: String): Settings =
        runCatching { json.decodeFromString(Settings.serializer(), migratedPayload(payload)).normalized() }
            .recoverCatching { json.decodeFromString(LegacyFlatSettings.serializer(), payload).toCurrent() }
            .getOrThrow()

    // Folds legacy per-app lists into the lane model before decoding (ignoreUnknownKeys would
    // otherwise drop them). No-op once the store already carries `appAssignments`.
    private fun migratedPayload(payload: String): String {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return payload
        var migratedRoot = migrateLegacyCachedProfileTokens(root)
        val expert = migratedRoot["expert"] as? JsonObject
        if (expert != null) {
            val migratedExpert =
                migrateLegacyAppAssignments(
                    expert,
                    migratedRoot["privacyRoute"] as? JsonObject,
                )
            if (migratedExpert !== expert) {
                migratedRoot =
                    buildJsonObject {
                        migratedRoot.forEach { (key, value) ->
                            put(key, if (key == "expert") migratedExpert else value)
                        }
                    }
            }
        }
        return if (migratedRoot === root) {
            payload
        } else {
            json.encodeToString(JsonObject.serializer(), migratedRoot)
        }
    }

    private fun migrateLegacyCachedProfileTokens(root: JsonObject): JsonObject {
        val cached = root["lastActiveProfile"] as? JsonObject ?: return root
        val source = (cached["sourceType"] as? JsonPrimitive)?.contentOrNull
        val protocol = (cached["protocolHint"] as? JsonPrimitive)?.contentOrNull
        val migratedSource = source?.let(::migrateStoredProfileSourceToken)
        val migratedProtocol = protocol?.let(::migrateStoredProtocolToken)
        if (source == migratedSource && protocol == migratedProtocol) {
            return root
        }
        val migratedCached =
            buildJsonObject {
                cached.forEach { (key, value) ->
                    when (key) {
                        "sourceType" -> put(key, migratedSource?.let(::JsonPrimitive) ?: value)
                        "protocolHint" -> put(key, migratedProtocol?.let(::JsonPrimitive) ?: value)
                        else -> put(key, value)
                    }
                }
            }
        return buildJsonObject {
            root.forEach { (key, value) ->
                put(key, if (key == "lastActiveProfile") migratedCached else value)
            }
        }
    }

    private suspend fun readLegacySettingsOrNull(): Settings? =
        if (!legacySettingsFile.exists()) {
            null
        } else {
            val legacyStore =
                PreferenceDataStoreFactory.create(
                    produceFile = { legacySettingsFile },
                )
            legacyStore.data.firstOrNull()?.let { preferences ->
                Settings(
                    ui =
                    UiSettings(
                        themeMode = parseStoredThemeMode(preferences[LegacySettingsKeys.themeMode]),
                        locale = AppLocale.valueOf(preferences[LegacySettingsKeys.locale] ?: AppLocale.SYSTEM.name),
                        showExpertSettings = true,
                    ),
                    connection =
                    ConnectionSettings(
                        autoReconnect = preferences[LegacySettingsKeys.autoReconnect] ?: true,
                        autoStartOnBoot = preferences[LegacySettingsKeys.autoStartOnBoot] ?: false,
                        ipInfoEndpoint =
                        normalizeIpInfoEndpoint(
                            preferences[LegacySettingsKeys.ipInfoEndpoint].orEmpty(),
                        ),
                    ),
                ).normalized()
            }
        }

    private fun deleteLegacySettings() {
        legacySettingsFile.delete()
        File("${legacySettingsFile.absolutePath}.crc").delete()
    }
}
