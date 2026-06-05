package com.foxhole.beta.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeStaticSafetyGuardTest {
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
            pattern = Regex("""FoxholeVpnRuntimeBridge\.updateIpInfo\(null\)"""),
            allowlistedPaths = setOf("app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelRuntimeSupport.kt"),
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
        val supervisor = projectFile("src/main/kotlin/com/foxhole/beta/vpn/RuntimeSupervisor.kt").readText()

        assertTrue(supervisor.contains("RuntimeStateMachine("))
        assertTrue(supervisor.contains("stateMachine.dispatch(event)"))
        assertTrue(supervisor.contains("stateMachine.beginTransition("))
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
        val source = projectFile("src/main/kotlin/com/foxhole/beta/vpn/RuntimeStopSupport.kt").readText()
        val closeBlock =
            source.between(
                "internal suspend fun runBlockingRuntimeClose",
                "internal suspend fun VpnCoreRuntime.startFailClosed",
            )
        val startBlock =
            source.between(
                "internal suspend fun VpnCoreRuntime.startFailClosed",
                "internal suspend fun VpnCoreRuntime.stopFailClosed",
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
    fun `local guard fallback reachability probes are bound to candidate vpn network`() {
        val source = projectFile("src/main/kotlin/com/foxhole/beta/vpn/FoxholeVpnService.kt").readText()
        val fallbackBlock =
            source.between(
                "private suspend fun probeLocalGuardFallbackReachability(",
                "private suspend fun handleLocalGuardPreflight(",
            )

        assertTrue(fallbackBlock.contains("network = network"))
        assertTrue(fallbackBlock.contains("resolverNetwork = network"))
        assertTrue(fallbackBlock.contains("network.getAllByName(LOCAL_GUARD_CONNECTIVITY_DNS_PROBE_HOST)"))
        assertFalse(fallbackBlock.contains("InetAddress.getAllByName(LOCAL_GUARD_CONNECTIVITY_DNS_PROBE_HOST)"))
    }

    @Test
    fun `foreground service starts are fenced through safe launchers`() {
        val contract =
            projectFile("src/main/kotlin/com/foxhole/beta/vpn/FoxholeConnectionServiceContract.kt").readText()
        val runtimeHandler =
            projectFile("src/main/kotlin/com/foxhole/beta/vpn/RuntimeCommandHandler.kt").readText()
        val publicStartBlock =
            contract.between(
                "fun startForegroundService(",
                "fun stopInactiveServices(",
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
    fun `foreground service special use subtypes stay descriptive`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains(
                "Maintains a user-initiated VPN tunnel or local firewall guard so selected apps and DNS traffic " +
                    "remain protected while Foxhole is not in the foreground.",
            ),
        )
        assertTrue(
            manifest.contains(
                "Maintains a user-initiated local proxy runtime so the user's explicitly configured client apps " +
                    "can route traffic through the selected profile while Foxhole is not in the foreground.",
            ),
        )
        assertFalse(manifest.contains("android:value=\"vpn\""))
        assertFalse(manifest.contains("android:value=\"proxy\""))
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

    private fun mainKotlinFiles(): List<File> =
        projectFile("src/main/kotlin")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }

    private fun File.relativeProjectPath(): String {
        val normalized = invariantSeparatorsPath
        val appIndex = normalized.indexOf("app/src/")
        return when {
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
                "app/src/main/kotlin/com/foxhole/beta/ui/HomeViewModelRuntimeSupport.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/BootReceiver.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeConnectionController.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeConnectionLifecycle.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeProxyService.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeTileService.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeVpnService.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/FoxholeVpnServiceHealthSupport.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeCommandHandler.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeNotificationCoordinator.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeProxyEgressValidationSupport.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeReconnectCoordinator.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeValidationCoordinator.kt",
            )
        val RUNTIME_STATE_OWNER_ALLOWLIST =
            setOf(
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeStateReducer.kt",
                "app/src/main/kotlin/com/foxhole/beta/vpn/RuntimeStateMachine.kt",
            )
    }
}
