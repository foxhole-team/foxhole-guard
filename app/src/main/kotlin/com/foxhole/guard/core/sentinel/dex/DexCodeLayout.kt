package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionBytes
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.ScanControl
import com.foxhole.guard.core.sentinel.apk.stopInspection

internal class DexCodeLayout(
    private val input: InspectionBytes,
    private val sections: DexSections,
    private val references: DexReferences,
    private val control: ScanControl,
) {
    private val dataRanges = mutableMapOf<Int, Int>()
    private val codeRanges = mutableMapOf<Int, Int>()
    private var definedMethods = 0
    private var nativeMethods = 0
    private var instructionUnits = 0L

    fun inspect(): DexCodeSummary {
        val table = sections.table(6)
        val classes = HashSet<Int>()
        repeat(table.count) {
            control.checkpoint()
            val at = table.offset + it * 32
            val owner = references.index(input.u32(at), references.types.size)
            if (!classes.add(owner) || !references.types[owner].startsWith('L')) {
                stopInspection(
                    InspectionReason.DEX_INVALID_CLASS_DATA
                )
            }
            optionalIndex(input.u32(at + 8), references.types.size)
            optionalIndex(input.u32(at + 16), references.strings.size)
            val interfaces = references.typeList(input.u32(at + 12))
            if (interfaces.toSet().size != interfaces.size || interfaces.any { type -> !references.types[type].startsWith('L') }) {
                stopInspection(
                    InspectionReason.DEX_INVALID_CLASS_DATA
                )
            }
            optionalPointer(0x2006, input.u32(at + 20), 4)
            optionalPointer(0x2005, input.u32(at + 28))
            val classData = input.u32(at + 24)
            if (classData != 0L) readClassData(owner, classData)
        }
        validateRanges(0x2000, dataRanges)
        validateRanges(0x2001, codeRanges)
        return DexCodeSummary(definedMethods, codeRanges.size, instructionUnits, nativeMethods)
    }

    private fun optionalIndex(index: Long, count: Int) {
        if (index != 0xffffffffL) references.index(index, count)
    }

    private fun optionalPointer(type: Int, offset: Long, alignment: Int = 1) {
        if (offset != 0L) sections.within(type, offset, 1, alignment)
    }

    private fun readClassData(owner: Int, offset: Long) {
        val at = sections.within(0x2000, offset)
        if (at in dataRanges) stopInspection(InspectionReason.DEX_INVALID_CLASS_DATA)
        val cursor =
            DexCursor(input, at, sections.section(0x2000).end, control, InspectionReason.DEX_INVALID_CLASS_DATA)
        val counts = List(4) { cursor.uleb() }
        if (counts.any { it > control.budget.maxTableItems }) stopInspection(InspectionReason.DEX_TABLE_LIMIT)
        if (counts[0] + counts[1] > references.fieldOwners.size || counts[2] + counts[3] > references.methodOwners.size) {
            stopInspection(
                InspectionReason.DEX_INVALID_CLASS_DATA
            )
        }
        val fields = HashSet<Int>()
        readFields(cursor, counts[0], owner, fields)
        readFields(cursor, counts[1], owner, fields)
        val methods = HashSet<Int>()
        readMethods(cursor, counts[2], owner, methods)
        readMethods(cursor, counts[3], owner, methods)
        dataRanges[at] = cursor.offset
    }

    private fun readFields(cursor: DexCursor, count: Long, owner: Int, seen: MutableSet<Int>) {
        var index = 0L
        repeat(count.toInt()) {
            control.checkpoint()
            val delta = cursor.uleb()
            if (it > 0 && delta == 0L) stopInspection(InspectionReason.DEX_INVALID_CLASS_DATA)
            index += delta
            val id = references.index(index, references.fieldOwners.size)
            if (!seen.add(id) || references.fieldOwners[id] != owner) {
                stopInspection(
                    InspectionReason.DEX_INVALID_CLASS_DATA
                )
            }
            cursor.uleb()
        }
    }

    private fun readMethods(cursor: DexCursor, count: Long, owner: Int, seen: MutableSet<Int>) {
        var index = 0L
        repeat(count.toInt()) {
            control.checkpoint()
            val delta = cursor.uleb()
            if (it > 0 && delta == 0L) stopInspection(InspectionReason.DEX_INVALID_CLASS_DATA)
            index += delta
            val id = references.index(index, references.methodOwners.size)
            if (!seen.add(id) || references.methodOwners[id] != owner) {
                stopInspection(
                    InspectionReason.DEX_INVALID_CLASS_DATA
                )
            }
            val flags = cursor.uleb()
            val codeOffset = cursor.uleb()
            val noCode = flags and 0x500 != 0L
            if (noCode != (codeOffset == 0L)) stopInspection(InspectionReason.DEX_INVALID_CLASS_DATA)
            if (flags and 0x100 != 0L) nativeMethods++
            definedMethods++
            if (codeOffset != 0L && codeOffset.toInt() !in codeRanges) readCode(codeOffset)
        }
    }

    private fun readCode(offset: Long) {
        val at = sections.within(0x2001, offset, 16, 4)
        val registers = input.u16(at)
        val incoming = input.u16(at + 2)
        val tries = input.u16(at + 6)
        val debugOffset = input.u32(at + 8)
        val units = input.u32(at + 12)
        if (incoming > registers) stopInspection(InspectionReason.DEX_INVALID_CODE)
        optionalPointer(0x2003, debugOffset)
        sections.within(0x2001, at + 16L, units * 2)
        control.checkpoint(units)
        instructionUnits += units
        var end = at + 16 + (units * 2).toInt()
        if (tries > 0) {
            if (units % 2 == 1L) {
                sections.within(0x2001, end.toLong(), 2)
                if (input.u16(end) != 0) stopInspection(InspectionReason.DEX_INVALID_CODE)
                end += 2
            }
            end = readTries(end, tries, units)
        }
        codeRanges[at] = end
    }

    private fun readTries(at: Int, count: Int, units: Long): Int {
        sections.within(0x2001, at.toLong(), count * 8L)
        val handlerStart = at + count * 8
        val cursor =
            DexCursor(input, handlerStart, sections.section(0x2001).end, control, InspectionReason.DEX_INVALID_CODE)
        val handlers = cursor.uleb()
        if (handlers == 0L || handlers > control.budget.maxTableItems) stopInspection(InspectionReason.DEX_INVALID_CODE)
        val offsets = HashSet<Int>()
        repeat(handlers.toInt()) {
            control.checkpoint()
            offsets.add(cursor.offset - handlerStart)
            val size = cursor.sleb()
            val pairs = kotlin.math.abs(size.toLong())
            if (pairs > references.types.size) stopInspection(InspectionReason.DEX_INVALID_CODE)
            repeat(pairs.toInt()) {
                references.index(cursor.uleb(), references.types.size)
                if (cursor.uleb() >= units) stopInspection(InspectionReason.DEX_INVALID_CODE)
            }
            if (size <= 0 && cursor.uleb() >= units) stopInspection(InspectionReason.DEX_INVALID_CODE)
        }
        var previousEnd = 0L
        repeat(count) {
            control.checkpoint()
            val offset = at + it * 8
            val start = input.u32(offset)
            val length = input.u16(offset + 4)
            val handler = input.u16(offset + 6)
            if (length == 0 || start < previousEnd) stopInspection(InspectionReason.DEX_INVALID_CODE)
            if (start + length > units || handler !in offsets) stopInspection(InspectionReason.DEX_INVALID_CODE)
            previousEnd = start + length
        }
        return cursor.offset
    }

    private fun validateRanges(type: Int, ranges: Map<Int, Int>) {
        val expected = sections.sections[type]?.count ?: 0
        if (expected != ranges.size) stopInspection(InspectionReason.DEX_INVALID_MAP)
        var end = -1
        ranges.toSortedMap().forEach { (start, nextEnd) ->
            control.checkpoint()
            if (start < end) stopInspection(InspectionReason.DEX_INVALID_RANGE)
            end = nextEnd
        }
    }
}
