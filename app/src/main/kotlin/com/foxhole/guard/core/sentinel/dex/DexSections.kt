package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionBytes
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import com.foxhole.guard.core.sentinel.apk.ScanControl
import com.foxhole.guard.core.sentinel.apk.stopInspection

internal data class DexTable(val type: Int, val count: Int, val offset: Int, val width: Int) {
    val end: Long get() = offset.toLong() + count.toLong() * width
}

internal data class DexSection(val type: Int, val count: Int, val offset: Int, val end: Int)

internal class DexSections(private val input: InspectionBytes, private val control: ScanControl) {
    val tables: List<DexTable> = readTables()
    val dataOffset: Int = input.range(input.u32(108), input.u32(104))
    private val mapOffset: Int = input.range(input.u32(52), 4)
    val sections: Map<Int, DexSection> = readMap()

    fun table(type: Int): DexTable = tables.single { it.type == type }

    fun section(type: Int): DexSection = sections[type] ?: stopInspection(InspectionReason.DEX_INVALID_MAP)

    fun within(type: Int, offset: Long, bytes: Long = 1, alignment: Int = 1): Int {
        val section = section(type)
        if (offset < section.offset || offset > section.end) stopInspection(InspectionReason.DEX_INVALID_RANGE)
        if (bytes < 0 || bytes > section.end - offset || offset % alignment != 0L) {
            stopInspection(
                InspectionReason.DEX_INVALID_RANGE
            )
        }
        return input.range(offset, bytes)
    }

    private fun readTables(): List<DexTable> {
        val widths = listOf(4, 4, 12, 8, 8, 32)
        val result = widths.mapIndexed { index, width ->
            control.checkpoint()
            val count = input.u32(56 + index * 8)
            val offset = input.u32(60 + index * 8)
            validateTableRange(index, count, offset, width)
            DexTable(index + 1, count.toInt(), offset.toInt(), width)
        }
        var end = 112L
        result.filter { it.count != 0 }.forEach {
            if (it.offset < end) stopInspection(InspectionReason.DEX_INVALID_RANGE)
            end = it.end
        }
        val dataStart = input.u32(108)
        val dataSize = input.u32(104)
        if (dataStart < end || dataStart % 4 != 0L) stopInspection(InspectionReason.DEX_INVALID_RANGE)
        if (dataSize % 4 != 0L || dataStart + dataSize != input.size.toLong()) {
            stopInspection(
                InspectionReason.DEX_INVALID_RANGE
            )
        }
        return result
    }

    private fun readMap(): Map<Int, DexSection> {
        if (mapOffset < dataOffset || mapOffset % 4 != 0) stopInspection(InspectionReason.DEX_INVALID_MAP)
        val count = input.u32(mapOffset)
        if (count !in 2..64) stopInspection(InspectionReason.DEX_INVALID_MAP)
        input.range(mapOffset + 4L, count * 12, InspectionReason.DEX_INVALID_MAP)
        val rows = ArrayList<DexSection>(count.toInt())
        val seen = HashSet<Int>()
        repeat(count.toInt()) { index ->
            control.checkpoint()
            val at = mapOffset + 4 + index * 12
            val type = input.u16(at)
            val size = input.u32(at + 4)
            val offset = input.u32(at + 8)
            if (input.u16(at + 2) != 0 || !seen.add(type)) stopInspection(InspectionReason.DEX_INVALID_MAP)
            val start = validateMapRow(type, size, offset, rows.lastOrNull()?.offset)
            rows.add(DexSection(type, size.toInt(), start, input.size))
        }
        val sections = rows.mapIndexed { index, row ->
            row.copy(
                end = rows.getOrNull(index + 1)?.offset ?: input.size
            )
        }.associateBy { it.type }
        validateMapTables(sections)
        return sections
    }

    private fun validateMapTables(sections: Map<Int, DexSection>) {
        val header = sections[0]
        if (header?.offset != 0 || header.count != 1 || header.end < 112) {
            stopInspection(
                InspectionReason.DEX_INVALID_MAP
            )
        }
        val map = sections[0x1000]
        if (map?.offset != mapOffset || map.count != 1 || mapOffset + 4L + input.u32(mapOffset) * 12 > map.end) {
            stopInspection(
                InspectionReason.DEX_INVALID_MAP
            )
        }
        tables.forEach { table -> validateMatchingTable(table, sections[table.type]) }
        val strings = sections[0x2002]
        if (table(1).count != (strings?.count ?: 0)) stopInspection(InspectionReason.DEX_INVALID_MAP)
    }

    private fun validateTableRange(index: Int, count: Long, offset: Long, width: Int) {
        if (count > control.budget.maxTableItems) stopInspection(InspectionReason.DEX_TABLE_LIMIT)
        if (index in 1..2 && count > 65535) stopInspection(InspectionReason.DEX_TABLE_LIMIT)
        if (count == 0L && offset != 0L) stopInspection(InspectionReason.DEX_INVALID_RANGE)
        if (count != 0L && offset < 112) stopInspection(InspectionReason.DEX_INVALID_RANGE)
        if (offset % 4 != 0L) stopInspection(InspectionReason.DEX_INVALID_RANGE)
        input.range(offset, count * width)
    }

    private fun validateMapRow(type: Int, size: Long, offset: Long, previous: Int?): Int {
        if (size == 0L || size > control.budget.maxTableItems) stopInspection(InspectionReason.DEX_INVALID_MAP)
        if (type !in KNOWN_TYPES) stopInspection(InspectionReason.DEX_FEATURE_UNSUPPORTED, InspectionStatus.UNSUPPORTED)
        val start = input.range(offset, 1, InspectionReason.DEX_INVALID_MAP)
        if (previous != null && start <= previous) stopInspection(InspectionReason.DEX_INVALID_MAP)
        if (type >= 0x1000 && start < dataOffset) stopInspection(InspectionReason.DEX_INVALID_MAP)
        return start
    }

    private fun validateMatchingTable(table: DexTable, section: DexSection?) {
        if (table.count == 0) {
            if (section != null) stopInspection(InspectionReason.DEX_INVALID_MAP)
        } else {
            if (section == null) stopInspection(InspectionReason.DEX_INVALID_MAP)
            if (section.count != table.count || section.offset != table.offset || table.end > section.end) {
                stopInspection(
                    InspectionReason.DEX_INVALID_MAP
                )
            }
        }
    }

    private companion object {
        val KNOWN_TYPES =
            setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0x1000, 0x1001, 0x1002, 0x1003, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0xf000)
    }
}
