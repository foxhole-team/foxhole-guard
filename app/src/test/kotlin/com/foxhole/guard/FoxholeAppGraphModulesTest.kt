package com.foxhole.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoxholeAppGraphModulesTest {
    @Test
    fun `production import parser validates remote hosts through public dns fallback`() {
        val source =
            listOf(
                File("src/main/kotlin/com/foxhole/guard/FoxholeAppGraphModules.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/FoxholeAppGraphModules.kt"),
                File("../app/src/main/kotlin/com/foxhole/guard/FoxholeAppGraphModules.kt"),
            ).first { file -> file.isFile }.readText()

        // Резолвер под бюджетом: этот экземпляр зовут и вне OkHttp-звонка (санитайзер резолвед-
        // конфига на пути connect), где никакой call timeout его не покрывает — без обёртки
        // системный getaddrinfo вешал команду connect навсегда.
        assertTrue(source.contains("PublicRemoteDns(BoundedSystemHostResolver(core.httpClient.dns::lookup))"))
        assertTrue(source.contains("ProfileImportParser(core.json, remoteHostResolver = publicRemoteDns::lookup)"))
        assertTrue(
            source.contains("DnsFilterUpdateClient(core.httpClient, core.json, resolver = publicRemoteDns::lookup)")
        )
        assertFalse(source.contains("ProfileImportParser(core.json)"))
    }
}
