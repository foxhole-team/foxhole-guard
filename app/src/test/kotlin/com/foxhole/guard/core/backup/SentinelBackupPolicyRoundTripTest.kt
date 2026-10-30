package com.foxhole.guard.core.backup

import com.foxhole.core.model.ApkScanCost
import com.foxhole.core.model.ApkScanSettings
import com.foxhole.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.core.model.Settings
import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.GuardKdfParams
import com.foxhole.guard.core.security.GuardKeypair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class SentinelBackupPolicyRoundTripTest {
    @Test fun bothPortableModesRestoreExpertPolicyAndKeepCurrentDeviceCredentials() {
        val policy = ApkScanSettings(
            dexEnabled = true,
            yaraEnabled = true,
            notifyFindings = true,
            automaticNewUpdatedApps = true,
            chargingOnly = false,
            batteryNotLow = true,
            cost = ApkScanCost.BALANCED,
        )
        val base = Settings()
        val exported = base.copy(anomaly = base.anomaly.copy(apkScan = policy))
        val current = base.copy(updateSources = base.updateSources.copy(appReleasesToken = "owned-current-device"))
        val document = BackupDocument(settingsSchemaVersion = SETTINGS_SCHEMA_VERSION, settings = exported)
        for (encrypted in listOf(false, true)) {
            val operation = PendingBackupExport.create(
                BackupExportSelection(includeProfiles = false, includeSettings = true, encrypted = encrypted),
                if (encrypted) "owned password".toCharArray() else null,
            ).transfer()
            try {
                val payload = operation.encode(document, FixtureKdf())
                val decoded = if (encrypted) {
                    decryptBackupDocument(payload, "owned password".toCharArray(), FixtureKdf())
                } else {
                    decodeBackupDocument(payload)
                }
                val restored = requireNotNull((decoded as BackupParseResult.Success).document.settings)
                val merged = mergeRestoredSettings(restored, current)
                assertEquals(policy, merged.anomaly.apkScan)
                assertEquals("owned-current-device", merged.updateSources.appReleasesToken)
            } finally {
                operation.close()
            }
        }
    }

    @Test fun legacyBackupWithoutStaticPolicyRestoresSafeNewDefaults() {
        val document = BackupDocument(settingsSchemaVersion = SETTINGS_SCHEMA_VERSION, settings = Settings())
        val root = Json.parseToJsonElement(encodeBackupDocument(document)).jsonObject
        val settings = requireNotNull(root["settings"]).jsonObject
        val anomaly = requireNotNull(settings["anomaly"]).jsonObject
        val legacySettings = JsonObject(settings + ("anomaly" to JsonObject(anomaly - "apkScan")))
        val payload = JsonObject(root + ("settings" to legacySettings)).toString()
        val restored = (decodeBackupDocument(payload) as BackupParseResult.Success).document.settings
        assertEquals(ApkScanSettings(), requireNotNull(restored).anomaly.apkScan)
    }
}

private class FixtureKdf : GuardCrypto {
    override fun argon2id(password: ByteArray, salt: ByteArray, params: GuardKdfParams, outputLength: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(password + salt)
        return ByteArray(outputLength) { digest[it % digest.size] }
    }
    override fun sealedBoxKeypair(): GuardKeypair = error("Not used")
    override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray = error("Not used")
    override fun openSealed(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray = error("Not used")
}
