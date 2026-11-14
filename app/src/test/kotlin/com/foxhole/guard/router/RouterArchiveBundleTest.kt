package com.foxhole.guard.router

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class RouterArchiveBundleTest {
    private val descriptor = requireNotNull(javaClass.getResourceAsStream(
        "/router-bootstrap-releases/openwrt-1.0.6-bundle-transport.json")).use { it.readBytes() }
    private val sums = ReleasedRouterBootstrapFixtures.bytes("openwrt-1.0.6-SHA256SUMS")
    private val manifestHash = "b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d"
    private val manifest = verifyRouterReleaseManifest(sums, manifestHash)
    private val snapshot = "apk-22f49453c235d2caefe6859bf797a96bfcd874b1f43df6ee45dc6a48a37e57d3.apk"

    @Test fun publishedArchiveIsBoundToTheSignedManifestAndAll147Entries() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        assertEquals(147, archive.entries.size)
        assertEquals(11786240L, archive.archive.bytes)
        assertEquals("native-install.tar", archive.archive.name)
        assertEquals(manifest["native-install.tar"], archive.archive.sha256)
        assertEquals(107337L, requireNotNull(archive.entries[snapshot]).bytes)
    }

    @Test fun archiveReplacesOnlyItsExactFlatSnapshotsAndRetainsCoreAndMetadata() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        val entry = requireNotNull(archive.entries[snapshot])
        val core = RouterBundleAsset("foxcore-engine-test.apk", "d".repeat(64), 1024)
        val assets = linkedMapOf("SHA256SUMS" to RouterBundleAsset("SHA256SUMS", manifestHash, sums.size.toLong(), sums),
            snapshot to RouterBundleAsset(snapshot, entry.sha256, entry.bytes), core.name to core)
        archive.materialize(assets)
        assertEquals(setOf("SHA256SUMS", core.name, "native-install.tar"), assets.keys)
        assertSame(core, assets[core.name])
        val plan = RouterInstallBundlePlan("https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6",
            manifestHash, assets.values.toList(), archive.workingRamBytes)
        assertEquals(11786240L + 16777216L + descriptor.size, plan.transportWorkingRamBytes)
        assertEquals(1024L + plan.stagingBytes + plan.transportWorkingRamBytes, routerInstallBundleRequiredRam(1024, plan))
    }

    @Test fun archiveCannotRelabelDifferentBytesOfASelectedPackage() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        val entry = requireNotNull(archive.entries[snapshot])
        for (asset in listOf(RouterBundleAsset(snapshot, "d".repeat(64), entry.bytes),
            RouterBundleAsset(snapshot, entry.sha256, entry.bytes + 1),
            RouterBundleAsset(snapshot, entry.sha256, entry.bytes, byteArrayOf(1)))) {
            assertThrows(IllegalArgumentException::class.java) { archive.materialize(linkedMapOf(snapshot to asset)) }
        }
    }

    @Test fun unboundDescriptorAndArchiveDigestAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { parseRouterArchiveBundle(descriptor, manifest - "bundle-transport.json") }
        assertThrows(IllegalArgumentException::class.java) {
            parseRouterArchiveBundle(descriptor, manifest + ("native-install.tar" to "d".repeat(64)))
        }
    }

    @Test fun noncanonicalWhitespaceAndDuplicateKeysAreRefusedEvenWithMatchingOuterDigest() {
        reject(descriptor.toString(Charsets.UTF_8).removeSuffix("\n"))
        reject(descriptor.toString(Charsets.UTF_8).replace("\"schema\":1", "\"schema\":1,\"schema\":1"))
        reject(descriptor.toString(Charsets.UTF_8).replace("\"schema\":1", "\"schema\":1,\"unknown\":true"))
    }

    @Test fun wrongSchemaAndUnsafeOrUnboundEntryNamesAreRefused() {
        val text = descriptor.toString(Charsets.UTF_8)
        reject(text.replace("\"schema\":1", "\"schema\":2"))
        reject(text.replace(snapshot, "../outside.apk"))
        reject(text.replace(snapshot, "SHA256SUMS"))
        reject(text.replace(snapshot, "other.apk"))
    }

    @Test fun misalignedUndersizedAndUnboundedArchivesAreRefused() {
        val text = descriptor.toString(Charsets.UTF_8)
        for (size in listOf(11786241L, 1024L, 536871424L)) reject(text.replace("\"bytes\":11786240", "\"bytes\":$size"))
    }

    @Test fun residentRuntimeDoesNotConsumeTmpfsWhileStagingAndReservesRemainRequired() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        val assets = listOf(RouterBundleAsset("SHA256SUMS", manifestHash, sums.size.toLong(), sums), archive.archive)
        val base = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6"
        val working = 128L * 1024 * 1024
        val resident = 64L * 1024 * 1024
        val plan = RouterInstallBundlePlan(base, manifestHash, assets, archive.workingRamBytes, resident)
        val ram = routerInstallBundleRequiredRam(working, plan)
        val tmp = routerInstallBundleRequiredTmp(working, plan)
        assertEquals(resident, ram - tmp)
        assertEquals(working + plan.stagingBytes + archive.workingRamBytes, ram)
        assertEquals(working - resident + plan.stagingBytes + archive.workingRamBytes, tmp)
        val legacy = plan.copy(residentRuntimeBytes = 0)
        assertEquals(routerInstallBundleRequiredRam(working, legacy), routerInstallBundleRequiredTmp(working, legacy))
    }

    @Test fun impossibleResidentMemoryCannotReduceTheTemporaryBudget() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        val plan = RouterInstallBundlePlan("https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6",
            manifestHash, listOf(RouterBundleAsset("SHA256SUMS", manifestHash, sums.size.toLong(), sums), archive.archive),
            archive.workingRamBytes, residentRuntimeBytes = 1025L)
        assertThrows(IllegalArgumentException::class.java) { routerInstallBundleRequiredTmp(1024L, plan) }
    }

    @Test fun aFlatArchiveFileCannotOverrideTheDescriptor() {
        val archive = parseRouterArchiveBundle(descriptor, manifest)
        assertThrows(IllegalArgumentException::class.java) {
            archive.materialize(linkedMapOf("native-install.tar" to archive.archive))
        }
    }

    private fun reject(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertThrows(RuntimeException::class.java) {
            parseRouterArchiveBundle(bytes, manifest + ("bundle-transport.json" to hash))
        }
    }
}
