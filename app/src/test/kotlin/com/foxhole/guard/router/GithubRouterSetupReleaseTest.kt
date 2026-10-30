package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubRouterSetupReleaseTest {
    private val hash = "a".repeat(64)
    private val ownSigningFingerprint = "fa220a28fd8662fc5f617f46594bafe2415b4f605136570dc3c446caeb7d1ef1"
    private val upstreamKeyHash = "f".repeat(64)
    private val ownIndexHash = "b".repeat(64)
    private val upstreamIndexHash = "c".repeat(64)
    private val variant = RouterInstallVariant("hy2-x86", "x86_64", setOf("25.12.5"), setOf("x86/64"),
        setOf(RouterInstallProtocol.HYSTERIA2), RouterInstallAsset("producer.json", hash), hash, hash,
        RouterInstallAsset("capabilities.json", hash), mapOf(RouterStorageMode.FLASH to RouterInstallAsset("budget.json", hash)))
    private val system = RouterSystemInspection("25.12.5", RouterPlatform("25.12", "x86/64", "x86_64"),
        RouterResourceFacts(1000, 1000), "6.12.75~fixture-r1")
    private val budget = Json.parseToJsonElement("""{
      "schema":1,"architecture":"x86_64","mode":"flash","coreProducerSha256":"$hash","capabilitiesSha256":"$hash",
      "supportedTargets":["x86/64"],"supportedOpenWrt":["25.12.5"],"protocols":["hysteria2"],"platformPackages":{"kernel":"6.12.75~fixture-r1"},
      "verification":{"signatures":true,"resolver":"apk add --simulate --no-network","signatureModel":"canonical-apk-and-upstream-index-v1","factorySourceSha256":"$hash"},
      "corePackage":{"payloadVerified":true,"binarySha256":"$hash","producerSha256":"$hash","file":"core.apk","sha256":"$hash"},
      "runtimeMemoryBytes":128,"memoryEvidence":{"schema":1,"type":"conservative","description":"synthetic parser fixture","runtimeMemoryBytes":128},
      "compressedBytes":2000,"installedFlashBytes":4000,"installedRamBytes":0,"stagingBytes":9400,"flashReserveBytes":64,"ramReserveBytes":64,
      "requiredFlashBytes":4064,"requiredRamBytes":9592,"rootPackages":["foxcore-cli"],"dependencyInstalledBytes":2000,
      "packages":[{"name":"foxcore-cli","version":"1.0.0","arch":"x86_64","file":"core.apk","sha256":"$hash","archiveBytes":1000,"installedBytes":2000},
      {"name":"kernel","version":"6.12.75~fixture-r1","arch":"x86_64","file":"kernel.apk","sha256":"$hash","archiveBytes":1000,"installedBytes":2000}],
      "repositoryIndexBytes":300,"trustKeyBytes":800,
      "trustKeys":[{"file":"foxhole-openwrt-apk.pem","sha256":"$ownSigningFingerprint","bytes":400},{"file":"upstream.pem","sha256":"$upstreamKeyHash","bytes":400}],
      "repositoryIndexes":[{"file":"own.adb","sha256":"$ownIndexHash","bytes":100,"role":"foxhole",
        "sourceUrl":"https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/packages.adb","trustKeyFiles":["foxhole-openwrt-apk.pem"]},
        {"file":"upstream.adb","sha256":"$upstreamIndexHash","bytes":200,"role":"upstream",
        "sourceUrl":"https://downloads.openwrt.org/releases/25.12.5/packages/x86_64/base/packages.adb","trustKeyFiles":["upstream.pem"]}],
      "packageAuthentications":[{"file":"core.apk","indexFile":"own.adb","name":"foxcore-cli","version":"1.0.0","architecture":"x86_64","packageIdentity":"$hash"},
        {"file":"kernel.apk","indexFile":"upstream.adb","name":"kernel","version":"6.12.75~fixture-r1","architecture":"x86_64","packageIdentity":"$hash"}],
      "filesSha256":{"core.apk":"$hash","kernel.apk":"$hash","own.adb":"$ownIndexHash","upstream.adb":"$upstreamIndexHash","foxhole-openwrt-apk.pem":"$ownSigningFingerprint","upstream.pem":"$upstreamKeyHash"}
    }""") as JsonObject
    private val manifest = mapOf("core.apk" to hash, "kernel.apk" to hash, "own.adb" to ownIndexHash,
        "upstream.adb" to upstreamIndexHash, "foxhole-openwrt-apk.pem" to ownSigningFingerprint, "upstream.pem" to upstreamKeyHash)

    @Test fun exactSignedModeBudgetIncludesDependenciesStagingAndWorkingMemory() {
        val result = verifyRouterModeBudget(budget, RouterStorageMode.FLASH, variant, system, manifest)
        assertEquals(4064L, result.first.flashPersistentBytes)
        assertEquals(9592L, result.first.flashWorkingMemoryBytes)
        assertEquals(2, result.second.size)
    }

    @Test fun aQualifiedMemoryPolicyKeepsTheCompleteBudgetUnchanged() {
        val memory = budget["memoryEvidence"] as JsonObject
        val qualified = JsonObject(memory + mapOf("provisional" to JsonPrimitive(false), "acceptedForRelease" to JsonPrimitive(true)))
        val result = verifyRouterModeBudget(JsonObject(budget + ("memoryEvidence" to qualified)), RouterStorageMode.FLASH, variant, system, manifest)
        assertEquals(4064L, result.first.flashPersistentBytes)
        assertEquals(9592L, result.first.flashWorkingMemoryBytes)
    }

    @Test fun provisionalUnacceptedAndMistypedMemoryQualificationsAreRefused() {
        val memory = budget["memoryEvidence"] as JsonObject
        for ((field, flag) in listOf("provisional" to JsonPrimitive(true), "acceptedForRelease" to JsonPrimitive(false),
            "provisional" to JsonPrimitive("false"), "acceptedForRelease" to JsonPrimitive("true"),
            "provisional" to JsonPrimitive(0), "acceptedForRelease" to kotlinx.serialization.json.JsonNull)) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyRouterModeBudget(JsonObject(budget + ("memoryEvidence" to JsonObject(memory + (field to flag)))),
                    RouterStorageMode.FLASH, variant, system, manifest)
            }
        }
    }

    @Test fun malformedNegativeBooleanAndInconsistentBudgetsAreRefused() {
        for ((field, changed) in listOf("requiredRamBytes" to JsonPrimitive(-1), "requiredRamBytes" to JsonPrimitive(true),
            "requiredRamBytes" to JsonPrimitive("9592"), "requiredRamBytes" to JsonPrimitive(9591),
            "requiredFlashBytes" to JsonPrimitive(4063), "stagingBytes" to JsonPrimitive(9399),
            "compressedBytes" to JsonPrimitive(2001), "installedFlashBytes" to JsonPrimitive(4001),
            "dependencyInstalledBytes" to JsonPrimitive(1999))) {
            assertThrows(Exception::class.java) {
                verifyRouterModeBudget(JsonObject(budget + (field to changed)), RouterStorageMode.FLASH, variant, system, manifest)
            }
        }
    }

    @Test fun packageKernelMismatchAndMissingSignatureProofAreRejectedBeforeInstall() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(budget, RouterStorageMode.FLASH, variant, system.copy(kernelVersion = "other-r1"), manifest)
        }
        val unsigned = JsonObject(budget + ("verification" to JsonObject(mapOf("signatures" to JsonPrimitive(false),
            "resolver" to JsonPrimitive("apk add --simulate --no-network")))))
        assertThrows(IllegalArgumentException::class.java) { verifyRouterModeBudget(unsigned, RouterStorageMode.FLASH, variant, system, manifest) }
        val stringFlag = JsonObject(budget + ("verification" to JsonObject(mapOf("signatures" to JsonPrimitive("true"),
            "resolver" to JsonPrimitive("apk add --simulate --no-network")))))
        assertThrows(IllegalArgumentException::class.java) { verifyRouterModeBudget(stringFlag, RouterStorageMode.FLASH, variant, system, manifest) }
        assertThrows(IllegalArgumentException::class.java) { verifyRouterModeBudget(budget, RouterStorageMode.FLASH, variant, system, emptyMap()) }
    }

    @Test fun onlyTheReviewedManifestHashAuthenticatesReleaseEntries() {
        val bytes = "$hash  package-catalog.json\n$hash  install.sh\n".toByteArray()
        assertEquals(mapOf("package-catalog.json" to hash, "install.sh" to hash), verifyRouterReleaseManifest(bytes, routerReleaseSha256(bytes)))
        assertThrows(IllegalArgumentException::class.java) { verifyRouterReleaseManifest(bytes, "b".repeat(64)) }
    }

    @Test fun duplicateAndPathTraversalReleaseEntriesAreRefusedEvenWhenTheirHashMatches() {
        for (text in listOf("$hash  ../install.sh\n", "$hash  install.sh\n$hash  install.sh\n", "$hash *install.sh\n")) {
            val bytes = text.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { verifyRouterReleaseManifest(bytes, routerReleaseSha256(bytes)) }
        }
    }

    private val serverHash = "c".repeat(64)
    private val serverAsset = RouterInstallAsset("server-producer.json", "b".repeat(64))
    private val serverVariant get() = variant.copy(serverProducer = serverAsset)
    private val serverProducer get() = RouterServerProducer(serverHash, 30)
    private val serverManifest get() = manifest + mapOf("server-producer.json" to serverAsset.sha256,
        "core.bin" to hash, "server.bin" to serverHash, "server.apk" to serverHash)
    private fun ramServerBudget(): JsonObject {
        val values = Json.parseToJsonElement("""{
            "mode":"ram","serverProducerSha256":"${serverAsset.sha256}",
            "serverPackage":{"file":"server.apk","sha256":"$serverHash","binarySha256":"$serverHash","producerSha256":"${serverAsset.sha256}","payloadVerified":true},
            "compressedBytes":2500,"installedFlashBytes":4700,"installedRamBytes":50,"stagingBytes":11150,
            "requiredFlashBytes":4764,"requiredRamBytes":11342,"rootPackages":["foxcore-cli-ram","hysteria-server-ram"],
            "volatileBinary":{"file":"core.bin","bytes":20,"sha256":"$hash"},
            "volatileDependencies":[{"file":"server.bin","bytes":30,"sha256":"$serverHash","producerFile":"${serverAsset.file}","producerSha256":"${serverAsset.sha256}"}],
            "packages":[{"name":"foxcore-cli-ram","version":"1.0.0","arch":"x86_64","file":"core.apk","sha256":"$hash","archiveBytes":1000,"installedBytes":2000},
                {"name":"kernel","version":"6.12.75~fixture-r1","arch":"x86_64","file":"kernel.apk","sha256":"$hash","archiveBytes":1000,"installedBytes":2000},
                {"name":"hysteria-server-ram","version":"2.12.2","arch":"x86_64","file":"server.apk","sha256":"$serverHash","archiveBytes":500,"installedBytes":700}],
            "packageAuthentications":[{"file":"core.apk","indexFile":"own.adb","name":"foxcore-cli-ram","version":"1.0.0","architecture":"x86_64","packageIdentity":"$hash"},
                {"file":"kernel.apk","indexFile":"upstream.adb","name":"kernel","version":"6.12.75~fixture-r1","architecture":"x86_64","packageIdentity":"$hash"},
                {"file":"server.apk","indexFile":"own.adb","name":"hysteria-server-ram","version":"2.12.2","architecture":"x86_64","packageIdentity":"$serverHash"}],
            "filesSha256":{"core.apk":"$hash","kernel.apk":"$hash","server.apk":"$serverHash","own.adb":"$ownIndexHash","upstream.adb":"$upstreamIndexHash","foxhole-openwrt-apk.pem":"$ownSigningFingerprint","upstream.pem":"$upstreamKeyHash"}
        }""") as JsonObject
        return JsonObject(budget + values)
    }

    @Test fun ramBudgetCountsBothAuthenticatedVolatilePayloads() {
        val result = verifyRouterModeBudget(ramServerBudget(), RouterStorageMode.RAM, serverVariant, system, serverManifest, serverProducer)
        assertEquals(4764L, result.first.ramPersistentBytes)
        assertEquals(11342L, result.first.ramWorkingMemoryBytes)
    }

    @Test fun missingServerProducerAndUnboundVolatilePayloadAreRejected() {
        val original = ramServerBudget()
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(original, RouterStorageMode.RAM, serverVariant, system, serverManifest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(original, RouterStorageMode.RAM, serverVariant, system, serverManifest - "server.bin", serverProducer)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(original, RouterStorageMode.RAM, serverVariant, system, serverManifest - serverAsset.file, serverProducer)
        }
    }

    @Test fun volatileDuplicateSizeHashAndProducerMismatchesAreRejected() {
        val original = ramServerBudget()
        val dependency = (original["volatileDependencies"] as JsonArray).single() as JsonObject
        for ((key, changed) in listOf("file" to JsonPrimitive("core.bin"), "file" to JsonPrimitive("../server.bin"),
            "bytes" to JsonPrimitive(29), "bytes" to JsonPrimitive("30"), "sha256" to JsonPrimitive(hash),
            "producerFile" to JsonPrimitive("producer.json"), "producerSha256" to JsonPrimitive(hash))) {
            val modified = JsonObject(original + ("volatileDependencies" to JsonArray(listOf(JsonObject(dependency + (key to changed))))))
            assertThrows(Exception::class.java) {
                verifyRouterModeBudget(modified, RouterStorageMode.RAM, serverVariant, system, serverManifest, serverProducer)
            }
        }
        for (changed in listOf(JsonArray(emptyList()), JsonArray(listOf(dependency, dependency)))) {
            assertThrows(IllegalArgumentException::class.java) {
                verifyRouterModeBudget(JsonObject(original + ("volatileDependencies" to changed)), RouterStorageMode.RAM,
                    serverVariant, system, serverManifest, serverProducer)
            }
        }
    }

    @Test fun flashBudgetCannotHideAnExtraRamPayload() {
        val dependency = ramServerBudget()["volatileDependencies"]!!
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(JsonObject(budget + ("volatileDependencies" to dependency)), RouterStorageMode.FLASH, variant, system, manifest)
        }
    }

    @Test fun serverProducerRequiresActualSuccessfulTargetAndExactFeatureRoles() {
        val producer = Json.parseToJsonElement("""{
            "schema":1,"exitCode":0,"name":"hysteria","version":"2.12.2","target":"linux-amd64",
            "sourceSha256":"$hash","sourceCommit":"${"a".repeat(40)}","toolchain":"synthetic fixture",
            "binarySha256":"$serverHash","binaryBytes":30,"features":["incoming-hysteria2","legacy-outgoing-hysteria2"]
        }""") as JsonObject
        assertEquals(serverProducer, verifyRouterServerProducer(producer, serverVariant))
        for ((key, changed) in listOf("exitCode" to JsonPrimitive(1), "target" to JsonPrimitive("linux-arm64"),
            "binaryBytes" to JsonPrimitive(-1), "binaryBytes" to JsonPrimitive("30"),
            "features" to JsonArray(listOf(JsonPrimitive("incoming-hysteria2"))))) {
            assertThrows(Exception::class.java) { verifyRouterServerProducer(JsonObject(producer + (key to changed)), serverVariant) }
        }
    }

    @Test fun coreProducerMustMatchArchitectureAndCapabilityVersion() {
        val producer = Json.parseToJsonElement("""{"schema":1,"exitCode":0,"target":"x86_64-unknown-linux-musl",
            "coreVersion":"1.0.0","binarySha256":"$hash","sourceSha256":"$hash","protocols":["hysteria2"]}""") as JsonObject
        val capabilities = Json.parseToJsonElement("""{"schema_version":1,"router_contract_version":1,
            "platform":"linux","core_version":"1.0.0","protocols":["hysteria2"]}""") as JsonObject
        verifyRouterCoreProducer(producer, capabilities, variant)
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterCoreProducer(JsonObject(producer + ("target" to JsonPrimitive("aarch64-unknown-linux-musl"))), capabilities, variant)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterCoreProducer(producer, JsonObject(capabilities + ("core_version" to JsonPrimitive("0.0.5"))), variant)
        }
    }

    @Test fun kernelReceiptCannotMasqueradeAsTheServerPackage() {
        val original = ramServerBudget()
        val server = original["serverPackage"] as JsonObject
        val forged = JsonObject(original + mapOf(
            "serverPackage" to JsonObject(server + mapOf("file" to JsonPrimitive("kernel.apk"), "sha256" to JsonPrimitive(hash))),
            "rootPackages" to JsonArray(listOf("foxcore-cli-ram", "hysteria-server-ram", "kernel").map(::JsonPrimitive)),
            "dependencyInstalledBytes" to JsonPrimitive(0)))
        assertThrows(IllegalArgumentException::class.java) {
            verifyRouterModeBudget(forged, RouterStorageMode.RAM, serverVariant, system, serverManifest, serverProducer)
        }
    }

    @Test fun kernelReceiptCannotMasqueradeAsTheCorePackage() {
        val core = budget["corePackage"] as JsonObject
        val forged = JsonObject(budget + mapOf(
            "corePackage" to JsonObject(core + ("file" to JsonPrimitive("kernel.apk"))),
            "rootPackages" to JsonArray(listOf("foxcore-cli", "kernel").map(::JsonPrimitive)),
            "dependencyInstalledBytes" to JsonPrimitive(0)))
        assertThrows(IllegalArgumentException::class.java) { verifyRouterModeBudget(forged, RouterStorageMode.FLASH, variant, system, manifest) }
    }

    private fun repositoryProof(value: JsonObject = budget, files: Map<String, String> = manifest): RouterRepositoryBytes =
        verifyRouterRepositoryAuthentication(value, (value["packages"] as JsonArray).map { it as JsonObject }, files, "x86_64")

    @Test fun repositoryMetadataCountsEachSharedSameRoleKeyOnlyOnce() {
        assertEquals(RouterRepositoryBytes(300, 800), repositoryProof())
        val indexes = budget["repositoryIndexes"] as JsonArray
        val extra = JsonObject((indexes[0] as JsonObject) + mapOf("file" to JsonPrimitive("own-extra.adb"), "bytes" to JsonPrimitive(50)))
        val hashes = budget["filesSha256"] as JsonObject
        val shared = JsonObject(budget + mapOf("repositoryIndexes" to JsonArray(indexes + extra),
            "repositoryIndexBytes" to JsonPrimitive(350), "filesSha256" to JsonObject(hashes + ("own-extra.adb" to JsonPrimitive(ownIndexHash)))))
        assertEquals(RouterRepositoryBytes(350, 800), repositoryProof(shared, manifest + ("own-extra.adb" to ownIndexHash)))
    }

    @Test fun trustKeysMustBeDeclaredUsedCanonicalAndDistinctAcrossRoles() {
        val indexes = budget["repositoryIndexes"] as JsonArray
        for (role in indexes.indices) {
            val changed = JsonObject((indexes[role] as JsonObject) + ("trustKeyFiles" to JsonArray(listOf(JsonPrimitive(if (role == 0) "upstream.pem" else "foxhole-openwrt-apk.pem")))))
            assertThrows(IllegalArgumentException::class.java) {
                repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(indexes.mapIndexed { i, item -> if (i == role) changed else item }))))
            }
        }
        val keys = budget["trustKeys"] as JsonArray
        val duplicateHash = JsonObject((keys[1] as JsonObject) + ("sha256" to JsonPrimitive(ownSigningFingerprint)))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("trustKeys" to JsonArray(listOf(keys[0], duplicateHash)))))
        }
        val unreferenced = JsonObject((keys[1] as JsonObject) + mapOf("file" to JsonPrimitive("unused.pem"), "sha256" to JsonPrimitive("e".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("trustKeys" to JsonArray(keys + unreferenced))), manifest + ("unused.pem" to "e".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("trustKeys" to JsonArray(keys + keys[0]))))
        }
        val hashes = budget["filesSha256"] as JsonObject
        val alias = JsonObject((keys[0] as JsonObject) + ("file" to JsonPrimitive("canonical-alias.pem")))
        val aliasIndex = JsonObject((indexes[0] as JsonObject) + ("trustKeyFiles" to JsonArray(listOf(JsonPrimitive("canonical-alias.pem")))))
        val aliasFiles = JsonObject((hashes - "foxhole-openwrt-apk.pem") + ("canonical-alias.pem" to JsonPrimitive(ownSigningFingerprint)))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + mapOf("trustKeys" to JsonArray(listOf(alias, keys[1])),
                "repositoryIndexes" to JsonArray(listOf(aliasIndex, indexes[1])), "filesSha256" to aliasFiles)),
                (manifest - "foxhole-openwrt-apk.pem") + ("canonical-alias.pem" to ownSigningFingerprint))
        }
        val duplicateUpstream = JsonObject((keys[1] as JsonObject) + ("file" to JsonPrimitive("upstream-alias.pem")))
        val duplicateIndex = JsonObject((indexes[1] as JsonObject) +
            ("trustKeyFiles" to JsonArray(listOf("upstream.pem", "upstream-alias.pem").map(::JsonPrimitive))))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + mapOf("trustKeys" to JsonArray(keys + duplicateUpstream),
                "repositoryIndexes" to JsonArray(listOf(indexes[0], duplicateIndex)), "trustKeyBytes" to JsonPrimitive(1200),
                "filesSha256" to JsonObject(hashes + ("upstream-alias.pem" to JsonPrimitive(upstreamKeyHash))))),
                manifest + ("upstream-alias.pem" to upstreamKeyHash))
        }
    }

    @Test fun repositorySourcesCannotRedirectToOtherPublishersArchitecturesOrSnapshots() {
        val indexes = budget["repositoryIndexes"] as JsonArray
        for ((position, address) in listOf(0 to "http://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/packages.adb",
            0 to "https://github.com/another/repository/releases/download/v1.0.0/packages.adb",
            0 to "https://user@github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/packages.adb",
            0 to "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0/%2fpackages.adb",
            1 to "https://downloads.openwrt.org/snapshots/packages/x86_64/base/packages.adb",
            1 to "https://downloads.openwrt.org/releases/25.12.4/packages/x86_64/base/packages.adb",
            1 to "https://downloads.openwrt.org/releases/25.12.5/packages/aarch64_generic/base/packages.adb",
            1 to "https://downloads.openwrt.org/releases/25.12.5/targets/mediatek/filogic/packages/packages.adb",
            1 to "https://downloads.openwrt.org/releases/25.12.5/packages/x86_64/base/packages.adb?redirect=true")) {
            val changed = JsonObject((indexes[position] as JsonObject) + ("sourceUrl" to JsonPrimitive(address)))
            assertThrows(IllegalArgumentException::class.java) {
                repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(indexes.mapIndexed { i, item -> if (i == position) changed else item }))))
            }
        }
        for (path in listOf("targets/x86/64/packages/packages.adb", "targets/x86/64/kmods/6.12.75-1-fixture/packages.adb")) {
            val changed = JsonObject((indexes[1] as JsonObject) + ("sourceUrl" to JsonPrimitive("https://downloads.openwrt.org/releases/25.12.5/$path")))
            assertEquals(RouterRepositoryBytes(300, 800), repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(listOf(indexes[0], changed))))))
        }
    }

    @Test fun originalSignedEntryMustMatchEachPackageAndItsRoleExactlyOnce() {
        val authentications = budget["packageAuthentications"] as JsonArray
        for ((field, changed) in listOf("name" to JsonPrimitive("kernel"), "version" to JsonPrimitive("other-r1"),
            "architecture" to JsonPrimitive("aarch64_generic"), "packageIdentity" to JsonPrimitive("claimed-verified"),
            "packageIdentity" to JsonPrimitive("d".repeat(40)),
            "indexFile" to JsonPrimitive("upstream.adb"), "file" to JsonPrimitive("missing.apk"))) {
            val forged = JsonObject((authentications[0] as JsonObject) + (field to changed))
            assertThrows(IllegalArgumentException::class.java) {
                repositoryProof(JsonObject(budget + ("packageAuthentications" to JsonArray(listOf(forged, authentications[1])))))
            }
        }
        for (changed in listOf(JsonArray(listOf(authentications[0])), JsonArray(authentications + authentications[0]))) {
            assertThrows(IllegalArgumentException::class.java) { repositoryProof(JsonObject(budget + ("packageAuthentications" to changed))) }
        }
        val packages = budget["packages"] as JsonArray
        val wrongArch = JsonObject((packages[0] as JsonObject) + ("arch" to JsonPrimitive("aarch64_generic")))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("packages" to JsonArray(listOf(wrongArch, packages[1])))))
        }
    }

    @Test fun metadataFileUnionAndByteCountsCannotHideUnsignedOrUndeclaredAssets() {
        val hashes = budget["filesSha256"] as JsonObject
        for (changed in listOf(JsonObject(hashes - "foxhole-openwrt-apk.pem"), JsonObject(hashes + ("extra.apk" to JsonPrimitive(hash))),
            JsonObject(hashes + ("upstream.adb" to JsonPrimitive(hash))))) {
            assertThrows(IllegalArgumentException::class.java) { repositoryProof(JsonObject(budget + ("filesSha256" to changed))) }
        }
        for ((field, changed) in listOf("repositoryIndexBytes" to JsonPrimitive(299), "repositoryIndexBytes" to JsonPrimitive("300"),
            "trustKeyBytes" to JsonPrimitive(true), "trustKeyBytes" to JsonPrimitive(799))) {
            assertThrows(IllegalArgumentException::class.java) { repositoryProof(JsonObject(budget + (field to changed))) }
        }
        assertThrows(IllegalArgumentException::class.java) { repositoryProof(files = manifest - "upstream.pem") }
        val indexes = budget["repositoryIndexes"] as JsonArray
        for (bytes in listOf(JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive(8388609), JsonPrimitive(true), JsonPrimitive("100"))) {
            val changed = JsonObject((indexes[0] as JsonObject) + ("bytes" to bytes))
            assertThrows(IllegalArgumentException::class.java) {
                repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(listOf(changed, indexes[1])))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(indexes + indexes[0]))))
        }
        val keys = budget["trustKeys"] as JsonArray
        val oversized = JsonObject((keys[1] as JsonObject) + ("bytes" to JsonPrimitive(16385)))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + mapOf("trustKeys" to JsonArray(listOf(keys[0], oversized)), "trustKeyBytes" to JsonPrimitive(16785))))
        }
        val misplaced = JsonObject((indexes[0] as JsonObject) + ("file" to JsonPrimitive("../own.adb")))
        assertThrows(IllegalArgumentException::class.java) {
            repositoryProof(JsonObject(budget + ("repositoryIndexes" to JsonArray(listOf(misplaced, indexes[1])))))
        }
        val verification = budget["verification"] as JsonObject
        for ((field, changed) in listOf("signatureModel" to JsonPrimitive("caller-verified"), "factorySourceSha256" to JsonPrimitive(true))) {
            assertThrows(IllegalArgumentException::class.java) {
                repositoryProof(JsonObject(budget + ("verification" to JsonObject(verification + (field to changed)))))
            }
        }
    }
}
