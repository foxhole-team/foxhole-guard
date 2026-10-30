package com.foxhole.guard.core.sentinel.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object OwnedInspectionFixtures {
    fun dex(version: String = "035"): ByteArray {
        val strings = listOf("Ljava/lang/Runtime;", "V", "exec").sorted()
        val stringIds = 112
        val typeIds = stringIds + strings.size * 4
        val protoIds = typeIds + 8
        val methodIds = protoIds + 12
        val dataStart = methodIds + 8
        val stringData = ByteArrayOutputStream()
        val offsets = strings.map {
            val offset = dataStart + stringData.size()
            stringData.write(it.length)
            stringData.write(it.toByteArray(Charsets.UTF_8))
            stringData.write(0)
            offset
        }
        while ((dataStart + stringData.size()) % 4 != 0) stringData.write(0)
        val mapOffset = dataStart + stringData.size()
        val maps = listOf(
            Triple(0, 1, 0),
            Triple(1, 3, stringIds),
            Triple(2, 2, typeIds),
            Triple(3, 1, protoIds),
            Triple(5, 1, methodIds),
            Triple(0x2002, 3, dataStart),
            Triple(0x1000, 1, mapOffset),
        )
        val bytes = ByteArray(mapOffset + 4 + maps.size * 12)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("dex\n$version\u0000".toByteArray(Charsets.US_ASCII))
        out.position(32)
        out.putInt(bytes.size).putInt(112).putInt(0x12345678)
        out.putInt(0).putInt(0).putInt(mapOffset)
        out.putInt(3).putInt(stringIds).putInt(2).putInt(typeIds)
        out.putInt(1).putInt(protoIds).putInt(0).putInt(0)
        out.putInt(1).putInt(methodIds).putInt(0).putInt(0)
        out.putInt(bytes.size - dataStart).putInt(dataStart)
        out.position(stringIds)
        offsets.forEach(out::putInt)
        out.putInt(strings.indexOf("Ljava/lang/Runtime;")).putInt(strings.indexOf("V"))
        out.putInt(strings.indexOf("V")).putInt(1).putInt(0)
        out.putShort(0).putShort(0).putInt(strings.indexOf("exec"))
        out.put(stringData.toByteArray())
        out.putInt(maps.size)
        maps.forEach { (type, size, offset) ->
            out.putShort(type.toShort()).putShort(0).putInt(size).putInt(offset)
        }
        return sealDex(bytes)
    }

    fun sealDex(bytes: ByteArray): ByteArray {
        val signature = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size))
        signature.copyInto(bytes, 12)
        val checksum = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value
        putInt(bytes, 8, checksum.toInt())
        return bytes
    }

    fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = entry(name, bytes, stored)
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun entry(name: String, bytes: ByteArray, stored: Boolean): ZipEntry = ZipEntry(name).apply {
        time = 0
        if (stored) {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
        }
    }

    fun centralOffset(bytes: ByteArray): Int = signatureOffset(bytes, 0x02014b50)

    fun signatureOffset(bytes: ByteArray, signature: Int): Int =
        (0..bytes.size - 4).first { intAt(bytes, it) == signature }

    fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }
}
