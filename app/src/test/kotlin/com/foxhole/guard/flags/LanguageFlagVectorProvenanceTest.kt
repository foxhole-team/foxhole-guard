package com.foxhole.guard.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

class LanguageFlagVectorProvenanceTest {
    @Test fun sevenLanguageFlagsArePinnedUntintedNativeVectorsWithFourByThreeAspect() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { it.resolve("third_party/flags/language-vectors.sha256").isFile }
        val entries = root.resolve("third_party/flags/language-vectors.sha256").readLines().associate { line ->
            val fields = line.split("  ")
            fields[1] to fields[0]
        }
        assertEquals(setOf("gb", "ru", "de", "fr", "es", "jp", "cn").map { "flag_svg_$it.xml" }.toSet(), entries.keys)
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = true
        }
        entries.forEach { (name, expectedHash) ->
            val file = root.resolve("app/src/main/res/drawable/$name")
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(name, expectedHash, hash)
            val vector = factory.newDocumentBuilder().parse(file).documentElement
            assertEquals("vector", vector.tagName)
            val android = "http://schemas.android.com/apk/res/android"
            assertEquals(4f / 3f, vector.getAttributeNS(android, "viewportWidth").toFloat() /
                vector.getAttributeNS(android, "viewportHeight").toFloat(), .00001f)
            assertFalse(vector.hasAttributeNS(android, "tint"))
        }
    }
}
