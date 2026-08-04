package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app ships exactly two string catalogues: `values/` is the English default and `values-ru/` is
 * the Russian translation. Nothing enforced that they stay in step — lint's `UnusedResources` is not
 * raised to an error and a missing translation only shows up as English text leaking into a Russian
 * screen at runtime. Every UI pass in this project has drifted them apart at least once, so the
 * parity is pinned here instead.
 */
class StringResourceParityTest {
    private val stringName = Regex("""<string\s+[^>]*name="([^"]+)"""")

    @Test
    fun `every default string is translated and every translation has a default`() {
        val default = names("values")
        val russian = names("values-ru")

        assertEquals(
            "Keys present in values/ but missing from values-ru/ — add the Russian translation.",
            emptyList<String>(),
            (default - russian).sorted(),
        )
        assertEquals(
            "Keys present in values-ru/ but missing from values/ — orphaned after a rename or " +
                "deletion; remove them or restore the English default.",
            emptyList<String>(),
            (russian - default).sorted(),
        )
    }

    @Test
    fun `no catalogue declares the same key twice`() {
        listOf("values", "values-ru").forEach { qualifier ->
            val all = allNames(qualifier)
            val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

            assertEquals("Duplicate keys in $qualifier/strings.xml", emptyList<String>(), duplicates)
        }
    }

    private fun names(qualifier: String): Set<String> = allNames(qualifier).toSet()

    private fun allNames(qualifier: String): List<String> =
        stringName
            .findAll(stringsFile(qualifier).readText())
            .map { match -> match.groupValues[1] }
            .toList()

    private fun stringsFile(qualifier: String): File =
        repoRoot().resolve("app/src/main/res/$qualifier/strings.xml").also { file ->
            check(file.isFile) { "Missing string catalogue: $file" }
        }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }
}
