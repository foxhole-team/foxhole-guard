package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FlagAssetProvenanceTest {
    @Test
    fun `runtime flags match the pinned provenance manifest`() {
        val root = repositoryRoot()
        val flags = File(root, "app/src/main/assets/flags")
        val manifest = File(root, "third_party/flags/app-assets.sha256")
        val entries =
            manifest
                .readLines()
                .filter(String::isNotBlank)
                .associate { line ->
                    val match = requireNotNull(MANIFEST_LINE.matchEntire(line)) { "Invalid flag manifest line: $line" }
                    match.groupValues[2] to match.groupValues[1]
                }

        assertEquals(270, entries.size)
        assertEquals(entries.keys, flags.listFiles().orEmpty().filter { it.extension == "png" }.map { it.name }.toSet())

        entries.forEach { (name, expectedSha256) ->
            val file = File(flags, name)
            val bytes = file.readBytes()
            assertEquals("SHA-256 drift for $name", expectedSha256, bytes.sha256())
            assertTrue("Invalid PNG signature for $name", bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
            val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
            assertEquals("Width drift for $name", 256, dimensions.int)
            assertEquals("Height drift for $name", 192, dimensions.int)
        }
    }

    @Test
    fun `flag provenance pins source renderer and exact MIT text`() {
        val root = repositoryRoot()
        val provenance = File(root, "third_party/flags/README.md").readText()
        val license = File(root, "third_party/flags/flag-icons-LICENSE.txt")

        assertTrue(provenance.contains("086f7e97d657358203916dbe84f61c2bccaa81eb"))
        assertTrue(provenance.contains("eb5b814c794cda735155e2874e288ad9302b6f7a5728e5e50d3ef0333b6c20f4"))
        assertTrue(provenance.contains("@resvg/resvg-wasm` 2.6.2"))
        assertEquals(
            "8f1195d55a2fd315a07d812328470ca9ba2abb78c8d317ff19619d5125e00cea",
            license.readBytes().sha256(),
        )
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { root -> File(root, "third_party/flags/app-assets.sha256").isFile }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val MANIFEST_LINE = Regex("([0-9a-f]{64})  ([a-z0-9-]+\\.png)")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
