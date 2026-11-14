package com.foxhole.guard.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.*

/** Real SSH install, TLS pairing and authenticated settings on one owned disposable router. */
class RouterDefaultSetupLabTest {
    @Test fun freshInstallDefaultsAndExistingManagementPreservePolicy() = runBlocking {
        withTimeout(600_000) {
            com.jcraft.jsch.JSch.setLogger(object : com.jcraft.jsch.Logger {
                override fun isEnabled(level: Int) = true
                override fun log(level: Int, message: String) {
                    val known = listOf("router_output_limit" to "OUTPUT_LIMIT", "Read timed out" to "READ_TIMEOUT",
                        "Socket closed" to "SOCKET_CLOSED", "session is down" to "SESSION_DOWN",
                        "SSH_MSG_DISCONNECT" to "PEER_DISCONNECT", "Packet corrupt" to "PACKET_REJECTED",
                        "Caught an exception, leaving main loop" to "SESSION_EXCEPTION")
                    val codes = known.filter { it.first in message }.map { it.second }
                    if (codes.isNotEmpty()) println("LAB SSH library codes=$codes")
                }
            })
            val fixture = Fixture.load()
            var initialBootId: String? = null
            FixedLabNetwork(fixture).use { network ->
                MemoryRouterPairingStore().use { store ->
                    val real = RouterHttpProvisioner(localAddress = { fixture.peerAddress },
                        elapsedMs = ::clock, pairingStore = store)
                    val audit = Audit(real)
                    val cacheRoot = System.getenv("FOXHOLE_ROUTER_LAB_CACHE_ROOT")?.let(::File)
                    if (cacheRoot != null) require(cacheRoot.isDirectory && !Files.isSymbolicLink(cacheRoot.toPath()))
                    val installCache = if (cacheRoot == null) Files.createTempDirectory("owned-router-install-").toFile()
                        else Files.createTempDirectory(cacheRoot.toPath(), "owned-router-install-").toFile()
                    val attemptedBundle = AtomicReference<Pair<RouterSetupPayload, RouterInstallProposal>?>()
                    val publishedRelease = GithubRouterSetupRelease(RELEASE, MANIFEST)
                    val release = object : RouterSetupRelease by publishedRelease {
                        override suspend fun bundlePlan(payload: RouterSetupPayload, proposal: RouterInstallProposal): RouterInstallBundlePlan {
                            val plan = publishedRelease.bundlePlan(payload, proposal)
                            attemptedBundle.set(payload to proposal)
                            val budget = completeRouterBudget(payload.budgets)
                            val working = if (proposal.storageMode == RouterStorageMode.FLASH)
                                budget.flashWorkingMemoryBytes else budget.ramWorkingMemoryBytes
                            println("LAB bundle resources: mode=${proposal.storageMode} bytes=${plan.bytes} staging=${plan.stagingBytes} " +
                                "transport=${plan.transportWorkingRamBytes} neededRam=${routerInstallBundleRequiredRam(working, plan)} " +
                                "neededTmp=${routerInstallBundleRequiredTmp(working, plan)} resident=${plan.residentRuntimeBytes} cacheFree=${installCache.usableSpace}")
                            return plan
                        }
                    }
                    val publishedClient = routerPublicReleaseClient().newBuilder()
                        .addInterceptor { chain ->
                            val request = chain.request()
                            if (request.url.host == "github.com") println("LAB published asset=${request.url.pathSegments.last()}")
                            try {
                                chain.proceed(request).also { println("LAB publisher host=${request.url.host} status=${it.code}") }
                            } catch (failure: java.io.IOException) {
                                println("LAB publisher failure=${failure.javaClass.simpleName}")
                                if (request.url.host == "127.0.0.1") {
                                    println("LAB owned mirror error=" + failure.message?.lineSequence()?.firstOrNull()?.take(240))
                                    println("LAB owned mirror causes=" + generateSequence(failure as Throwable) { it.cause }
                                        .take(5).joinToString(",") { it.javaClass.simpleName })
                                }
                                throw failure
                            }
                        }.build()
                    val releaseHttp = RouterDeviceReleaseHttp(ownedLabSnapshotCalls(fixture.file, publishedClient))
                    val installer = WifiRouterSetupService(release, network::ssh, {}, Dispatchers.IO,
                        deviceBundleProvider = RouterDeviceInstallBundleProvider(installCache, releaseHttp))
                    val setup = object : RouterSetupService by installer {
                        override suspend fun install(proposal: RouterInstallProposal, licenseAccepted: Boolean,
                            onProgress: (RouterInstallProgress) -> Unit): RouterInstallResult {
                            val result = try {
                                installer.install(proposal, licenseAccepted) { progress ->
                                    println("LAB install phase=${progress.stage}")
                                    onProgress(progress)
                                }
                            } catch (failure: Exception) {
                                attemptedBundle.get()?.let { network.reportPackageChecks(it.first, it.second) }
                                throw failure
                            }
                            network.installationCompleted()
                            return result
                        }
                    }
                    val wizard = RouterWizardCoordinator(network, store, "Owned lab setup", setup, audit,
                        { null }, { false }, pinClock = ::clock)
                    try {
                        wizard.begin(RouterWizardPath.SETUP, null)
                        assertTrue(wizard.state.value.options.autoUpdateEnabled)
                        wizard.inspectAddress(fixture.logicalAddress)
                        assertEquals(RouterWizardStage.IDENTITY, wizard.state.value.stage)
                        assertEquals("SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
                            hex(fixture.sshKeySha256)), wizard.state.value.fingerprint)
                        wizard.acceptIdentity()
                        assertEquals(RouterWizardStage.PROTOCOLS, wizard.state.value.stage)
                        assertEquals(setOf(RouterInstallProtocol.HYSTERIA2), wizard.state.value.installSelection?.protocols)
                        assertFalse(wizard.state.value.installSelection!!.includeServer)
                        if (!fixture.initialAutoUpdate) wizard.options(wizard.state.value.options.copy(autoUpdateEnabled = false))
                        wizard.confirmInstallProtocols()
                        val actualKey = network.ssh(fixture.logicalAddress, null).use { client ->
                            try { client.connectForDiscovery(); error("Expected SSH key review") }
                            catch (review: RouterSshTrustRequired) { review.key }
                        }
                        assertEquals(fixture.sshKeySha256, sha(actualKey))
                        network.ssh(fixture.logicalAddress, actualKey).use { client ->
                            val password = fixture.secret("rootPassword")
                            try { client.connect(password) } finally { password.fill('\u0000') }
                            assertTrue("Pending guest UCI changes", client.read(RouterReadCommand.CONFIGURATION).toString(Charsets.UTF_8).isBlank())
                            val board = client.read(RouterReadCommand.BOARD)
                            val architecture = client.read(RouterReadCommand.ARCHITECTURE)
                            val flash = client.read(RouterReadCommand.FLASH)
                            val memory = client.read(RouterReadCommand.MEMORY)
                            val identity = Json.parseToJsonElement(board.toString(Charsets.UTF_8)).jsonObject["release"]!!.jsonObject
                            println("LAB platform: version=${identity["version"]} target=${identity["target"]} arch=${architecture.toString(Charsets.UTF_8).trim()}")
                            val facts = parseRouterSystem(board, architecture, flash, memory, release.supportedPlatforms)
                            assertTrue("Guest resource facts", facts.resources.availableRamBytes > 0)
                            val kernel = client.read(RouterReadCommand.KERNEL_PACKAGE)
                            println("LAB kernel package: " + kernel.toString(Charsets.UTF_8).take(1024).replace('\n', '|').replace('\r', '|'))
                            parseRouterKernelVersion(kernel)
                            val transfer = parseRouterBundleTransferFacts(client.read(RouterReadCommand.BUNDLE_TRANSFER_FACTS))
                            initialBootId = transfer.bootId
                            println("LAB router resources: ram=${transfer.availableRamBytes} tmp=${transfer.availableTmpBytes} flash=${facts.resources.availableFlashBytes}")
                            parseRouterCompactBackupBytes(client.readCompactBackupBytes())
                        }
                        wizard.login(fixture.secret("rootPassword"))
                        assertEquals("SSH preparation error=${wizard.state.value.error}", RouterWizardStage.INSTALL, wizard.state.value.stage)
                        assertTrue(audit.writes.isEmpty())
                        // Exercise the real license gate before the real install mutates this owned guest.
                        wizard.install()
                        assertEquals(RouterWizardError.LICENSE, wizard.state.value.error)
                        assertFalse(wizard.state.value.installedInThisWizard)
                        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
                        wizard.install()
                        if (!wizard.state.value.installedInThisWizard) {
                            var sshReachable = false
                            var sameBoot: Boolean? = null
                            var packageReceiptPresent = false
                            var receiptSchema: Long? = null
                            try {
                                network.ssh(fixture.logicalAddress, actualKey).use { probe ->
                                    val password = fixture.secret("rootPassword")
                                    try { probe.connect(password) } finally { password.fill('\u0000') }
                                    sshReachable = true
                                    sameBoot = parseRouterBundleTransferFacts(probe.read(RouterReadCommand.BUNDLE_TRANSFER_FACTS)).bootId == initialBootId
                                    val document = Json.parseToJsonElement(probe.read(RouterReadCommand.PACKAGE_MANIFEST).toString(Charsets.UTF_8)).jsonObject
                                    packageReceiptPresent = true
                                    receiptSchema = document["schema"]?.jsonPrimitive?.longOrNull
                                }
                            } catch (_: Exception) { }
                            println("LAB readonly recovery: ssh=$sshReachable sameBoot=$sameBoot receipt=$packageReceiptPresent schema=$receiptSchema")
                        }
                        assertTrue("Actual installation error=${wizard.state.value.error}", wizard.state.value.installedInThisWizard)
                        if (!wizard.state.value.initialPinRequired && wizard.state.value.installedInThisWizard) {
                            val recoveredIdentity = network.ssh(fixture.logicalAddress, actualKey).use { probe ->
                                val password = fixture.secret("rootPassword")
                                try { probe.connect(password) } finally { password.fill('\u0000') }
                                routerIdentityFromSshCertificate(fixture.logicalAddress, probe.read(RouterReadCommand.MOBILE_CERTIFICATE))
                            }
                            network.api(recoveredIdentity).use { probe ->
                                repeat(5) { attempt ->
                                    kotlinx.coroutines.delay(500)
                                    val result = try { probe.packageStatus(); "READY" }
                                        catch (failure: RouterApiFailure) { failure.kind.name }
                                    println("LAB readonly API readiness attempt=$attempt result=$result")
                                }
                            }
                        }
                        assertTrue("Initial pairing stage=${wizard.state.value.stage} error=${wizard.state.value.error}", wizard.state.value.initialPinRequired)
                        assertEquals(RouterWizardStage.OPTIONS, wizard.state.value.stage)
                        assertTrue(audit.writes.isEmpty())
                        wizard.options(wizard.state.value.options.copy(licenseAccepted = true))
                        wizard.finish(fixture.secret("newPanelPin"))
                        assertEquals(RouterWizardStage.COMPLETE, wizard.state.value.stage)
                        assertEquals(listOf(fixture.initialAutoUpdate), audit.writes)
                        assertEquals(1, audit.finishes)
                        assertFalse(audit.beforeWrite!!.autoUpdate.enabled)
                        assertTrue(audit.pairedSessionOnly)
                        var preservedData: String? = null
                        val pairing = requireNotNull(store.read())
                        try {
                            network.api(pairing.identity).use { api ->
                                api.unlockPairing(pairing).use { session ->
                                    val initial = real.updateStatus(api, session)
                                    assertEquals(fixture.initialAutoUpdate, initial.autoUpdate.enabled)
                                    val configuredState = api.state(session)
                                    assertTrue(configuredState["devices"]!!.jsonArray.isNotEmpty())
                                    val before = dataFence(configuredState)
                                    val changed = real.setUpdateAuto(api, session, initial, !fixture.initialAutoUpdate)
                                    assertEquals(!fixture.initialAutoUpdate, changed.autoUpdate.enabled)
                                    assertEquals(before, dataFence(api.state(session)))
                                    preservedData = before
                                    assertEquals(changed.autoUpdate.enabled, real.updateStatus(api, session).autoUpdate.enabled)
                                    val retained = if (changed.autoUpdate.enabled) real.setUpdateAuto(api, session, changed, false) else changed
                                    assertFalse(retained.autoUpdate.enabled)
                                    assertEquals(before, dataFence(api.state(session)))
                                }
                            }
                        } finally { pairing.clear() }
                        wizard.close()
                        // SETUP against an installed router must route to PAIR, not reset its policy.
                        val oldWrites = audit.writes.size
                        val oldReads = audit.reads
                        val reentry = RouterWizardCoordinator(network, store, "Owned lab reentry", setup, audit,
                            { null }, { false }, pinClock = ::clock)
                        try {
                            reentry.begin(RouterWizardPath.SETUP, null)
                            assertTrue(reentry.state.value.options.autoUpdateEnabled)
                            reentry.inspectAddress(fixture.logicalAddress)
                            assertEquals(RouterWizardPath.PAIR, reentry.state.value.path)
                            assertFalse(reentry.state.value.installedInThisWizard)
                            reentry.acceptIdentity()
                            reentry.login(fixture.secret("newPanelPin"))
                            assertEquals(RouterWizardStage.COMPLETE, reentry.state.value.stage)
                            assertEquals(oldWrites, audit.writes.size)
                            assertEquals(oldReads, audit.reads)
                            val saved = requireNotNull(store.read())
                            try {
                                network.api(saved.identity).use { api ->
                                    api.unlockPairing(saved).use { session ->
                                        assertFalse(real.updateStatus(api, session).autoUpdate.enabled)
                                        assertEquals(preservedData, dataFence(api.state(session)))
                                    }
                                }
                            } finally { saved.clear() }
                        } finally { reentry.close() }
                    } finally { wizard.close(); installCache.deleteRecursively() }
                }
            }
        }
    }

    private class Audit(private val real: RouterHttpProvisioner) : RouterWizardProvisioner by real {
        val writes = mutableListOf<Boolean>()
        var reads = 0
        var finishes = 0
        var pairedSessionOnly = true
        var beforeWrite: RouterUpdateStatus? = null
        override suspend fun updateStatus(api: RouterPanelApi, session: RouterPanelSession): RouterUpdateStatus {
            reads++
            pairedSessionOnly = pairedSessionOnly && session.token().startsWith("p1.")
            return real.updateStatus(api, session)
        }
        override suspend fun setUpdateAuto(api: RouterPanelApi, session: RouterPanelSession,
            status: RouterUpdateStatus, enabled: Boolean): RouterUpdateStatus {
            beforeWrite = status
            writes += enabled
            pairedSessionOnly = pairedSessionOnly && session.token().startsWith("p1.")
            return real.setUpdateAuto(api, session, status, enabled)
        }
        override suspend fun finish(api: RouterPanelApi, session: RouterPanelSession, pairing: RouterPairing,
            options: RouterWizardOptions, selectedProfile: RouterProfileSelection?): RouterWizardResult {
            finishes++
            return real.finish(api, session, pairing, options, selectedProfile)
        }
    }

    internal class Fixture private constructor(val file: File, val logicalAddress: String, val peerAddress: String,
        val sshPort: Int, val tlsPort: Int, val sshKeySha256: String, val initialAutoUpdate: Boolean) {
        fun secret(name: String): CharArray {
            val raw = file.readBytes()
            return try {
                Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject[name]!!.jsonPrimitive.content.toCharArray()
            } finally { raw.fill(0) }
        }
        companion object {
            fun load(): Fixture {
                val file = File(requireNotNull(System.getenv("FOXHOLE_ROUTER_LAB_FIXTURE")))
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..4096)
                require((Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
                require(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS) == setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                require(Files.getPosixFilePermissions(file.parentFile.toPath(), NOFOLLOW_LINKS) == setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                val raw = file.readBytes()
                val value = try { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject } finally { raw.fill(0) }
                require(value["ownedEphemeral"]!!.jsonPrimitive.boolean)
                require(value["fixtureId"]!!.jsonPrimitive.content.matches(Regex("[a-f0-9]{32}")))
                require(value["fixtureId"]!!.jsonPrimitive.content == System.getenv("FOXHOLE_ROUTER_LAB_FIXTURE_ID"))
                require(value["logicalAddress"]!!.jsonPrimitive.content == "10.77.0.2")
                val ssh = value["sshForwardPort"]!!.jsonPrimitive.int
                val tls = value["tlsForwardPort"]!!.jsonPrimitive.int
                require(ssh in 20000..65000 && tls in 20000..65000 && ssh != tls)
                val hash = value["sshKeySha256"]!!.jsonPrimitive.content.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val peer = value["guestObservedPeer"]!!.jsonPrimitive.content.also { require(routerLanAddress(it) != null) }
                require(value["newPanelPin"]!!.jsonPrimitive.content.matches(Regex("[0-9]{6}")))
                return Fixture(file, "10.77.0.2", peer, ssh, tls, hash, value["initialAutoUpdate"]!!.jsonPrimitive.boolean)
            }
        }
    }

    /** Only Android network selection is adapted; SSH, TLS, signed installation and HTTP RPC remain real. */
    internal class FixedLabNetwork(private val fixture: Fixture) : RouterWizardTransport, AutoCloseable {
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()

        private val observedPackage = AtomicReference<ByteArray?>()
        fun reportPackageChecks(payload: RouterSetupPayload, proposal: RouterInstallProposal) {
            try {
                val raw = observedPackage.get() ?: return
                val v = routerCompactDocument(raw, 524288)
                val binding = proposal.binding
                val compact = requireNotNull(binding.compact)
                val p = compact.platform
                val expectedBase = buildJsonObject {
                    put("name", p.basePackage.name); put("file", p.basePackage.file)
                    put("sha256", p.basePackage.sha256); put("packageIdentity", p.basePackage.packageIdentity)
                }
                val expectedModules = compact.modules.map { m -> buildJsonObject {
                    put("id", m.protocol.canonical); put("storage", m.storage.name.lowercase()); put("entry", m.entry)
                } }.toSet()
                val expectedPackages = payload.packagesByMode[proposal.storageMode].orEmpty().toSet()
                val actualPackages = v.compactObjects("packages", 1..512).map { x ->
                    RouterPackageIdentity(x.compactText("name"), x.compactText("version"), x.compactFile("file"), x.compactHash("sha256"))
                }
                val checks = linkedMapOf(
                    "schema" to (v.compactLong("schema") == 2L),
                    "kind" to (v.compactText("kind") == "compact-installed-package-v1"),
                    "architecture" to (v.compactText("architecture") == binding.architecture),
                    "catalog" to (v.compactHash("catalogSha256") == binding.selection.catalogSha256),
                    "engineCatalog" to (v.compactHash("engineCatalogSha256") == p.engineCatalog.sha256),
                    "mode" to (v.compactText("mode") == binding.storageMode.name.lowercase()),
                    "includeServer" to (v.compactBoolean("includeServer") == binding.selection.includeServer),
                    "protocols" to (v.compactStrings("protocols", 1..12).toSet() == binding.selection.protocols.map { it.canonical }.toSet()),
                    "targets" to (v.compactStrings("supportedTargets", 1..1).toSet() == p.supportedTargets),
                    "openWrt" to (v.compactStrings("supportedOpenWrt", 1..32).toSet() == p.supportedOpenWrt),
                    "base" to (v["basePackage"] == expectedBase),
                    "modules" to (v.compactObjects("modules", 1..12).toSet() == expectedModules),
                    "server" to (v["server"] == (compact.server ?: JsonNull)),
                    "packages" to (actualPackages.toSet() == expectedPackages && actualPackages.distinct().size == actualPackages.size),
                )
                println("LAB package checks=" + checks.entries.joinToString(",") { "${it.key}:${it.value}" })
            } catch (_: Exception) { println("LAB package checks=decode:false") }
        }

        private var closed = false
        private var freshDiscovery = true
        fun installationCompleted() { freshDiscovery = false }
        override suspend fun inspectDiscoveryIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            // QEMU hostfwd accepts TCP even before a guest listener exists; model that one network boundary.
            if (freshDiscovery) throw ConnectException("owned_guest_not_installed")
            return inspectIdentity(endpoint)
        }
        override fun gateway() = fixture.logicalAddress
        override fun hasPhysicalWifi() = !closed
        private fun current() { check(!closed) }
        private fun socket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                current()
                val remote = endpoint as InetSocketAddress
                require(remote.address.hostAddress == fixture.logicalAddress)
                val port = when (remote.port) { 22 -> fixture.sshPort; 8443 -> fixture.tlsPort; else -> error("Lab endpoint rejected") }
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout)
            }
            override fun connect(endpoint: SocketAddress) = connect(endpoint, 15_000)
            override fun close() { sockets.remove(this); super.close() }
        }.also { sockets += it }
        fun ssh(address: String, key: ByteArray?): RouterSshClient {
            require(address == fixture.logicalAddress)
            val real = RouterSshConnection(RouterEndpoint(address, 22).socketAddress(), "owned-default-setup-lab", key,
                RouterSocketProvider(::socket))
            return object : RouterSshClient by real {
                override fun read(command: RouterReadCommand): ByteArray = try {
                    real.read(command).also {
                        if (command == RouterReadCommand.PACKAGE_MANIFEST) observedPackage.set(it.copyOf())
                        println("LAB SSH read=${command.name} bytes=${it.size}")
                    }
                } catch (failure: Exception) {
                    println("LAB SSH read failure=${command.name} kind=${failure.javaClass.simpleName}")
                    throw failure
                }
                override fun bootstrapEnrollment(): ByteArray = try {
                    real.bootstrapEnrollment().also {
                        val status = runCatching { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject["status"]!!.jsonPrimitive.content }.getOrNull()
                        val label = status?.takeIf { value -> value in setOf("fresh", "already_configured") } ?: "UNKNOWN"
                        println("LAB SSH enrollment bytes=${it.size} status=$label")
                    }
                } catch (failure: Exception) {
                    println("LAB SSH enrollment failure=${failure.javaClass.simpleName}")
                    throw failure
                }
                override fun install(script: RouterVerifiedInstallScript, command: RouterInstallCommand,
                    onProgress: (RouterInstallProgress) -> Unit) {
                    try { real.install(script, command, onProgress) }
                    catch (failure: RouterSshFailure) {
                        println("LAB installer failure: exit=${failure.remoteExitStatus} codes=${failure.codes}")
                        throw failure
                    }
                }
            }
        }
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity {
            current()
            val observed = AtomicReference<X509Certificate?>()
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    require(chain.isNotEmpty() && chain.size <= 8 && chain[0].encoded.size <= 16384)
                    chain[0].checkValidity(); observed.set(chain[0]); throw CertificateException("lab_identity_review")
                }
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
            socket().use { socket ->
                socket.connect(endpoint.socketAddress(), 2000); socket.soTimeout = 3000
                (context.socketFactory.createSocket(socket, endpoint.address, endpoint.port, true) as SSLSocket).use {
                    it.enabledProtocols = it.supportedProtocols.filter { p -> p in setOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
                    try { it.startHandshake() } catch (_: javax.net.ssl.SSLException) { }
                }
            }
            return RouterTlsIdentity(endpoint, sha(requireNotNull(observed.get()).encoded))
        }
        override fun api(identity: RouterTlsIdentity): RouterPanelApi {
            current()
            val trust = RouterPinnedTls(identity)
            val factory = object : SocketFactory() {
                override fun createSocket(): Socket = socket()
                override fun createSocket(h: String, p: Int): Socket = socket().apply { connect(InetSocketAddress(h, p)) }
                override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = createSocket(h, p)
                override fun createSocket(h: InetAddress, p: Int): Socket = createSocket(h.hostAddress!!, p)
                override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = createSocket(h, p)
            }
            val client = OkHttpClient.Builder().socketFactory(factory).sslSocketFactory(trust.socketFactory, trust)
                .hostnameVerifier { host, _ -> host == identity.endpoint.address }
                .dns(Dns { host -> require(host == identity.endpoint.address); listOf(identity.endpoint.socketAddress().address) })
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    current()
                    try { chain.proceed(chain.request()).also { println("LAB management HTTP=${it.code}") } }
                    catch (failure: Exception) {
                        println("LAB management failure=${failure.javaClass.simpleName}")
                        throw failure
                    }
                }.build()
            return RouterPanelApi(identity, client, closeTransport = {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            })
        }
        override fun close() { closed = true; sockets.toList().forEach { it.close() } }
    }

    companion object {
        private const val RELEASE = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6"
        private const val MANIFEST = "b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d"
        private fun clock() = System.nanoTime() / 1_000_000L
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun dataFence(value: JsonObject): String {
            val fields = setOf("settings", "countries", "devices", "sites", "servers", "clients")
            check(value.keys.containsAll(fields))
            val stable = value.filterKeys { it in fields }.toMutableMap()
            stable["desiredServerId"] = value["runtime"]!!.jsonObject["desiredServerId"] ?: JsonNull
            return sha(JsonObject(stable).toString().toByteArray())
        }
    }
}
