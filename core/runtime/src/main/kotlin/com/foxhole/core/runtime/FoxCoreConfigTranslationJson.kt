package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun rejectFoxCoreConfig(
    rejection: FoxCoreConfigRejection,
    path: String,
    explanation: String? = null,
): Nothing = throw FoxCoreConfigTranslationException(rejection, path, explanation)

internal fun JsonObject.requireOnlyKeys(
    allowed: Set<String>,
    path: String,
    rejection: FoxCoreConfigRejection = FoxCoreConfigRejection.UNSUPPORTED_FIELD,
) {
    if (keys.any { it !in allowed }) {
        rejectFoxCoreConfig(rejection, path)
    }
}

internal fun JsonElement.asFoxCoreObject(path: String): JsonObject =
    this as? JsonObject
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)

internal fun JsonElement.asFoxCoreArray(path: String): JsonArray =
    this as? JsonArray
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)

internal fun JsonElement.asFoxCoreString(path: String): String {
    val primitive = this as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return primitive.content
}

internal fun JsonElement.asFoxCoreInt(path: String): Int {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.intOrNull == null) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return checkNotNull(primitive.intOrNull)
}

internal fun JsonElement.asFoxCoreLong(path: String): Long {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.longOrNull == null) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return checkNotNull(primitive.longOrNull)
}

internal fun JsonElement.asFoxCoreBoolean(path: String): Boolean {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
    return checkNotNull(primitive.booleanOrNull)
}

internal fun JsonObject.requiredString(
    key: String,
    path: String,
): String =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.asFoxCoreString("$path.$key")
        ?.takeIf(String::isNotBlank)
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.$key")

internal fun JsonObject.optionalString(
    key: String,
    path: String,
): String? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.asFoxCoreString("$path.$key")

internal fun JsonObject.requiredPort(
    key: String,
    path: String,
): Int =
    this[key]
        ?.asFoxCoreInt("$path.$key")
        ?.takeIf { it in 1..65_535 }
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.$key")

internal fun JsonObject.optionalInt(
    key: String,
    path: String,
): Int? = this[key]?.takeUnless { it is JsonNull }?.asFoxCoreInt("$path.$key")

internal fun JsonObject.optionalLong(
    key: String,
    path: String,
): Long? = this[key]?.takeUnless { it is JsonNull }?.asFoxCoreLong("$path.$key")

internal fun JsonObject.optionalBoolean(
    key: String,
    path: String,
): Boolean? = this[key]?.takeUnless { it is JsonNull }?.asFoxCoreBoolean("$path.$key")

internal fun JsonObject.stringList(
    key: String,
    path: String,
): List<String> =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.asFoxCoreArray("$path.$key")
        ?.mapIndexed { index, element ->
            element
                .asFoxCoreString("$path.$key[$index]")
                .takeIf(String::isNotBlank)
                ?: rejectFoxCoreConfig(
                    FoxCoreConfigRejection.INVALID_SHAPE,
                    "$path.$key[$index]",
                )
        }.orEmpty()

internal fun parseFoxCoreDurationMillis(
    value: String,
    path: String,
): Long {
    val match = DURATION_PATTERN.matchEntire(value.trim())
        ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    val amount =
        match.groupValues[1].toLongOrNull()
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    val multiplier =
        when (match.groupValues[2]) {
            "ms" -> 1L
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            else -> rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
        }
    return try {
        Math.multiplyExact(amount, multiplier)
    } catch (_: ArithmeticException) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
    }
}

internal fun String.isFoxCoreIpv4Literal(): Boolean {
    val parts = split('.')
    return parts.size == 4 &&
        parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
}

internal fun String.isFoxCoreIpv6Literal(): Boolean =
    contains(':') &&
        length <= 45 &&
        all { character ->
            character.isDigit() ||
                character.lowercaseChar() in 'a'..'f' ||
                character == ':' ||
                character == '.'
        }

internal fun String.isFoxCoreIpLiteral(): Boolean =
    isFoxCoreIpv4Literal() || isFoxCoreIpv6Literal()

private val DURATION_PATTERN = Regex("""^(\d+)(ms|s|m|h)$""")
