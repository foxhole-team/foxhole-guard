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
    private fun pair(number: Int) = RouterPairing(RouterTlsIdentity(RouterEndpoint("192.168.1.1"), number.toString(16).padStart(64, '0')),
        "pair-" + number.toString(16).padStart(32, '0'), ByteArray(32) { number.toByte() }, "Phone", true)

    @Test fun renamingOneRouterKeepsSelectionPoliciesAndAllCredentials() = runTest {
        val store = MemoryRouterPairingStore()
        val pairs = (1..3).map(::pair)
        try {
            pairs.forEach { store.save(it) }
            store.selectSaved(pairs[1].id)
            store.save(RouterControlPreferences(pairs[1].id, enabled = true, countryCode = "NL"))
            store.renameSaved(pairs[0].id, "  Living room  ")
            assertEquals(pairs[1].id, store.listSaved().selectedId)
            assertEquals("Living room", store.listSaved().routers.first().label)
            assertTrue(store.read(pairs[1].id).enabled)
            pairs.forEach { expected ->
                val actual = requireNotNull(store.readById(expected.id))
                try { assertArrayEquals(expected.credential, actual.credential) } finally { actual.clear() }
            }
            for (invalid in listOf(" ", "x".repeat(65), "Room\nTwo")) {
                val before = store.listSaved()
                try { store.renameSaved(pairs[0].id, invalid); fail("invalid label accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(before, store.listSaved())
            }
        } finally { store.close(); pairs.forEach(RouterPairing::clear) }
    }

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

    @Test fun eleventhRouterAndChangedKnownCertificateCannotOverwriteTheExistingVault() = runTest {
        val store = MemoryRouterPairingStore()
        val pairs = (1..11).map(::pair)
        try {
            pairs.take(10).forEach { store.save(it) }
            val before = store.listSaved()
            val failure = try { store.save(pairs[10]); null } catch (caught: RouterApiFailure) { caught }
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
    @Test fun legacyElevenRecordsRemainReadableUpdatableAndRemovableWithoutRenumberingOrCredentialLoss() {
        val pairs = (1..12).map(::pair)
        val original = RouterSavedVault(selectedId = pairs[10].id,
            routers = pairs.take(11).mapIndexed { index, pair -> RouterSavedEntry(pair.copy(), index + 1, "Saved" + (index + 1)) })
        var decoded: RouterSavedVault? = null
        var updated: RouterSavedVault? = null
        var reduced: RouterSavedVault? = null
        try {
            decoded = RouterSavedVaultCodec.decode(RouterSavedVaultCodec.encode(original))
            val retained = requireNotNull(decoded)
            assertEquals(11, retained.inventory().routers.size)
            assertEquals(pairs[10].id, retained.selectedId)
            val source = pairs[10]
            val known = RouterPairing(source.identity, source.id, source.credential.copyOf(), "Retained phone",
                source.active, source.pendingServerId, source.pendingSyncSha256)
            try { updated = retained.withPairing(known) } finally { known.clear() }
            val current = requireNotNull(updated)
            assertEquals(11, current.routers.single { it.pairing.id == pairs[10].id }.number)
            assertThrows(RouterApiFailure::class.java) { retained.withPairing(pairs[11]) }
            reduced = current.without(pairs[0].id)
            val remaining = requireNotNull(reduced)
            assertEquals(10, remaining.routers.size)
            assertEquals(pairs[10].id, remaining.selectedId)
            for (row in remaining.routers) {
                val expected = pairs.single { it.id == row.pairing.id }
                assertArrayEquals(expected.credential, row.pairing.credential)
            }
            assertEquals(11, original.routers.size)
        } finally { decoded?.cleared(); updated?.cleared(); reduced?.cleared(); original.cleared(); pairs.forEach(RouterPairing::clear) }
    }

}
