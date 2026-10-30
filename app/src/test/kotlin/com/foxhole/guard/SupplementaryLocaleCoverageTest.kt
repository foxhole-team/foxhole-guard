package com.foxhole.guard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

internal class SupplementaryLocaleCoverageTest {
    private val qualifiers = listOf("values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN")
    private val formatPlaceholder =
        Regex("""%(?:[1-9]\d*\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaA%n])""")
    private val explicitEscape = Regex("""\\+n|\\+r|\\+t""")

    @Test
    fun everyTranslatableStringAcrossAllValuesFilesHasCompleteLocaleCoverage() {
        listOf("app/src/main/res", "core/runtime/src/main/res").forEach { relative ->
            val root = repoRoot().resolve(relative)
            val defaults = strings(root.resolve("values"))
            assertTrue("No default strings in $relative", defaults.isNotEmpty())
            qualifiers.forEach { qualifier ->
                val translated = strings(root.resolve(qualifier))
                assertEquals("String keys differ for $relative/$qualifier", defaults.keys.sorted(), translated.keys.sorted())
                defaults.forEach { (name, value) ->
                    val localized = translated.getValue(name)
                    assertTrue("Blank string in $relative/$qualifier/$name", localized.isNotBlank())
                    assertEquals(
                        "Format differs for $relative/$qualifier/$name",
                        formatPlaceholder.findAll(value).map { it.value }.sorted().toList(),
                        formatPlaceholder.findAll(localized).map { it.value }.sorted().toList(),
                    )
                    assertEquals(
                        "Explicit escapes differ for $relative/$qualifier/$name",
                        explicitEscape.findAll(value).map { it.value }.toList(),
                        explicitEscape.findAll(localized).map { it.value }.toList(),
                    )
                }
            }
        }
    }

    private fun strings(directory: File): Map<String, String> {
        check(directory.isDirectory) { "Missing locale directory: $directory" }
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }.sortedBy { it.name }
        check(files.isNotEmpty()) { "No resource XML files in $directory" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val result = linkedMapOf<String, String>()
        files.forEach { file ->
            val document = factory.newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                check(name.isNotBlank()) { "Unnamed string in $file" }
                check(result.put(name, element.textContent) == null) { "Duplicate string $name in $directory" }
            }
        }
        return result
    }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { directory -> directory.resolve("settings.gradle.kts").isFile }
}
