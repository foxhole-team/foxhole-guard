package com.foxhole.guard.router

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class RouterSavedVaultAndroidTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var preferencesName: String
    private lateinit var alias: String
    private lateinit var aad: ByteArray
    private val file get() = File(root, "vpn-router-pairing.v1")

    @Before fun isolate() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.foxhole.guard.debug", target.packageName)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        root = File(target.noBackupFilesDir, "router-vault-qa-$nonce")
        assertTrue(root.mkdir())
        preferencesName = "router-vault-qa-$nonce"
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getPackageName(): String = "${target.packageName}.qa.$nonce"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$preferencesName-$name", mode)
        }
        alias = "${context.packageName}.router-pairing.v1"
        aad = "$alias:1".toByteArray()
    }

    @After fun removeOwnedFixtures() {
        if (::alias.isInitialized) KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        if (::root.isInitialized) {
            assertTrue(root.name.startsWith("router-vault-qa-"))
            root.deleteRecursively()
        }
        if (::preferencesName.isInitialized) InstrumentationRegistry.getInstrumentation().targetContext
            .deleteSharedPreferences("$preferencesName-vpn-router-controls")
    }

    private fun pair(index: Int) = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), index.toString().repeat(64)),
        "pair-${index.toString().repeat(32)}", ByteArray(32) { index.toByte() }, "Synthetic phone", true)
    private fun key(): SecretKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build())
    }.generateKey()
    private fun encrypt(raw: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(raw)
        return ByteBuffer.allocate(17 + encrypted.size).putInt(0x46525031).put(12.toByte()).put(cipher.iv).put(encrypted).array()
    }
    private fun decrypt(raw: ByteArray, key: SecretKey): ByteArray {
        val buffer = ByteBuffer.wrap(raw)
        assertEquals(0x46525031, buffer.int); assertEquals(12, buffer.get().toInt())
        val nonce = ByteArray(12).also(buffer::get)
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
        return cipher.doFinal(bytes)
    }

    @Test fun legacyCiphertextAndPreferencesMigrateUsingTheSameNonExportableKey() = runBlocking {
        val original = pair(1)
        try {
            val plaintext = Json.encodeToString(original).toByteArray()
            val legacy = encrypt(plaintext, createKey())
            file.writeBytes(legacy)
            context.getSharedPreferences("vpn-router-controls", Context.MODE_PRIVATE).edit()
                .putString("pairing", original.id).putBoolean("enabled", true).putString("country", "NL")
                .putBoolean("public-ip-confirmed", true).putBoolean("country-auto", false).commit()
            val store = EncryptedRouterPairingStore(context)
            val migrated = requireNotNull(store.read())
            try { assertEquals(original.id, migrated.id); assertArrayEquals(original.credential, migrated.credential) }
            finally { migrated.clear() }
            val preferences = LocalRouterControlPreferenceStore(context, pairings = store).read(original.id)
            assertTrue(preferences.enabled); assertTrue(preferences.publicIpConfirmed); assertEquals("NL", preferences.countryCode)
            assertArrayEquals(plaintext, decrypt(legacy, key()))
            val reopened = EncryptedRouterPairingStore(context)
            assertEquals(preferences, reopened.readPreferences(original.id))
            assertEquals(original.id, reopened.listSaved().selectedId)
            plaintext.fill(0)
        } finally { original.clear() }
    }

    @Test fun threeEntriesAndSelectedPreferencesSurviveReopenAndTargetedDelete() = runBlocking {
        val store = EncryptedRouterPairingStore(context)
        val pairs = (1..3).map(::pair)
        try {
            pairs.forEach { store.save(it) }
            store.savePreferences(RouterControlPreferences(pairs[2].id, countryCode = "DE", countryAuto = false, publicIpConfirmed = true))
            store.selectSaved(pairs[1].id)
            val reopened = EncryptedRouterPairingStore(context)
            assertEquals(pairs[1].id, reopened.listSaved().selectedId)
            reopened.remove(pairs[1].id)
            val final = EncryptedRouterPairingStore(context)
            assertEquals(listOf(pairs[0].id, pairs[2].id), final.listSaved().routers.map { it.id })
            assertEquals(pairs[0].id, final.listSaved().selectedId)
            assertEquals("DE", final.readPreferences(pairs[2].id)?.countryCode)
            val retained = requireNotNull(final.readById(pairs[2].id))
            try { assertArrayEquals(pairs[2].credential, retained.credential) } finally { retained.clear() }
        } finally { pairs.forEach(RouterPairing::clear) }
    }

    @Test fun rejectingTheFourthEntryLeavesTheOriginalEncryptedBytesUntouched() = runBlocking {
        val store = EncryptedRouterPairingStore(context)
        val pairs = (1..4).map(::pair)
        try {
            pairs.take(3).forEach { store.save(it) }
            val before = file.readBytes()
            try { store.save(pairs[3]); fail("fourth router accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.LIMIT, failure.kind) }
            assertArrayEquals(before, file.readBytes())
        } finally { pairs.forEach(RouterPairing::clear) }
    }

    @Test fun tamperedCiphertextAndWrongOwnFixtureKeyFailWithoutResettingTheFile() = runBlocking {
        val original = pair(1)
        try {
            EncryptedRouterPairingStore(context).save(original)
            val before = file.readBytes()
            val tampered = before.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertThrows(Exception::class.java) { runBlocking { EncryptedRouterPairingStore(context).read() } }
            assertArrayEquals(tampered, file.readBytes())
            file.writeBytes(before)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            createKey()
            assertThrows(Exception::class.java) { runBlocking { EncryptedRouterPairingStore(context).read() } }
            assertArrayEquals(before, file.readBytes())
        } finally { original.clear() }
    }
}
