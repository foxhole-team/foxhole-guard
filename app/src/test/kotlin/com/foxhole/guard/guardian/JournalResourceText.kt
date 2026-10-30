package com.foxhole.guard.guardian

import com.foxhole.guard.R
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal fun journalResourceText(language: String): JournalText {
    val values = linkedMapOf<String, String>()
    val directories = if (language == "ru") listOf("values", "values-ru") else listOf("values")
    directories.forEach { directory ->
        File("src/main/res/$directory").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { source ->
            val entries = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                source
            ).getElementsByTagName("string")
            for (index in 0 until entries.length) {
                val node = entries.item(index) as Element
                values[node.getAttribute("name")] = node.textContent.replace("\\'", "'").replace("\\n", "\n")
            }
        }
    }
    val symbols = R.string::class.java.fields.associate { field -> field.getInt(null) to field.name }
    return { resource, arguments ->
        val name = requireNotNull(symbols[resource]) { "Missing resource symbol $resource" }
        val template = requireNotNull(values[name]) { "Missing resource text $name" }
        require(arguments.size <= 1)
        String.format(
            if (language == "ru") Locale.forLanguageTag("ru") else Locale.ENGLISH,
            template,
            arguments.firstOrNull()
        )
    }
}
