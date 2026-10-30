package com.foxhole.guard.core.sentinel.dex

import com.foxhole.guard.core.sentinel.apk.InspectionBytes
import com.foxhole.guard.core.sentinel.apk.InspectionReason
import com.foxhole.guard.core.sentinel.apk.ScanControl
import com.foxhole.guard.core.sentinel.apk.stopInspection

internal class DexCursor(
    private val input: InspectionBytes,
    var offset: Int,
    private val end: Int,
    private val control: ScanControl,
    private val reason: InspectionReason,
) {
    fun byte(): Int {
        control.checkpoint()
        if (offset >= end) stopInspection(reason)
        return input.bytes[offset++].toInt() and 255
    }

    fun uleb(): Long {
        var value = 0L
        repeat(5) { index ->
            val next = byte()
            if (index == 4 && next and 0xf0 != 0) stopInspection(reason)
            value = value or ((next and 0x7f).toLong() shl (index * 7))
            if (next and 0x80 == 0) return value
        }
        stopInspection(reason)
    }

    fun sleb(): Int {
        var value = 0L
        repeat(5) { index ->
            val next = byte()
            if (index == 4 && next and 0x70 != 0 && next and 0x70 != 0x70) stopInspection(reason)
            value = value or ((next and 0x7f).toLong() shl (index * 7))
            if (next and 0x80 == 0) {
                val bits = (index + 1) * 7
                if (next and 0x40 != 0) value = value or (-1L shl bits)
                if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) stopInspection(reason)
                return value.toInt()
            }
        }
        stopInspection(reason)
    }

    fun string(): String {
        val chars = uleb()
        control.reserveStringChars(chars)
        val output = StringBuilder(chars.toInt())
        while (true) {
            val first = byte()
            if (first == 0) break
            if (output.length >= chars) stopInspection(InspectionReason.DEX_INVALID_STRING)
            val decoded = when {
                first < 0x80 -> first
                first in 0xc0..0xdf -> {
                    val value = (first and 0x1f shl 6) or continuation()
                    if (value in 1..0x7f) stopInspection(InspectionReason.DEX_INVALID_STRING)
                    value
                }
                first in 0xe0..0xef -> {
                    val value = (first and 0x0f shl 12) or (continuation() shl 6) or continuation()
                    if (value < 0x800) stopInspection(InspectionReason.DEX_INVALID_STRING)
                    value
                }
                else -> stopInspection(InspectionReason.DEX_INVALID_STRING)
            }
            output.append(decoded.toChar())
        }
        if (output.length.toLong() != chars) stopInspection(InspectionReason.DEX_INVALID_STRING)
        return output.toString()
    }

    private fun continuation(): Int {
        val byte = byte()
        if (byte and 0xc0 != 0x80) stopInspection(InspectionReason.DEX_INVALID_STRING)
        return byte and 0x3f
    }
}
