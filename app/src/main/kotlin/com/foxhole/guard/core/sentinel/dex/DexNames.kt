package com.foxhole.guard.core.sentinel.dex

internal object DexNames {
    fun member(value: String, version: Int): Boolean {
        val name = if (value.startsWith('<') && value.endsWith('>')) value.substring(1, value.length - 1) else value
        return simple(name, version)
    }

    fun simple(value: String, version: Int): Boolean {
        if (value.isEmpty()) return false
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            if (!validPoint(point, version)) return false
            offset += Character.charCount(point)
        }
        return true
    }

    private fun validPoint(point: Int, version: Int): Boolean = when {
        point in 'A'.code..'Z'.code || point in 'a'.code..'z'.code || point in '0'.code..'9'.code -> true
        point == '$'.code || point == '-'.code || point == '_'.code -> true
        point in 0x00a1..0x1fff || point in 0x2010..0x2027 || point in 0x2030..0xd7ff -> true
        point in 0xe000..0xffef || point in 0x10000..0x10ffff -> true
        version >= 40 && (point == 0x20 || point == 0xa0 || point in 0x2000..0x200a || point == 0x202f) -> true
        else -> false
    }
}
