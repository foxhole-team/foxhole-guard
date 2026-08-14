package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeStaticSafetyGuardTest {
    @Test
    fun `allowlist entries point at files that exist`() {
        // A moved or renamed file must be re-triaged, not silently exempted forever: dead
        // allowlist entries are exactly how this guard rotted after the :core:runtime move.
        val missing =
            (LEGACY_BRIDGE_ALLOWLIST + RUNTIME_STATE_OWNER_ALLOWLIST)
                .filterNot { path -> repoRoot().resolve(path).isFile }

        assertEquals("Stale allowlist entries — update the path or delete the exemption.", emptyList<String>(), missing)
    }

    @Test
    fun `runtime code does not force unwrap Android system services`() {
        assertNoMatches(
            pattern = Regex("""getSystemService<[^>]+>\(\)!!"""),
            message = "Use requireSystemServiceSafe(...) so missing services include diagnostic context.",
        )
    }

    @Test
    fun `runtime code does not clear tunnel IP through legacy bridge null publication`() {
        assertNoMatches(
            pattern = Regex("""(FoxholeVpnRuntimeBridge|bridgeWriter)\.updateIpInfo\(null\)"""),
            allowlistedPaths = setOf("app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelIpRefreshSupport.kt"),
            message = "Do not clear runtime IP to null from connect, reload, or service paths.",
        )
    }

    @Test
    fun `direct legacy bridge usage remains fenced`() {
        val offenders =
            mainKotlinFiles()
                .filter { file -> "FoxholeVpnRuntimeBridge" in file.readText() }
                .map { file -> file.relativeProjectPath() }
                .filterNot(LEGACY_BRIDGE_ALLOWLIST::contains)

        assertEquals(
            "New direct bridge dependencies must go through RuntimeStateStore or the legacy migration adapter.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `runtime supervisor delegates state ownership to state machine`() {
        val supervisor = projectFile("src/main/kotlin/com/foxhole/core/runtime/RuntimeSupervisor.kt").readText()

        assertTrue(supervisor.contains("RuntimeStateMachine("))
        assertTrue(supervisor.contains("stateMachine.dispatch(event)"))
        // Ф3c: transitions mint from the shared clock and are adopted by the state machine —
        // the supervisor still never owns generation state itself.
        assertTrue(supervisor.contains("stateMachine.adoptTransition("))
        assertFalse(supervisor.contains("RuntimeStateStore("))
        assertFalse(supervisor.contains("AtomicLong"))
    }

    @Test
    fun `services do not instantiate runtime state stores directly`() {
        val offenders =
            mainKotlinFiles()
                .filter { file -> file.relativeProjectPath() !in RUNTIME_STATE_OWNER_ALLOWLIST }
                .flatMap { file ->
                    file
                        .readLines()
                        .mapIndexedNotNull { index, line ->
                            if ("RuntimeStateStore(" in line) {
                                "${file.relativeProjectPath()}:${index + 1}:$line"
                            } else {
                                null
                            }
                        }
                }

        assertEquals(
            "Runtime state store construction must stay behind RuntimeStateMachine.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `native runtime start and close helpers do not create detached coroutine scopes`() {
        val source = projectFile("src/main/kotlin/com/foxhole/core/runtime/RuntimeStopSupport.kt").readText()
        val closeBlock =
            source.between(
                "suspend fun runBlockingRuntimeClose",
                "suspend fun FoxholeRuntime.startFailClosed",
            )
        val startBlock =
            source.between(
                "suspend fun FoxholeRuntime.startFailClosed",
                "suspend fun FoxholeRuntime.stopFailClosed",
            )

        assertFalse(
            "runBlockingRuntimeClose must not create detached CoroutineScope instances.",
            closeBlock.contains("CoroutineScope("),
        )
        assertFalse(
            "startFailClosed must not create detached CoroutineScope instances.",
            startBlock.contains("CoroutineScope("),
        )
    }

    @Test
    fun `runtime service code does not bypass fail closed stop policy`() {
        assertNoMatches(
            pattern = Regex("""\bruntime\.stop\("""),
            message = "Use stopRuntimeFailClosed or stopFailClosed so native runtime cleanup can timeout and escalate.",
        )
    }

    @Test
    fun `local guard fallback reachability probes are bound to candidate vpn network`() {
        val source =
            projectFile("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLocalGuardSupport.kt").readText()
        val fallbackBlock =
            source.between(
                "private suspend fun FoxholeVpnService.probeLocalGuardFallbackReachability(",
                "private suspend fun FoxholeVpnService.handleLocalGuardPreflight(",
            )

        assertTrue(fallbackBlock.contains("network = network"))
        assertTrue(fallbackBlock.contains("resolverNetwork = network"))
        // The DNS probe must be bound to the candidate vpn network, not the unbound platform resolver.
        assertTrue(fallbackBlock.contains("network.getAllByName("))
        assertFalse(fallbackBlock.contains("InetAddress.getAllByName("))
    }

    @Test
    fun `foreground service starts are fenced through safe launchers`() {
        val contract =
            projectFile("src/main/kotlin/com/foxhole/guard/runtime/FoxholeConnectionServiceContract.kt").readText()
        val runtimeHandler =
            projectFile("src/main/kotlin/com/foxhole/guard/runtime/RuntimeServiceCommandSupport.kt").readText()
        val publicStartBlock =
            contract.between(
                "fun startForegroundService(",
                "private val serviceLive",
            )

        assertEquals(1, Regex("""ContextCompat\.startForegroundService""").findAll(contract).count())
        assertFalse(publicStartBlock.contains("ContextCompat.startForegroundService"))
        assertTrue(publicStartBlock.contains("startForegroundServiceSafely("))
        assertTrue(contract.contains("FOREGROUND_SERVICE_START_NOT_ALLOWED_EXCEPTION"))
        assertTrue(contract.contains("SecurityException -> ForegroundServiceStartBlockReason.SECURITY"))
        assertTrue(contract.contains("IllegalStateException -> ForegroundServiceStartBlockReason.ILLEGAL_STATE"))
        assertTrue(runtimeHandler.contains("startForegroundRuntimeSafely("))
        assertTrue(runtimeHandler.contains("foregroundServiceStartBlockReason(error) ?: throw error"))
        assertTrue(runtimeHandler.contains("publishForegroundRuntimeStartBlockedSnapshot("))
        assertTrue(runtimeHandler.contains("Service.START_NOT_STICKY"))
    }

    @Test
    fun `the single network service has the vpn foreground role`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        // Every runtime service uses specialUse with an explicit subtype. systemExempted is
        // banned on purpose: its runtime eligibility rides the VPN consent app-op, so a
        // boot-restore or firewall start after consent revocation would crash with
        // SecurityException instead of reaching the foreground-start-blocked handler.
        assertFalse(manifest.contains("android:foregroundServiceType=\"systemExempted\""))
        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(
            manifest.contains(
                "Runs the user-initiated VPN/firewall tunnel (VpnService) and keeps it alive while " +
                    "the user's traffic is routed or filtered through the selected profile.",
            ),
        )
        assertFalse(manifest.contains("FoxholeProxyService"))
        assertFalse(manifest.contains("android:value=\"vpn\""))
        assertFalse(manifest.contains("android:value=\"proxy\""))
    }

    @Test
    fun `geoip database is downloaded from foxhole db, never bundled again`() {
        val resolver =
            projectFile("src/main/kotlin/com/foxhole/core/runtime/TorGeoIpCountryResolver.kt").readText()
        val store =
            projectFile("src/main/kotlin/com/foxhole/core/runtime/GeoIpDatabaseStore.kt").readText()

        // The geo database moved into the FoxHole DB feed (downloaded on demand): bundling it
        // again would put ~24 MB of ranges back into every APK, so its absence IS the contract.
        assertFalse(File("app/src/main/assets/geoip/geoip").exists())
        assertFalse(File("../app/src/main/assets/geoip/geoip").exists())
        // The downloaded override files stay the architecture-neutral source both readers share.
        assertTrue(resolver.contains("overrideIpv4File"))
        assertTrue(resolver.contains("overrideIpv6File"))
        assertTrue(store.contains("overrideIpv4File"))
    }

    private fun assertNoMatches(
        pattern: Regex,
        allowlistedPaths: Set<String> = emptySet(),
        message: String,
    ) {
        val offenders =
            mainKotlinFiles()
                .flatMap { file ->
                    file
                        .readLines()
                        .mapIndexedNotNull { index, line ->
                            val relativePath = file.relativeProjectPath()
                            if (relativePath in allowlistedPaths || !pattern.containsMatchIn(line)) {
                                null
                            } else {
                                "$relativePath:${index + 1}:$line"
                            }
                        }
                }

        assertTrue(
            "$message\n${offenders.joinToString(separator = "\n")}",
            offenders.isEmpty(),
        )
    }

    // The fleet-wide bans police BOTH runtime source roots. Scanning only app/src/main silently
    // exempted the engine after its move to :core:runtime (the guards' primary subject).
    private fun mainKotlinFiles(): List<File> =
        listOf(moduleRoot("app"), moduleRoot("core/runtime"))
            .flatMap { root ->
                root.resolve("src/main/kotlin")
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
            }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }

    private fun moduleRoot(module: String): File = repoRoot().resolve(module)

    private fun projectFile(path: String): File =
        listOf(
            File(path),
            File("app/$path"),
            File("../app/$path"),
            File("../core/runtime/$path"),
            File("core/runtime/$path")
        )
            .first { file -> file.exists() }

    private fun File.relativeProjectPath(): String {
        val normalized = invariantSeparatorsPath
        val appIndex = normalized.indexOf("app/src/")
        val coreRuntimeIndex = normalized.indexOf("core/runtime/src/")
        return when {
            coreRuntimeIndex >= 0 -> normalized.substring(coreRuntimeIndex)
            appIndex >= 0 -> normalized.substring(appIndex)
            normalized.startsWith("src/") -> "app/$normalized"
            else -> normalized
        }
    }

    private fun String.between(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "Could not isolate source block $start -> $end" }
        return substring(startIndex, endIndex)
    }

    private companion object {
        val LEGACY_BRIDGE_ALLOWLIST =
            setOf(
                "app/src/main/kotlin/com/foxhole/guard/FoxholeApplication.kt",
                // Web apps: вотчдог/виджет/суб-экран читают снапшот (и виджет шлёт тот же
                // оптимистичный апдейт, что тайл) — сознательно по образцу FoxholeTileService.
                "app/src/main/kotlin/com/foxhole/guard/core/webapps/WebAppsWatchdog.kt",
                "app/src/main/kotlin/com/foxhole/guard/widget/StatusWidget.kt",
                "app/src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliWebAppsSubScreen.kt",
                "app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelIpRefreshSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/ui/HomeViewModelInstalledAppsSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/BootReceiver.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeConnectionController.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeConnectionControllerReconcileSupport.kt",
                "core/runtime/src/main/kotlin/com/foxhole/core/runtime/FoxholeVpnRuntimeBridge.kt",
                // Pre-existing phase publishers surfaced when the scan regained core/runtime
                // coverage — queued for the single-owner supervisor rewrite, not new usages.
                "core/runtime/src/main/kotlin/com/foxhole/core/runtime/I2pdManager.kt",
                // LAN proxy: the same shape as I2pdManager above — a phase PUBLISHER, not a reader.
                // The controller writes what the core reported; the service support reads the
                // connection snapshot exactly like its FoxholeVpnService*Support siblings below.
                // Every consumer of this state goes through FoxholeConnectionController.
                "core/runtime/src/main/kotlin/com/foxhole/core/runtime/LanProxyRuntime.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLanProxySupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeConnectionLifecycle.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeTileService.kt",
                // Read-only bridge consumer, same class as the tile/widget above.
                "app/src/main/kotlin/com/foxhole/guard/runtime/ConnectionNotificationAction.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnService.kt",
                // Mechanical extraction of the service-owned callbacks below. The owner reads the
                // same legacy snapshot at the same network edges; it does not mint runtime state.
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceNetworkCallbackOwner.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceRuntimePolicies.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceFailureAndBridgeSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceChildWatchdogSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceConnectSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceHealthSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceIpRefreshSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLocalGuardSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLocalGuardHealSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceNetworkCallbacksSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceReloadSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTeardownSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTrafficSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeProxyEgressValidationSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeReconnectCoordinator.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeServiceCommandSupport.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationCoordinator.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationIpRefresh.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationProbes.kt",
                "app/src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt",
            )
        val RUNTIME_STATE_OWNER_ALLOWLIST =
            setOf(
                "core/runtime/src/main/kotlin/com/foxhole/core/runtime/RuntimeStateMachine.kt",
            )
    }
}
