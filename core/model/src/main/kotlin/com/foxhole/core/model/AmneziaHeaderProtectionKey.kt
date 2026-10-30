package com.foxhole.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

private const val HPK_MALFORMED = "AmneziaWG HeaderProtectionKey must be 32 canonical base64-encoded bytes"

public class AmneziaHeaderProtectionKey private constructor(
    private val encoded: String,
    public val enabled: Boolean,
) {
    public fun normalizedJson(): JsonPrimitive = JsonPrimitive(encoded)

    public fun requireNoncePrefixes(vararg sizes: Int) {
        require(sizes.size == 4 && (!enabled || sizes.all { it >= 12 })) {
            "AmneziaWG nonzero HeaderProtectionKey requires S1..S4 of at least 12 bytes"
        }
    }

    override fun toString(): String = "AmneziaHeaderProtectionKey([REDACTED])"

    public companion object {
        public fun parse(raw: String): AmneziaHeaderProtectionKey = parseCanonical(raw.trim())

        public fun parseNormalized(value: JsonElement): AmneziaHeaderProtectionKey {
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException(HPK_MALFORMED)
            require(primitive.isString) { HPK_MALFORMED }
            return parseCanonical(primitive.content)
        }

        private fun parseCanonical(text: String): AmneziaHeaderProtectionKey {
            require(text.length == 44) { HPK_MALFORMED }
            val decoded = try {
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(HPK_MALFORMED)
            }
            return try {
                require(decoded.size == 32 && Base64.getEncoder().encodeToString(decoded) == text) { HPK_MALFORMED }
                AmneziaHeaderProtectionKey(text, decoded.any { it.toInt() != 0 })
            } finally {
                decoded.fill(0)
            }
        }
    }
}
