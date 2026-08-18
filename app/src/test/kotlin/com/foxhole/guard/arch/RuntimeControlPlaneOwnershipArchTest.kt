package com.foxhole.guard.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RuntimeControlPlaneOwnershipArchTest {
    @Test
    fun `vpn and proxy services share one application scoped native control plane`() {
        val supervisorSites = constructionSites("RuntimeSupervisor")
        val storeSites = constructionSites("RuntimeInstanceStore")

        assertEquals(
            "expected exactly one RuntimeSupervisor construction site (the application graph); found: $supervisorSites",
            1,
            supervisorSites.size,
        )
        assertEquals(
            "expected exactly one RuntimeInstanceStore construction site (the application graph); found: $storeSites",
            1,
            storeSites.size,
        )

        val ownerFiles = (supervisorSites + storeSites).map(ConstructionSite::file).toSet()
        assertEquals(
            "the supervisor and the instance store must be constructed by the same application-scoped graph file; " +
                "found: $ownerFiles",
            1,
            ownerFiles.size,
        )
        assertFalse(
            "the shared native control plane must not be constructed inside an Android service " +
                "(services borrow it from the application graph): ${ownerFiles.single()}",
            declaresAndroidService(ownerFiles.single()),
        )
    }

    @Test
    fun `services never close the shared supervisor or clear all runtime ownership`() {
        assertNoMatches(
            pattern = Regex("""runtimeSupervisor\s*\.\s*close\s*\("""),
            files = appMainKotlinFiles(),
            message =
            "Services borrow the application-scoped supervisor; closing it kills the other " +
                "mode's command queue.",
        )
        assertNoMatches(
            pattern = Regex("""clearRuntimeOwnership\s*\(\s*\)"""),
            files = appMainKotlinFiles(),
            message =
            "Clearing ownership without a mode wipes the other service's control-plane state " +
                "during a cross-mode handoff; always clear for the service's own traffic mode.",
        )
    }

    @Test
    fun `app code never constructs control plane ownership state`() {
        assertNoMatches(
            pattern = Regex("""RuntimeControlPlaneOwnershipState\s*\("""),
            files = appMainKotlinFiles(),
            message =
            "Control-plane ownership state is owned by the supervisor in :core:runtime; app " +
                "code must read it through the supervisor, never mint its own copy.",
        )
    }

    @Test
    fun `service dispatched runtime commands carry a command owner`() {
        val callPattern = Regex("""runtimeSupervisor\s*\.\s*(launch|dispatch)\s*\(""")
        val offenders =
            appMainKotlinFiles().flatMap { file ->
                val text = file.readText()
                callPattern
                    .findAll(text)
                    .mapNotNull { match ->
                        val arguments = balancedParenBlock(text, openParenIndex = match.range.last)
                        if (Regex("""\bowner\s*=""").containsMatchIn(arguments)) {
                            null
                        } else {
                            "${file.relativeProjectPath()}:${lineNumberAt(text, match.range.first)}"
                        }
                    }.toList()
            }

        assertEquals(
            "Service-side supervisor dispatches must name an owner so closing the service's " +
                "command owner cancels its queued runtime work.",
            emptyList<String>(),
            offenders,
        )
    }

    private data class ConstructionSite(
        val file: String,
        val line: Int,
    ) {
        override fun toString(): String = "$file:$line"
    }

    private fun constructionSites(typeName: String): List<ConstructionSite> {
        val pattern = Regex("""(?<!class )\b$typeName\s*[({]""")
        return mainKotlinFiles().flatMap { file ->
            val text = file.readText()
            pattern
                .findAll(text)
                .map { match ->
                    ConstructionSite(
                        file = file.relativeProjectPath(),
                        line = lineNumberAt(text, match.range.first),
                    )
                }.toList()
        }
    }

    private fun declaresAndroidService(relativePath: String): Boolean =
        Regex("""class\s+\w+\s*:\s*(\w+\.)*\w*Service\s*\(""")
            .containsMatchIn(repoRoot().resolve(relativePath).readText())

    private fun assertNoMatches(
        pattern: Regex,
        files: List<File>,
        message: String,
    ) {
        val offenders =
            files.flatMap { file ->
                file
                    .readLines()
                    .mapIndexedNotNull { index, line ->
                        if (pattern.containsMatchIn(line)) {
                            "${file.relativeProjectPath()}:${index + 1}:$line"
                        } else {
                            null
                        }
                    }
            }

        assertEquals("$message\n${offenders.joinToString(separator = "\n")}", emptyList<String>(), offenders)
    }

    private fun balancedParenBlock(
        text: String,
        openParenIndex: Int,
    ): String {
        var depth = 0
        for (index in openParenIndex until text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(openParenIndex, index + 1)
                    }
                }
            }
        }
        return text.substring(openParenIndex)
    }

    private fun lineNumberAt(
        text: String,
        index: Int,
    ): Int = text.take(index).count { char -> char == '\n' } + 1

    private fun appMainKotlinFiles(): List<File> = kotlinFilesUnder("app/src/main/kotlin")

    private fun mainKotlinFiles(): List<File> =
        kotlinFilesUnder("app/src/main/kotlin") + kotlinFilesUnder("core/runtime/src/main/kotlin")

    private fun kotlinFilesUnder(root: String): List<File> =
        repoRoot()
            .resolve(root)
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }

    private fun File.relativeProjectPath(): String = relativeTo(repoRoot()).invariantSeparatorsPath
}
