package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.help.HelpBlock
import com.foxhole.guard.ui.cli.help.HelpText
import com.foxhole.guard.ui.cli.help.guardHelpTopic
import com.foxhole.guard.ui.cli.help.guardHelpTopicForResource
import com.foxhole.guard.ui.cli.help.guardHelpTopics
import com.foxhole.guard.ui.cli.help.helpUsesVerticalRows
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class CliHelpFormattingTest {
    @Test fun `smart structure is independent of translated punctuation`() {
        val blocks = guardHelpTopic("smart").blocks.filterIsInstance<HelpBlock.Comparison>()
        assertEquals(listOf(3, 4), blocks.map { it.rows.size })
        assertTrue(blocks.all { it.twoColumns })
        assertEquals(listOf(150L, 300L, 600L, 601L), blocks.last().rows.map { it.latencyMs })
        assertEquals(listOf("T", "P", "L"), blocks.first().rows.map { (it.label as HelpText.Literal).value })
        assertEquals(guardHelpTopic("smart"), guardHelpTopicForResource(R.string.cli_help_smart_body))
    }

    @Test fun `every locale contains every typed help resource`() {
        val required = (guardHelpTopics().flatMap { it.blocks } + guardHelpTopic("router", false, false).blocks).flatMap(::resourceIds).toSet()
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { locale ->
            val available = localizedStrings(locale)
            required.forEach { id ->
                val key = requireNotNull(names[id])
                assertTrue("$locale missing $key", !available[key].isNullOrBlank())
            }
        }
    }

    @Test fun `installation inline link labels occur exactly once in every locale`() {
        val links = guardHelpTopic("router").blocks.filterIsInstance<HelpBlock.Items>().first().links
        assertEquals(3, links.size)
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { locale ->
            val strings = localizedStrings(locale)
            links.forEach { (id, link) ->
                val full = strings.getValue(names.getValue(id))
                val label = strings.getValue(names.getValue(link.label))
                assertEquals("https://github.com/foxhole-team/foxhole-openwrt-client", link.url)
                assertTrue(label.isNotBlank())
                val start = full.indexOf(label)
                assertTrue("$locale link label missing", start >= 0)
                assertEquals("$locale link label ambiguous", -1, full.indexOf(label, start + label.length))
            }
        }
    }

    @Test fun `router inspection is never presented as installation`() {
        fun installText(canInstall: Boolean) = guardHelpTopic("router", canInstall).blocks.flatMap(::resourceIds)
        assertTrue(installText(false).contains(R.string.help_router_install_unavailable))
        assertFalse(installText(false).contains(R.string.help_router_install_available))
        assertTrue(installText(true).contains(R.string.help_router_install_available))
        assertFalse(installText(true).contains(R.string.help_router_install_unavailable))
        assertTrue(guardHelpTopic("router", false, false).blocks.flatMap(::resourceIds).contains(R.string.help_router_install_manual))
    }

    @Test fun `narrow and enlarged text use vertical comparisons`() {
        assertTrue(helpUsesVerticalRows(320f, 1f))
        assertTrue(helpUsesVerticalRows(360f, 1.5f))
        assertTrue(helpUsesVerticalRows(640f, 2f))
        assertFalse(helpUsesVerticalRows(720f, 1f))
    }

    @Test fun `router is a stable topic and context aliases reuse catalog`() {
        val topics = guardHelpTopics()
        assertEquals(topics.size, topics.map { it.id }.toSet().size)
        assertEquals("router", guardHelpTopicForResource(R.string.guard_router_router_help_setup_pair)?.id)
        assertEquals("routing", guardHelpTopicForResource(R.string.cli_help_routing_body)?.id)
    }

    private fun resourceIds(block: HelpBlock): List<Int> = when (block) {
        is HelpBlock.Heading -> listOf(block.text)
        is HelpBlock.Paragraph -> listOf(block.text)
        is HelpBlock.Items -> block.items + block.links.values.map { it.label }
        is HelpBlock.Notice -> listOf(block.text)
        is HelpBlock.Comparison -> listOfNotNull(block.title) + block.rows.flatMap { row ->
            listOf(row.label, row.detail).filterIsInstance<HelpText.Resource>().map { it.id }
        }
        is HelpBlock.Chips -> emptyList()
        is HelpBlock.Details -> listOf(block.title) + block.blocks.flatMap(::resourceIds)
        is HelpBlock.Link -> listOf(block.label)
    }

    private fun localizedStrings(locale: String): Map<String, String> {
        val root = File(requireNotNull(System.getProperty("user.dir")), "src/main/res/$locale")
        return root.listFiles().orEmpty().filter { it.extension == "xml" }.flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }.toMap()
    }
}
