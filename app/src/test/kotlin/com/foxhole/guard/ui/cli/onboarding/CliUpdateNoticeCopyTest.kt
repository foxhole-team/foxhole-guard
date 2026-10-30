package com.foxhole.guard.ui.cli.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CliUpdateNoticeCopyTest {
    @Test fun noticeKeepsConciseSummariesAndTheExactOwnerApprovedRouterText() {
        val routerText = mapOf(
            "values-ru" to "Поддержка установки и управления VPN на роутере с установленным пакетом FoxHole OpenWrt Client прямо из приложения.",
            "values" to "Install and manage VPN on a router running FoxHole OpenWrt Client, directly from the app.",
        )
        val modesText = mapOf(
            "values-ru" to "Добавлены пользовательский и экспертный режимы интерфейса.",
            "values" to "Added User and Expert interface modes.",
        )
        listOf("values", "values-ru").forEach { qualifier ->
            val strings = releaseNoteCopy(qualifier)
            val texts = listOf("runtime", "sentinel_network", "backup", "core").map { topic ->
                strings.getValue("cli_release_notes_$topic")
            }
            assertEquals(4, texts.size)
            assertTrue(texts.take(3).all { it.length < 90 })
            assertEquals(modesText.getValue(qualifier), texts[2])
            assertEquals(routerText.getValue(qualifier), texts[3])
            assertTrue(texts[3].length <= 125)
            assertEquals(strings.getValue("cli_release_notes_sentinel_network"), strings.getValue("cli_release_notes_sentinel_apps"))
        }
    }

    @Test fun bothLocalesKeepTheNamedImportFormatsAndBriefDetails() {
        listOf("values", "values-ru").forEach { qualifier ->
            val strings = releaseNoteCopy(qualifier)
            val text = strings.values.joinToString("\n")
            listOf("Clash/Mihomo", "YAML/JSON", "Outline JSON", "v2rayTun", "SIP008 JSON", "FoxHole Guard").forEach { format ->
                assertTrue("Missing import format: $format", text.contains(format))
            }
            val details = strings.getValue("cli_release_notes_details_body")
            assertTrue(details.contains("ssconf://"))
            assertTrue(details.replace("\\n", " ").split(Regex("\\s+")).size < 90)
        }
    }
}

internal fun releaseNoteCopy(qualifier: String): Map<String, String> {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile }
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val nodes = factory.newDocumentBuilder().parse(root.resolve("app/src/main/res/$qualifier/release-notes.xml"))
        .getElementsByTagName("string")
    return (0 until nodes.length).associate { index ->
        val node = nodes.item(index)
        node.attributes.getNamedItem("name").nodeValue to node.textContent
    }
}
