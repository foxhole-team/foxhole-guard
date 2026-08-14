package com.foxhole.guard.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoxholeDbDownloadProgressContractTest {
    @Test
    fun `every onboarding data client reports bytes before verification`() {
        listOf(
            "DnsFilterUpdateClient.kt",
            "GeoIpUpdateClient.kt",
            "TorBridgeUpdateClient.kt",
            "ThreatIntelUpdateClient.kt",
        ).forEach { name ->
            val source = runtimeSource(name)
            val download = source.indexOf("RemoteUpdatePhase.DOWNLOADING")
            val progress = source.indexOf("onProgress")
            val verifying = source.indexOf("RemoteUpdatePhase.VERIFYING")

            assertTrue("$name missing download phase", download >= 0)
            assertTrue("$name missing byte callback", progress >= 0)
            assertTrue("$name verifies only after download", verifying > download)
        }
    }

    private fun runtimeSource(name: String): String =
        sequenceOf(
            File("src/main/kotlin/com/foxhole/guard/runtime/$name"),
            File("app/src/main/kotlin/com/foxhole/guard/runtime/$name"),
        ).first(File::isFile).readText()
}
