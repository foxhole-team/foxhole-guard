package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StringResourceParityTest {
    private val translatedQualifiers = listOf("values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN")
    private val allQualifiers = listOf("values") + translatedQualifiers
    private val stringName = Regex("""<string\s+[^>]*name="([^"]+)"""")
    private val stringEntry =
        Regex(
            """<string\s+[^>]*name="([^"]+)"[^>]*>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    private val formatPlaceholder =
        Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
    private val incompleteFoxHoleBrand = Regex("""\bFoxHole\b(?! (?:Guard|Sentinel|Core|DB|Team|mirror))""")
    private val bareSentinelBrand = Regex("""(?<!FoxHole )\bSentinel\b""")
    private val bareSentinelAllowedKeys = setOf("cli_foxdb_group_security")
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
        translatedQualifiers.forEach { qualifier ->
            assertEquals("String keys differ in $qualifier", default.sorted(), names(qualifier).sorted())
        }
    }

    @Test
    fun `no catalogue declares the same key twice`() {
        allQualifiers.forEach { qualifier ->
            val all = allNames(qualifier)
            val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

            assertEquals("Duplicate keys in $qualifier/strings.xml", emptyList<String>(), duplicates)
        }
    }

    @Test
    fun `translated strings preserve format placeholders`() {
        val default = entries("values")
        translatedQualifiers.forEach { qualifier ->
            val translated = entries(qualifier)
            default.keys.sorted().forEach { key ->
                assertEquals(
                    "Format placeholders differ for $qualifier/$key",
                    placeholders(default.getValue(key)),
                    placeholders(translated.getValue(key)),
                )
            }
        }
    }

    @Test
    fun `visible strings use complete FoxHole product names`() {
        allQualifiers.forEach { qualifier ->
            entries(qualifier).forEach { (key, value) ->
                check(!incompleteFoxHoleBrand.containsMatchIn(value)) {
                    "$qualifier/$key contains a shortened FoxHole product name"
                }
                if (key !in bareSentinelAllowedKeys) {
                    check(!bareSentinelBrand.containsMatchIn(value)) {
                        "$qualifier/$key contains bare Sentinel instead of FoxHole Sentinel"
                    }
                }
            }
        }
    }

    @Test
    fun `translated strings preserve explicit line structure`() {
        val default = entries("values")
        translatedQualifiers.forEach { qualifier ->
            val translated = entries(qualifier)
            default.keys.sorted().forEach { key ->
                assertEquals(
                    "Explicit newline count differs for $qualifier/$key",
                    default.getValue(key).windowed(2).count { it == "\\n" },
                    translated.getValue(key).windowed(2).count { it == "\\n" },
                )
            }
        }
    }

    @Test
    fun `plural catalogues and placeholder signatures stay compatible`() {
        val default = plurals("values")
        translatedQualifiers.forEach { qualifier ->
            val translated = plurals(qualifier)
            assertEquals(default.keys.sorted(), translated.keys.sorted())
            default.forEach { (key, defaultQuantities) ->
                val quantities = translated.getValue(key)
                assertEquals(true, quantities.containsKey("other"))
                quantities.forEach { (quantity, value) ->
                    assertEquals(
                        "Plural placeholders differ for $qualifier/$key/$quantity",
                        placeholders(defaultQuantities[quantity] ?: defaultQuantities.getValue("other")),
                        placeholders(value),
                    )
                }
            }
        }
    }

    @Test
    fun `language names remain readable in every selected language`() {
        val endonyms = mapOf(
            "ru" to "русский", "en" to "english", "ja" to "日本語", "de" to "Deutsch",
            "fr" to "français", "es" to "español", "zh" to "简体中文",
        )
        allQualifiers.forEach { qualifier ->
            val translated = entries(qualifier)
            endonyms.forEach { (tag, label) ->
                assertEquals("Endonym differs in $qualifier", label, translated.getValue("cli_cfg_locale_$tag"))
            }
        }
    }

    @Test
    fun `runtime errors and widget actions have matching locale keys and placeholders`() {
        listOf(
            "app/src/main/res" to "widget_action_strings.xml",
            "core/runtime/src/main/res" to "strings.xml",
        ).forEach { (directory, filename) ->
            fun catalogue(qualifier: String) = stringEntry.findAll(
                repoRoot().resolve("$directory/$qualifier/$filename").readText(),
            ).associate { it.groupValues[1] to it.groupValues[2] }
            val default = catalogue("values")
            translatedQualifiers.forEach { qualifier ->
                val translated = catalogue(qualifier)
                assertEquals("Keys differ for $directory/$qualifier", default.keys.sorted(), translated.keys.sorted())
                default.forEach { (key, value) ->
                    assertEquals(placeholders(value), placeholders(translated.getValue(key)))
                }
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
