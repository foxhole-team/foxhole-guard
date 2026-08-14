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
    private val stringEntry =
        Regex(
            """<string\s+[^>]*name="([^"]+)"[^>]*>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    private val formatPlaceholder =
        Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
    private val incompleteFoxHoleBrand = Regex("""\bFoxHole\b(?! (?:Guard|Sentinel|Core|DB|Team))""")
    private val bareSentinelBrand = Regex("""(?<!FoxHole )\bSentinel\b""")
    private val pluralEntry =
        Regex(
            """<plurals\s+[^>]*name="([^"]+)"[^>]*>(.*?)</plurals>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    private val pluralItem =
        Regex(
            """<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

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

    @Test
    fun `translated strings preserve format placeholders`() {
        val default = entries("values")
        val russian = entries("values-ru")

        default.keys.sorted().forEach { key ->
            assertEquals(
                "Format placeholders differ for $key",
                placeholders(default.getValue(key)),
                placeholders(russian.getValue(key)),
            )
        }
    }

    @Test
    fun `visible strings use complete FoxHole product names`() {
        listOf("values", "values-ru").forEach { qualifier ->
            entries(qualifier).forEach { (key, value) ->
                check(!incompleteFoxHoleBrand.containsMatchIn(value)) {
                    "$qualifier/$key contains a shortened FoxHole product name"
                }
                check(!bareSentinelBrand.containsMatchIn(value)) {
                    "$qualifier/$key contains bare Sentinel instead of FoxHole Sentinel"
                }
            }
        }
    }

    @Test
    fun `translated strings preserve explicit line structure`() {
        val default = entries("values")
        val russian = entries("values-ru")

        default.keys.sorted().forEach { key ->
            assertEquals(
                "Explicit newline count differs for $key",
                default.getValue(key).windowed(2).count { it == "\\n" },
                russian.getValue(key).windowed(2).count { it == "\\n" },
            )
        }
    }

    @Test
    fun `plural catalogues and placeholder signatures stay compatible`() {
        val default = plurals("values")
        val russian = plurals("values-ru")
        assertEquals(default.keys.sorted(), russian.keys.sorted())

        default.forEach { (key, defaultQuantities) ->
            val russianQuantities = russian.getValue(key)
            listOf("one", "other").forEach { quantity ->
                assertEquals(
                    "Plural placeholders differ for $key/$quantity",
                    placeholders(defaultQuantities.getValue(quantity)),
                    placeholders(russianQuantities.getValue(quantity)),
                )
            }
            russianQuantities.values.forEach { value ->
                assertEquals(
                    "Russian plural variants for $key must use one placeholder signature",
                    placeholders(russianQuantities.getValue("other")),
                    placeholders(value),
                )
            }
        }
    }

    private fun names(qualifier: String): Set<String> = allNames(qualifier).toSet()

    private fun allNames(qualifier: String): List<String> =
        stringName
            .findAll(stringsFile(qualifier).readText())
            .map { match -> match.groupValues[1] }
            .toList()

    private fun entries(qualifier: String): Map<String, String> =
        stringEntry
            .findAll(stringsFile(qualifier).readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }

    private fun placeholders(value: String): List<String> =
        formatPlaceholder
            .findAll(value)
            .map { match -> match.value }
            .filterNot { placeholder -> placeholder == "%%" }
            .sorted()
            .toList()

    private fun plurals(qualifier: String): Map<String, Map<String, String>> =
        pluralEntry
            .findAll(stringsFile(qualifier).readText())
            .associate { match ->
                match.groupValues[1] to
                    pluralItem
                        .findAll(match.groupValues[2])
                        .associate { item -> item.groupValues[1] to item.groupValues[2] }
            }

    private fun stringsFile(qualifier: String): File =
        repoRoot().resolve("app/src/main/res/$qualifier/strings.xml").also { file ->
            check(file.isFile) { "Missing string catalogue: $file" }
        }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }
}
