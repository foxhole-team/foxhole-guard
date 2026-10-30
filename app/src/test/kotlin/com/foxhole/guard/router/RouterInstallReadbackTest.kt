package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterInstallReadbackTest {
    private val hash = "a".repeat(64)
    private val selection = RouterInstallProtocolSelection(hash, setOf(RouterInstallProtocol.HYSTERIA2))
    private val binding = RouterInstallBinding(selection, "hy2-x86", "x86_64", RouterStorageMode.FLASH, hash, hash, hash, hash, hash, "x86/64")
    private val system = RouterSystemInspection("25.12.5", RouterPlatform("25.12", "x86/64", "x86_64"), RouterResourceFacts(100, 100))
    private val packages = listOf(RouterPackageIdentity("foxcore-cli", "1.0.0", "foxcore-cli.apk", hash))
    private val receipt = """{"schema":1,"variantId":"hy2-x86","architecture":"x86_64","supportedOpenWrt":["25.12.5"],"supportedTargets":["x86/64"],"protocols":["hysteria2"],"coreBinarySha256":"$hash","capabilitiesSha256":"$hash","coreProducerSha256":"$hash","coreSourceSha256":"$hash","mode":"flash","catalogSha256":"$hash","packages":[{"name":"foxcore-cli","version":"1.0.0","file":"foxcore-cli.apk","sha256":"$hash"}]}"""

    @Test fun exactInstalledPackageAndProtocolBindingPasses() {
        verifyRouterInstalledPackage(receipt.toByteArray(), binding, system, packages)
    }

    @Test fun wrongVariantProducerModeAndFirmwareCannotCompleteInstallation() {
        val original = Json.parseToJsonElement(receipt) as JsonObject
        for ((field, changed) in mapOf("variantId" to "other", "architecture" to "aarch64_generic",
            "mode" to "ram", "catalogSha256" to "b".repeat(64), "coreBinarySha256" to "b".repeat(64),
            "capabilitiesSha256" to "b".repeat(64), "coreProducerSha256" to "b".repeat(64), "coreSourceSha256" to "b".repeat(64))) {
            val bytes = JsonObject(original + (field to JsonPrimitive(changed))).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(bytes, binding, system, packages) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterInstalledPackage(receipt.replace("25.12.5", "25.12.4").toByteArray(), binding, system, packages)
        }
    }

    @Test fun incompleteOrDifferentPackageClosuresCannotReportSuccess() {
        assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(receipt.toByteArray(), binding, system, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(receipt.toByteArray(), binding, system,
            packages.map { it.copy(version = "0.9.0") }) }
        assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(receipt.replace("[\"hysteria2\"]", "[\"vless\"]").toByteArray(), binding, system, packages) }
    }

    @Test fun installedServerProducerMustEchoTheReviewedPlan() {
        val original = Json.parseToJsonElement(receipt) as JsonObject
        val producer = "b".repeat(64)
        val exact = JsonObject(original + ("serverProducerSha256" to JsonPrimitive(producer))).toString().toByteArray()
        verifyRouterInstalledPackage(exact, binding, system, packages, producer)
        assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(exact, binding, system, packages, hash) }
        assertThrows(Exception::class.java) { verifyRouterInstalledPackage(receipt.toByteArray(), binding, system, packages, producer) }
        assertThrows(IllegalArgumentException::class.java) { verifyRouterInstalledPackage(exact, binding, system, packages) }
    }

    @Test fun bootstrapIsTransferredOnlyFromAFreshBoundedEnrollmentAndInputIsWiped() {
        val bytes = """{"schema":1,"status":"fresh","bootstrapPin":"1943","expiresInSeconds":300}""".toByteArray()
        val credential = routerBootstrapCredential(bytes)
        assertArrayEquals("1943".toCharArray(), credential)
        assertArrayEquals(ByteArray(bytes.size), bytes)
        credential?.fill('\u0000')
    }

    @Test fun existingRouterKeepsItsPinAndRequiresNormalPairing() {
        val bytes = """{"schema":1,"status":"already_configured","bootstrapPin":null,"expiresInSeconds":0}""".toByteArray()
        assertNull(routerBootstrapCredential(bytes))
        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    @Test fun malformedOrExpiredBootstrapNeverBecomesACredentialAndIsWiped() {
        for (value in listOf("""{"schema":1,"status":"fresh","bootstrapPin":"1943","expiresInSeconds":0}""",
            """{"schema":1,"status":"fresh","bootstrapPin":"1943","expiresInSeconds":301}""",
            """{"schema":1,"status":"fresh","bootstrapPin":"19ab","expiresInSeconds":300}""",
            """{"schema":1,"status":"already_configured","bootstrapPin":"1943","expiresInSeconds":0}""")) {
            val bytes = value.toByteArray()
            assertThrows(Exception::class.java) { routerBootstrapCredential(bytes) }
            assertEquals(0, bytes.sumOf { it.toInt() })
        }
    }
}
