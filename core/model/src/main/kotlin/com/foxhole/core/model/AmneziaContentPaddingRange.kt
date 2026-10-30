package com.foxhole.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val CPA_UINT32_MAX = 4_294_967_295L
private const val CPA_MALFORMED = "AmneziaWG ContentPaddingAddition must be N or N-M in 0..4294967295"
private val CPA_PARAMETER = Regex("[0-9]+(?:-[0-9]+)?")

public class AmneziaContentPaddingRange private constructor(
    public val lower: Long,
    public val upper: Long,
) {
    public val disabled: Boolean get() = upper == 0L

    public fun normalizedJson(): JsonPrimitive =
        if (lower == upper) JsonPrimitive(lower) else JsonPrimitive("$lower-$upper")

    public companion object {
        public fun parse(raw: String): AmneziaContentPaddingRange = parseBounds(raw.trim())

        public fun parseNormalized(value: JsonElement): AmneziaContentPaddingRange {
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException(CPA_MALFORMED)
            val text = primitive.content
            require(primitive.isString == text.contains('-')) { CPA_MALFORMED }
            return parseBounds(text)
        }

        private fun parseBounds(text: String): AmneziaContentPaddingRange {
            require(CPA_PARAMETER.matches(text)) { CPA_MALFORMED }
            val parts = text.split('-', limit = 2)
            val lower = parts[0].toLongOrNull()
            val upper = if (parts.size == 1) lower else parts[1].toLongOrNull()
            require(lower != null && upper != null && lower in 0..CPA_UINT32_MAX && upper in 0..CPA_UINT32_MAX) {
                CPA_MALFORMED
            }
            require(lower <= upper) { "AmneziaWG ContentPaddingAddition range ends before it starts" }
            return AmneziaContentPaddingRange(lower, upper)
        }
    }
}
