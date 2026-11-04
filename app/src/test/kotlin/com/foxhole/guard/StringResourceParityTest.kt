package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

class StringResourceParityTest {
    private val translatedQualifiers = listOf("values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar")
    private val allQualifiers = listOf("values") + translatedQualifiers
    private val stringEntry =
        Regex(
            """<string\s+[^>]*name="([^"]+)"[^>]*>(.*?)</string>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    private val formatPlaceholder =
        Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
    private val incompleteFoxHoleBrand = Regex("""\bFoxHole\b(?! (?:Guard|Sentinel|Core|DB|Team|mirror|VPN\b|OpenWrt Client\b))""")
    private val bareSentinelBrand = Regex("""(?<!FoxHole )\bSentinel\b""")
    private val umbrellaFoxHoleAllowedKeys = setOf("router_table_version")
    private val bareSentinelAllowedKeys = setOf(
        "cli_foxdb_group_security",
        "cli_logs_tab_sentinel",
        "cli_sentinel_module_history_note",
        "cli_status_sentinel_notices",
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

            assertEquals("Duplicate keys in $qualifier XML catalogues", emptyList<String>(), duplicates)
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
            declarations(qualifier, translatableOnly = false).forEach { (key, value) ->
                if (!key.startsWith("guard_router_") && key !in umbrellaFoxHoleAllowedKeys) {
                    check(!incompleteFoxHoleBrand.containsMatchIn(value)) {
                        "$qualifier/$key contains a shortened FoxHole product name"
                    }
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
            "fr" to "français", "es" to "español", "zh" to "简体中文", "ar" to "العربية",
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

    private fun names(qualifier: String): Set<String> = declarations(qualifier).map { it.first }.toSet()

    private fun allNames(qualifier: String): List<String> =
        declarations(qualifier, translatableOnly = false).map { it.first }

    private fun entries(qualifier: String): Map<String, String> = buildMap {
        declarations(qualifier).forEach { (name, value) ->
            check(put(name, value) == null) { "Duplicate string $name in $qualifier" }
        }
    }

    private fun declarations(qualifier: String, translatableOnly: Boolean = true): List<Pair<String, String>> = buildList {
        catalogues(qualifier).forEach { document ->
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (translatableOnly && element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                check(name.isNotBlank()) { "Unnamed string in $qualifier" }
                add(name to element.textContent)
            }
        }
    }

    private fun placeholders(value: String): List<String> =
        formatPlaceholder
            .findAll(value)
            .map { match -> match.value }
            .filterNot { placeholder -> placeholder == "%%" }
            .sorted()
            .toList()

    private fun plurals(qualifier: String): Map<String, Map<String, String>> = buildMap {
        catalogues(qualifier).forEach { document ->
            val nodes = document.getElementsByTagName("plurals")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                val quantities = buildMap<String, String> {
                    val items = element.getElementsByTagName("item")
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        val quantity = item.getAttribute("quantity")
                        check(put(quantity, item.textContent) == null) { "Duplicate quantity $name/$quantity in $qualifier" }
                    }
                }
                check(put(name, quantities) == null) { "Duplicate plural $name in $qualifier" }
            }
        }
    }

    private fun catalogues(qualifier: String): List<Document> {
        val directory = repoRoot().resolve("app/src/main/res/$qualifier")
        check(directory.isDirectory) { "Missing locale directory: $directory" }
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }.sortedBy { it.name }
        check(files.isNotEmpty()) { "No XML catalogues in $directory" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return files.map { factory.newDocumentBuilder().parse(it) }
    }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }
}
