package com.foxhole.guard.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCompactBootstrapReleaseTest {
    @Test fun clientOnlyReleaseDoesNotReadGoServerAndKeepsExactIndependentModules() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val payload = fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD))
        assertNull(payload.variant)
        assertFalse("server-producer.json" in fixture.requested)
        for (mode in RouterStorageMode.entries) {
            val plan = requireNotNull(payload.compact).modes.getValue(mode)
            assertEquals(setOf("vless", "wireguard"), plan.binding.modules.map { it.protocol.canonical }.toSet())
            assertNull(plan.binding.server)
            assertEquals(4, plan.packages.size) // panel, two selected slots and the actual kernel
            assertTrue(plan.packages.none { it.name in setOf("foxcore-cli", "foxcore-cli-ram", "hysteria", "hysteria-server-ram") || it.name.startsWith("foxcore-engine-hysteria2-") })
            assertEquals(50331648L + 1048576L, plan.document.compactLong("runtimeMemoryBytes"))
            assertEquals(plan.document.compactLong("requiredRamBytes") + 4096L, payload.budgets.getValue(mode).ramWorkingMemoryBytes)
        }
    }

    @Test fun explicitServerIsASeparateAuthenticatedRoleAndAddsItsWholeRamBudget() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val client = fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS))
        val remote = fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS, server = true))
        assertTrue("server-producer.json" in fixture.requested)
        val without = requireNotNull(client.compact).modes.getValue(RouterStorageMode.RAM)
        val with = requireNotNull(remote.compact).modes.getValue(RouterStorageMode.RAM)
        assertEquals(16777216L, with.document.compactLong("runtimeMemoryBytes") - without.document.compactLong("runtimeMemoryBytes"))
        assertEquals(16384L, with.document.compactLong("installedRamBytes") - without.document.compactLong("installedRamBytes"))
        assertEquals(1, with.packages.count { it.name == "hysteria-server-ram" })
        assertTrue(with.binding.server != null)
        assertEquals(1024L * 2 + 12288 + 16384 + 16777216, with.document.compactLong("requiredRamBytes") - without.document.compactLong("requiredRamBytes"))
    }

    @Test fun noDefaultHysteriaStillAllowsAnExplicitReleasedVlessSelection() = runTest {
        val fixture = RouterCompactBootstrapFixtures(ids = listOf("vless"), withServer = false)
        fixture.release.initialize()
        assertNull(fixture.release.catalog?.defaultSelection)
        val payload = fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS))
        assertEquals(setOf(RouterStorageMode.FLASH, RouterStorageMode.RAM), payload.budgets.keys)
        assertFalse(requireNotNull(fixture.release.catalog).accepts(setOf(RouterInstallProtocol.VLESS), true))
    }

    @Test fun missingKernelWrongCanonicalKeyAndMisclassifiedOwnIndexFailBeforeMutation() = runTest {
        val mutations: List<(JsonObject) -> JsonObject> = listOf(
            { root -> root.compactChanged("packages", JsonArray(root.compactObjects("packages", 1..512).map { pkg ->
                if (pkg.compactText("name") == "kernel") pkg.compactChanged("version", JsonPrimitive("different-kernel")) else pkg
            })) },
            { root -> root.compactChanged("trustKeys", JsonArray(root.compactObjects("trustKeys", 1..32).map { key ->
                if (key.compactText("file") == "foxhole-openwrt-apk.pem") key.compactChanged("sha256", JsonPrimitive("0".repeat(64))) else key
            })) },
            { root -> root.compactChanged("repositoryIndexes", JsonArray(root.compactObjects("repositoryIndexes", 1..16).map { index ->
                if (index.compactText("role") == "foxhole") index.compactChanged("role", JsonPrimitive("upstream")) else index
            })) },
        )
        for (mutation in mutations) {
            val fixture = RouterCompactBootstrapFixtures(transformUniverse = mutation)
            fixture.release.initialize()
            expectFailure { fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS)) }
        }
    }

    @Test fun aDependencyCannotSmuggleAnUnselectedEngineOrServerIntoTheClosure() = runTest {
        for (hidden in listOf("hysteria", "hysteria2")) {
            val fixture = RouterCompactBootstrapFixtures(transformUniverse = { root ->
                val unwanted = if (hidden == "hysteria") "hysteria" else root.compactObjects("packages", 1..512)
                    .single { it.compactText("name").startsWith("foxcore-engine-hysteria2-flash-") }.compactText("name")
                root.compactChanged("requires", root.compactObject("requires").compactChanged("foxhole-openwrt-client", compactArray(listOf(unwanted))))
            })
            fixture.release.initialize()
            expectFailure { fixture.release.payload(fixture.system, fixture.selection(RouterInstallProtocol.VLESS)) }
        }
    }

    @Test fun crossProtocolCapabilitySlotAndServerPatchBindingsAreRequired() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        val platform = fixture.catalog.compactPlatforms.single()
        val client = platform.clients.first { it.protocol == RouterInstallProtocol.VLESS }
        fun doc(asset: RouterInstallAsset) = routerCompactDocument(fixture.documents.getValue(asset.file), 131072)
        val producer = doc(client.producer); val caps = doc(client.capabilities); val slot = doc(client.slotManifest)
        verifyRouterCompactProducer(client, producer, caps, slot, "x86_64")
        for ((badProducer, badCaps, badSlot) in listOf(
            Triple(producer.compactChanged("target", JsonPrimitive("aarch64-unknown-linux-musl")), caps, slot),
            Triple(producer, caps.compactChanged("protocols", compactArray(listOf("hysteria2"))), slot),
            Triple(producer, caps, slot.compactChanged("legalProvenanceSha256", JsonPrimitive("f".repeat(64)))),
        )) assertThrows(RuntimeException::class.java) { verifyRouterCompactProducer(client, badProducer, badCaps, badSlot, "x86_64") }
        val server = doc(requireNotNull(platform.server).producer)
        assertThrows(RuntimeException::class.java) {
            verifyRouterCompactServer(platform, fixture.universe, RouterStorageMode.FLASH,
                server.compactChanged("sourcePatch", server.compactObject("sourcePatch").compactChanged("receiptSha256", JsonPrimitive("0".repeat(64)))), fixture.manifest)
        }
    }

    @Test fun installedReadbackMustMatchAllChosenSlotsServerAndPackagesExactly() = runTest {
        val fixture = RouterCompactBootstrapFixtures()
        fixture.release.initialize()
        val selection = fixture.selection(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD, server = true)
        val plan = requireNotNull(fixture.release.payload(fixture.system, selection).compact).modes.getValue(RouterStorageMode.FLASH)
        val binding = RouterInstallBinding(selection, plan.binding.platform.id, "x86_64", RouterStorageMode.FLASH,
            null, null, null, null, plan.binding.budgetSha256, "x86/64", plan.binding)
        assertTrue(fixture.catalog.matches(binding, "25.12.5", fixture.manifestSha256))
        val bytes = fixture.readback(binding, plan.packages)
        verifyRouterInstalledPackage(bytes, binding, fixture.system, plan.packages)
        val actual = routerCompactDocument(bytes, 524288)
        val tampered = listOf(
            actual.compactChanged("includeServer", JsonPrimitive(false)), actual.compactChanged("server", JsonNull),
            actual.compactChanged("modules", JsonArray(actual.compactObjects("modules", 1..12).take(1))),
            actual.compactChanged("packages", JsonArray(actual.compactObjects("packages", 1..512).drop(1))),
            actual.compactChanged("engineCatalogSha256", JsonPrimitive("f".repeat(64))),
        )
        for (value in tampered) assertThrows(RuntimeException::class.java) {
            verifyRouterInstalledPackage(value.toString().toByteArray(), binding, fixture.system, plan.packages)
        }
    }

    @Test fun confirmedStorageIsExplicitAndServerFlagIsOnlyOptIn() {
        val protocols = setOf(RouterInstallProtocol.VLESS, RouterInstallProtocol.WIREGUARD)
        val ordinary = RouterInstallCommand(RouterCompactBootstrapFixtures.BASE, "a".repeat(64), RouterStorageMode.RAM, protocols, licenseAccepted = true).command
        val server = RouterInstallCommand(RouterCompactBootstrapFixtures.BASE, "a".repeat(64), RouterStorageMode.RAM, protocols, true, licenseAccepted = true).command
        assertTrue("'--ram'" in ordinary && "'--auto'" !in ordinary && "'--flash'" !in ordinary)
        assertTrue("'--vless'" in ordinary && "'--wg'" in ordinary && "'--hy2'" !in ordinary && "'--server'" !in ordinary)
        assertEquals(ordinary.removeSuffix("'--apply'") + "'--server' '--apply'", server)
    }

    @Test fun aSchemaOneLabelCannotDowngradeCompactFieldsIntoTheLegacyParser() {
        val fixture = RouterCompactBootstrapFixtures()
        val body = fixture.catalogDocument.compactChanged("schema", JsonPrimitive(1)).toString().toByteArray()
        assertThrows(RuntimeException::class.java) {
            RouterInstallCatalog.parseVerified(body, routerReleaseSha256(body), fixture.manifestSha256)
        }
    }

    @Test fun duplicateJsonKeysOverflowAndUnknownBackupCannotBecomeAnInstallBudget() {
        assertThrows(RuntimeException::class.java) { routerCompactDocument("{\"schema\":2,\"schema\":2}".toByteArray(), 100) }
        for (text in listOf("", "-1", "1\n2", "536870913", "1e2", "9223372036854775808")) {
            assertThrows(RouterInspectionFailure::class.java) { parseRouterCompactBackupBytes(text.toByteArray()) }
        }
        assertEquals(0L, parseRouterCompactBackupBytes("0\n".toByteArray()))
        assertEquals(4096L, parseRouterCompactBackupBytes("4096\n".toByteArray()))
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { return }
        catch (_: IllegalStateException) { return }
        throw AssertionError("invalid compact metadata was accepted")
    }
}
