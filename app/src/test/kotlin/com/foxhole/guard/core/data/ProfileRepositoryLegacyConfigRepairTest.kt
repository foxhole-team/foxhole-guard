package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.Settings
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileRepositoryLegacyConfigRepairTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val parser = ProfileImportParser(json, remoteHostResolver = testRemoteHostResolver())

    @Test
    fun `repairs legacy raw share uri stored as resolved config`() {
        val repaired =
            parser.normalizeLegacyRawResolvedConfig(
                raw = "hysteria2://secret-direct@hy2-direct.example.com:8447/#Foxhole%20vpn%20direct",
                settings = Settings(),
                allowInsecureTls = false,
            )

        assertNotNull(repaired)
        val root = json.parseToJsonElement(requireNotNull(repaired)).jsonObject
        val outbound = root["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("hy2-direct.example.com", outbound["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `does not rewrite already normalized json config`() {
        val raw = """{"outbounds":[{"type":"direct","tag":"direct"}]}"""

        val repaired =
            parser.normalizeLegacyRawResolvedConfig(
                raw = raw,
                settings = Settings(),
                allowInsecureTls = false,
            )

        assertEquals(null, repaired)
    }
}
