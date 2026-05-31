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
    }
}
