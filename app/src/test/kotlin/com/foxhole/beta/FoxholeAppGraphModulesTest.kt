package com.foxhole.beta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoxholeAppGraphModulesTest {
    @Test
    fun `production import parser validates remote hosts through public dns fallback`() {
        val source =
            listOf(
                File("src/main/kotlin/com/foxhole/beta/FoxholeAppGraphModules.kt"),
                File("app/src/main/kotlin/com/foxhole/beta/FoxholeAppGraphModules.kt"),
                File("../app/src/main/kotlin/com/foxhole/beta/FoxholeAppGraphModules.kt"),
            ).first { file -> file.isFile }.readText()

        assertTrue(source.contains("PublicRemoteDns(core.httpClient.dns::lookup)"))
        assertTrue(source.contains("ProfileImportParser(core.json, remoteHostResolver = publicRemoteDns::lookup)"))
        assertTrue(source.contains("DnsFilterUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)"))
        assertFalse(source.contains("ProfileImportParser(core.json)"))
    }
}
