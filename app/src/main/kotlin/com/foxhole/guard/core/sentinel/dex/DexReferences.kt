package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionBytes
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.ScanControl
import com.foxhole.guard.core.sentinel.apk.stopInspection

internal class DexReferences(
    private val input: InspectionBytes,
    private val sections: DexSections,
    private val control: ScanControl,
    private val version: Int,
) {
    val strings: List<String> = readStrings()
    val types: List<String> = readTypes()
    val fieldOwners: IntArray = readFields()
    val methodOwners: IntArray
    val observations: List<DexReferenceObservation>

    init {
        validatePrototypes()
        val counts = mutableMapOf<DexObservationKind, Int>()
        methodOwners = readMethods(counts)
        observations = counts.entries.sortedBy { it.key.ordinal }.map { DexReferenceObservation(it.key, it.value) }
        validateExtraReferences()
    }

    fun index(value: Long, count: Int): Int {
        if (value < 0 || value >= count) stopInspection(InspectionReason.DEX_INVALID_INDEX)
        return value.toInt()
    }

    fun typeList(offset: Long): List<Int> {
        if (offset == 0L) return emptyList()
        val at = sections.within(0x1001, offset, 4, 4)
        val count = input.u32(at)
        if (count > control.budget.maxTableItems) stopInspection(InspectionReason.DEX_TABLE_LIMIT)
        sections.within(0x1001, at + 4L, count * 2)
        return List(count.toInt()) {
            control.checkpoint()
            index(input.u16(at + 4 + it * 2).toLong(), types.size)
        }
    }

    private fun readStrings(): List<String> {
        val table = sections.table(1)
        val offsets = HashSet<Int>()
        val ranges = mutableMapOf<Int, Int>()
        return List(table.count) { index ->
            control.checkpoint()
            val at = sections.within(0x2002, input.u32(table.offset + index * 4))
            if (!offsets.add(at)) stopInspection(InspectionReason.DEX_INVALID_STRING)
            val cursor =
                DexCursor(input, at, sections.section(0x2002).end, control, InspectionReason.DEX_INVALID_STRING)
            cursor.string().also { ranges[at] = cursor.offset }
        }.also { list ->
            var end = -1
            ranges.toSortedMap().forEach { (start, nextEnd) ->
                control.checkpoint()
                if (start < end) stopInspection(InspectionReason.DEX_INVALID_RANGE)
                end = nextEnd
            }
            for (index in 1 until list.size) {
                control.checkpoint()
                if (list[index - 1] >= list[index]) stopInspection(InspectionReason.DEX_INVALID_STRING)
            }
        }
    }

    private fun readTypes(): List<String> {
        val table = sections.table(2)
        var previous = -1
        return List(table.count) {
            control.checkpoint()
            val id = index(input.u32(table.offset + it * 4), strings.size)
            if (id <= previous) stopInspection(InspectionReason.DEX_INVALID_INDEX)
            previous = id
            strings[id].also(::validateDescriptor)
        }
    }

    private fun validateDescriptor(type: String) {
        val dimensions = type.takeWhile { it == '[' }.length
        if (dimensions > 255 || dimensions == type.length) stopInspection(InspectionReason.DEX_INVALID_STRING)
        val body = type.substring(dimensions)
        val primitive = body.length == 1 && body[0] in "VZBSCIJFD"
        val objectType = body.length >= 3 && body.startsWith('L') && body.endsWith(';') &&
            body.substring(1, body.length - 1).split('/').all { part ->
                part.isNotEmpty() && part.none {
                    it in ".;[" || it.code < 32
                }
            }
        if (!primitive && !objectType) stopInspection(InspectionReason.DEX_INVALID_STRING)
        if (dimensions > 0 && body == "V") stopInspection(InspectionReason.DEX_INVALID_STRING)
    }

    private fun validatePrototypes() {
        val table = sections.table(3)
        var previous: Pair<Int, List<Int>>? = null
        repeat(table.count) {
            control.checkpoint()
            val at = table.offset + it * 12
            val shorty = strings[index(input.u32(at), strings.size)]
            val returnIndex = index(input.u32(at + 4), types.size)
            val result = types[returnIndex]
            val parameters = typeList(input.u32(at + 8))
            val current = returnIndex to parameters
            previous?.let { before ->
                if (comparePrototypes(before, current) >= 0) {
                    stopInspection(
                        InspectionReason.DEX_INVALID_INDEX
                    )
                }
            }
            previous = current
            if (parameters.any { type -> types[type] == "V" }) stopInspection(InspectionReason.DEX_INVALID_INDEX)
            val expected = buildString {
                append(shortType(result))
                parameters.forEach { type -> append(shortType(types[type])) }
            }
            if (shorty != expected) stopInspection(InspectionReason.DEX_INVALID_STRING)
        }
    }

    private fun comparePrototypes(before: Pair<Int, List<Int>>, current: Pair<Int, List<Int>>): Int {
        if (before.first != current.first) return before.first.compareTo(current.first)
        for (index in 0 until minOf(before.second.size, current.second.size)) {
            control.checkpoint()
            val compare = before.second[index].compareTo(current.second[index])
            if (compare != 0) return compare
        }
        return before.second.size.compareTo(current.second.size)
    }

    private fun shortType(type: String): Char = if (type[0] == '[' || type[0] == 'L') 'L' else type[0]

    private fun readFields(): IntArray {
        val table = sections.table(4)
        var previous: List<Int>? = null
        return IntArray(table.count) {
            control.checkpoint()
            val at = table.offset + it * 8
            val owner = index(input.u16(at).toLong(), types.size)
            val type = index(input.u16(at + 2).toLong(), types.size)
            val name = index(input.u32(at + 4), strings.size)
            if (types[type] == "V" || !types[owner].startsWith('L')) stopInspection(InspectionReason.DEX_INVALID_INDEX)
            if (!DexNames.member(strings[name], version)) stopInspection(InspectionReason.DEX_INVALID_STRING)
            val key = listOf(owner, name, type)
            checkOrder(previous, key)
            previous = key
            owner
        }
    }

    private fun readMethods(counts: MutableMap<DexObservationKind, Int>): IntArray {
        val table = sections.table(5)
        var previous: List<Int>? = null
        return IntArray(table.count) {
            control.checkpoint()
            val at = table.offset + it * 8
            val owner = index(input.u16(at).toLong(), types.size)
            val proto = index(input.u16(at + 2).toLong(), sections.table(3).count)
            val name = index(input.u32(at + 4), strings.size)
            if (!types[owner].startsWith('L') && !types[owner].startsWith('[')) {
                stopInspection(
                    InspectionReason.DEX_INVALID_INDEX
                )
            }
            if (!DexNames.member(strings[name], version)) stopInspection(InspectionReason.DEX_INVALID_STRING)
            val key = listOf(owner, name, proto)
            checkOrder(previous, key)
            previous = key
            observation(types[owner], strings[name])?.let { kind -> counts[kind] = (counts[kind] ?: 0) + 1 }
            owner
        }
    }

    private fun checkOrder(previous: List<Int>?, current: List<Int>) {
        if (previous == null) return
        val differing = previous.indices.firstOrNull { previous[it] != current[it] }
        if (differing == null || previous[differing] > current[differing]) {
            stopInspection(
                InspectionReason.DEX_INVALID_INDEX
            )
        }
    }

    private fun validateExtraReferences() {
        sections.sections[7]?.let { section ->
            sections.within(7, section.offset.toLong(), section.count * 4L, 4)
            repeat(section.count) {
                control.checkpoint()
                sections.within(0x2005, input.u32(section.offset + it * 4))
            }
        }
        sections.sections[8]?.let { section ->
            sections.within(8, section.offset.toLong(), section.count * 8L, 4)
            repeat(section.count) {
                control.checkpoint()
                val at = section.offset + it * 8
                val kind = input.u16(at)
                if (kind !in 0..8 || input.u16(at + 2) != 0 || input.u16(at + 6) != 0) {
                    stopInspection(
                        InspectionReason.DEX_INVALID_INDEX
                    )
                }
                index(input.u16(at + 4).toLong(), if (kind <= 3) fieldOwners.size else methodOwners.size)
            }
        }
    }

    private fun observation(owner: String, name: String): DexObservationKind? = when {
        owner in CLASS_LOADERS && name in setOf("<init>", "loadClass", "findClass") -> DexObservationKind.DEX_CLASS_LOADER_REFERENCE
        owner == "Ljava/lang/Runtime;" && name == "exec" || owner == "Ljava/lang/ProcessBuilder;" && name == "start" -> DexObservationKind.RUNTIME_EXEC_REFERENCE
        owner == "Ljava/lang/reflect/Method;" && name == "invoke" || owner == "Ljava/lang/Class;" && name in setOf("forName", "getMethod", "getDeclaredMethod") -> DexObservationKind.REFLECTION_REFERENCE
        owner in NETWORK_TYPES && name in setOf("connect", "openConnection", "getInputStream", "send") -> DexObservationKind.NETWORK_API_REFERENCE
        owner in CRYPTO_TYPES && name in setOf("getInstance", "init", "doFinal", "generateKey") -> DexObservationKind.CRYPTO_API_REFERENCE
        owner == "Landroid/accessibilityservice/AccessibilityService;" && name in setOf("performGlobalAction", "getRootInActiveWindow", "dispatchGesture") -> DexObservationKind.ACCESSIBILITY_API_REFERENCE
        owner == "Landroid/telephony/SmsManager;" && name in setOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage") -> DexObservationKind.SMS_API_REFERENCE
        owner == "Landroid/content/pm/PackageInstaller;" && name == "createSession" || owner == "Landroid/content/pm/PackageInstaller\$Session;" && name == "commit" -> DexObservationKind.PACKAGE_INSTALL_API_REFERENCE
        owner == "Ljava/lang/System;" && name in setOf("load", "loadLibrary") || owner == "Ljava/lang/Runtime;" && name in setOf("load", "loadLibrary") -> DexObservationKind.NATIVE_LIBRARY_API_REFERENCE
        else -> null
    }

    private companion object {
        val CLASS_LOADERS =
            setOf(
                "Ldalvik/system/DexClassLoader;",
                "Ldalvik/system/PathClassLoader;",
                "Ldalvik/system/InMemoryDexClassLoader;"
            )
        val NETWORK_TYPES =
            setOf("Ljava/net/Socket;", "Ljava/net/URL;", "Ljava/net/HttpURLConnection;", "Ljava/net/DatagramSocket;")
        val CRYPTO_TYPES =
            setOf("Ljavax/crypto/Cipher;", "Ljavax/crypto/KeyGenerator;", "Ljava/security/MessageDigest;")
    }
}
