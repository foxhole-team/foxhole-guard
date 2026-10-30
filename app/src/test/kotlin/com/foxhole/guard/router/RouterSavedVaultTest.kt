package com.foxhole.guard.router

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class RouterSavedVaultTest {
    private fun pair(number: Int) = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), number.toString().repeat(64)),
        "pair-" + number.toString().repeat(32), ByteArray(32) { number.toByte() }, "Phone", true)

    @Test fun legacySingleRecordMigratesWithoutChangingIdentityCredentialsOrRemotePhoneName() {
        val old = pair(1)
        val migrated = RouterSavedVaultCodec.decode(Json.encodeToString(old))
        try {
            assertEquals(old.id, migrated.selectedId)
            assertEquals(old.identity, migrated.routers.single().pairing.identity)
            assertArrayEquals(old.credential, migrated.routers.single().pairing.credential)
            assertEquals("Phone", migrated.routers.single().pairing.name)
            assertNull(migrated.inventory().routers.single().label)
            assertEquals(1, migrated.inventory().routers.single().number)
            assertEquals(2, RouterSavedVaultCodec.decode(RouterSavedVaultCodec.encode(migrated)).schema)
        } finally { migrated.cleared(); old.clear() }
    }

    @Test fun threeRoutersKeepIndependentPreferencesAndTargetedRemovalRetainsTheOtherKeys() = runTest {
        val store = MemoryRouterPairingStore()
        val pairs = (1..3).map(::pair)
        try {
            pairs.forEach { store.save(it) }
            store.save(RouterControlPreferences(pairs[0].id, enabled = true, countryCode = "NL", publicIpConfirmed = true))
            store.selectSaved(pairs[1].id)
            store.remove(pairs[1].id)
            assertEquals(listOf(pairs[0].id, pairs[2].id), store.listSaved().routers.map { it.id })
            assertEquals(pairs[0].id, store.listSaved().selectedId)
            assertTrue(store.read(pairs[0].id).publicIpConfirmed)
            assertFalse(store.read(pairs[2].id).publicIpConfirmed)
            assertNull(store.readById(pairs[1].id))
            pairs.filterIndexed { index, _ -> index != 1 }.forEach { expected ->
                val actual = requireNotNull(store.readById(expected.id))
                try { assertArrayEquals(expected.credential, actual.credential) } finally { actual.clear() }
            }
            store.selectSaved(pairs[2].id)
            val selected = requireNotNull(store.read())
            try { assertEquals(pairs[2].id, selected.id) } finally { selected.clear() }
        } finally { store.close(); pairs.forEach(RouterPairing::clear) }
    }

    @Test fun fourthRouterAndChangedKnownCertificateCannotOverwriteTheExistingVault() = runTest {
        val store = MemoryRouterPairingStore()
        val pairs = (1..4).map(::pair)
        try {
            pairs.take(3).forEach { store.save(it) }
            val before = store.listSaved()
            val failure = try { store.save(pairs[3]); null } catch (caught: RouterApiFailure) { caught }
            assertEquals(RouterApiError.LIMIT, failure?.kind)
            val changed = RouterPairing(pairs[0].identity.copy(certificateSha256 = "a".repeat(64)), pairs[0].id,
                ByteArray(32) { 9 }, "Other phone")
            try {
                try { store.save(changed); fail("changed pin accepted") } catch (_: RouterIdentityChanged) { }
            } finally { changed.clear() }
            assertEquals(before, store.listSaved())
            val retained = requireNotNull(store.readById(pairs[0].id))
            try { assertArrayEquals(pairs[0].credential, retained.credential) } finally { retained.clear() }
        } finally { store.close(); pairs.forEach(RouterPairing::clear) }
    }

    @Test fun corruptSelectionFailsInsteadOfResettingSavedCredentials() {
        val first = pair(1)
        try {
            val original = RouterSavedVault(selectedId = first.id, routers = listOf(RouterSavedEntry(first.copy(), 1)))
            val text = RouterSavedVaultCodec.encode(original)
            original.cleared()
            assertThrows(IllegalArgumentException::class.java) {
                RouterSavedVaultCodec.decode(text.replace("\"selectedId\":\"${first.id}\"", "\"selectedId\":\"pair-${"9".repeat(32)}\""))
            }
        } finally { first.clear() }
    }
}
