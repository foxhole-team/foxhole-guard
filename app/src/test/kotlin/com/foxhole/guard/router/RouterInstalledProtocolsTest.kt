package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterInstalledProtocolsTest {
    private fun parse(text: String) = parseRouterInstalledProtocols(Json.parseToJsonElement(text))
    private fun entry(protocol: RouterInstallProtocol, installed: Boolean) = RouterEngineEntry(protocol,
        RouterEngineAvailability.AVAILABLE, installed, null, null, null, false, false, null, null)

    @Test fun legacyAbsenceOrNullIsUnknownAndActualEmptyIsKnownEmpty() {
        assertNull(parseRouterInstalledProtocols(null)); assertNull(parseRouterInstalledProtocols(JsonNull))
        assertEquals(emptySet<RouterInstallProtocol>(), parse("""{"schema":1,"protocols":[]}"""))
        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), parse("""{"schema":1,"protocols":["hysteria2"]}"""))
    }

    @Test fun malformedOrCompiledUnknownNamesNeverBecomeInstalledFacts() {
        for (text in listOf("{}", "false", """{"schema":"1","protocols":[]}""",
            """{"schema":1,"protocols":["VLESS"]}""", """{"schema":1,"protocols":["unknown"]}""",
            """{"schema":1,"protocols":["vless","vless"]}""", """{"schema":1,"protocols":[true]}""",
            """{"schema":1,"protocols":[],"supported":true}""")) {
            try { parse(text); throw AssertionError("Malformed installed protocol fact accepted") }
            catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
        }
    }

    @Test fun availableButUninstalledCatalogEntriesAreIgnoredAndOnlyVerifiedInstalledIdsMerge() {
        val entries = listOf(entry(RouterInstallProtocol.VLESS, false), entry(RouterInstallProtocol.HYSTERIA2, true))
        assertEquals(listOf(RouterInstallProtocol.HYSTERIA2, RouterInstallProtocol.HTTP),
            routerInstalledProtocols(setOf(RouterInstallProtocol.HTTP), entries))
        assertEquals(listOf(RouterInstallProtocol.HYSTERIA2), routerInstalledProtocols(null, entries))
        assertNull(routerInstalledProtocols(null, listOf(entry(RouterInstallProtocol.VLESS, false))))
        assertTrue(requireNotNull(routerInstalledProtocols(emptySet(), emptyList())).isEmpty())
    }
}
