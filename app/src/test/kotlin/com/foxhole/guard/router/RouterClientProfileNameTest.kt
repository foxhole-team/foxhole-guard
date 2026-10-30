package com.foxhole.guard.router

import com.foxhole.guard.router.profile.VpnProfileImporter
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterClientProfileNameTest {
    private val original = "hysteria2://SyntheticOnly@1.1.1.1:443?peer=router.example#Generated"

    @Test fun assignedClientNameSurvivesTheRealPhoneImporter() {
        val assigned = "Дом + телефон #1"
        val named = routerClientProfileForImport(original, assigned)
        assertEquals(assigned, VpnProfileImporter().import(named).profiles.single().name)
    }

    @Test fun renamingOnlyReplacesTheDisplayFragment() {
        val assigned = "Клиент / 東京"
        val named = URI(routerClientProfileForImport(original, assigned))
        val before = URI(original)
        assertEquals(assigned, named.fragment)
        assertEquals(before.scheme, named.scheme)
        assertEquals(before.rawAuthority, named.rawAuthority)
        assertEquals(before.rawPath, named.rawPath)
        assertEquals(before.rawQuery, named.rawQuery)
    }

    @Test fun aProfileWithoutAFragmentReceivesTheAssignedName() {
        assertEquals("Phone", URI(routerClientProfileForImport(original.substringBefore('#'), "Phone")).fragment)
    }

    @Test fun invalidRouterNamesCannotBeInsertedIntoAnImport() {
        listOf("", "   ", "Phone\nOther").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { routerClientProfileForImport(original, name) }
        }
    }
}
