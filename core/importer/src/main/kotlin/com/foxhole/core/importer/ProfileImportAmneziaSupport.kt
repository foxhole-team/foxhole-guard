package com.foxhole.core.importer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_AMNEZIA_JUNK_SIZE = 1280

private const val MAX_AMNEZIA_JUNK_PACKET_COUNT = 128

private const val MAX_AMNEZIA_INIT_PACKETS = 5

private const val U16_MAX = 65_535
private const val U32_MAX = 4_294_967_295L

private const val TIMESTAMP_TAG_BYTES = 4

private val AMNEZIA_OBFUSCATION_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

private val AMNEZIA_EXTENSION_KEYS =
    listOf(
        "s3",
        "s4",
        "i1",
        "i2",
        "i3",
        "i4",
        "i5",
        "rekeytimeout",
        "rekeyaftertime",
        "rejectaftertime",
        "keepalivetimeout",
        "maxhandshakeattempts",
    )

private val AMNEZIA_TIMER_KEYS =
    listOf(
        "rekeytimeout" to "rekey_timeout_s",
        "rekeyaftertime" to "rekey_after_time_s",
        "rejectaftertime" to "reject_after_time_s",
        "keepalivetimeout" to "keepalive_timeout_s",
        "maxhandshakeattempts" to "max_handshake_attempts",
    )

private const val AMNEZIA_HEADER_PROTECTION_KEY = "headerprotectionkey"

internal val AMNEZIA_URI_QUERY_KEYS: List<String> =
    AMNEZIA_OBFUSCATION_KEYS + AMNEZIA_EXTENSION_KEYS +
        listOf("advancedsecurity", AMNEZIA_HEADER_PROTECTION_KEY)

internal const val AMNEZIAWG_PROTOCOL_LABEL = "AMNEZIAWG"

internal fun ProfileImportCoreSupport.ProxyNode.carriesAmneziaObfuscation(): Boolean =
    endpoint?.containsKey("amnezia") == true

internal fun amneziaBlockFromInterface(
    interfaceSection: Map<String, List<String>>,
    peerSection: Map<String, List<String>>,
    mtu: Int,
): JsonObject? {
    val values = lowercasedFirstValues(interfaceSection)
    val peerValues = lowercasedFirstValues(peerSection)

    require(AMNEZIA_HEADER_PROTECTION_KEY !in values && AMNEZIA_HEADER_PROTECTION_KEY !in peerValues) {
        "AmneziaWG HeaderProtectionKey (3.0 header encryption) is not supported"
    }
    val present = AMNEZIA_OBFUSCATION_KEYS.filter { it in values }
    val hasExtensions = AMNEZIA_EXTENSION_KEYS.any { it in values }
    if (present.isEmpty() && !hasExtensions) {
        return null
    }

    require(present.isEmpty() || present.size == AMNEZIA_OBFUSCATION_KEYS.size) {
        val missing = AMNEZIA_OBFUSCATION_KEYS.filterNot { it in present }
        "AmneziaWG config is incomplete, missing: ${missing.joinToString(", ")}"
    }
    requireAdvancedSecurityAgrees(peerValues, values)
    val junk = if (present.isEmpty()) null else junkParameters(values, mtu)
    return buildJsonObject {
        put("junk_packet_count", junk?.count ?: 0)
        put("junk_min_size", junk?.minSize ?: 0)
        put("junk_max_size", junk?.maxSize ?: 0)
        put("init_junk_size", junk?.initSize ?: 0)
        put("response_junk_size", junk?.responseSize ?: 0)
        put("cookie_junk_size", amneziaJunkSize(values, "s3"))
        put("transport_junk_size", amneziaJunkSize(values, "s4"))
        putAmneziaHeaders(values, present.isNotEmpty())
        amneziaInitPackets(values)?.let { put("init_packets", it) }
        amneziaTimers(values)?.let { put("timers", it) }
    }
}

private fun requireAdvancedSecurityAgrees(
    peerValues: Map<String, String>,
    interfaceValues: Map<String, String>,
) {
    val declared = peerValues["advancedsecurity"] ?: interfaceValues["advancedsecurity"] ?: return
    require(declared.lowercase() in setOf("on", "true", "1", "yes")) {
        "AmneziaWG AdvancedSecurity is off for this peer while the profile also configures obfuscation"
    }
}

private class AmneziaJunk(
    val count: Int,
    val minSize: Int,
    val maxSize: Int,
    val initSize: Int,
    val responseSize: Int,
)

private fun junkParameters(
    values: Map<String, String>,
    mtu: Int,
): AmneziaJunk {
    val count = amneziaUnsigned16(values, "jc")
    val minSize = amneziaJunkSize(values, "jmin")
    val maxSize = amneziaJunkSize(values, "jmax")
    require(count <= MAX_AMNEZIA_JUNK_PACKET_COUNT) {
        "AmneziaWG Jc must be at most $MAX_AMNEZIA_JUNK_PACKET_COUNT"
    }
    if (count > 0) {
        require(minSize <= maxSize) { "AmneziaWG Jmin must not exceed Jmax" }

        require(maxSize > 0) { "AmneziaWG Jmax must be at least 1 byte when Jc is set" }
        require(maxSize < mtu) {
            "AmneziaWG Jmax ($maxSize) must stay below the profile MTU ($mtu) or every junk packet fragments"
        }
    }
    return AmneziaJunk(
        count = count,
        minSize = minSize,
        maxSize = maxSize,
        initSize = amneziaJunkSize(values, "s1"),
        responseSize = amneziaJunkSize(values, "s2"),
    )
}

private fun amneziaUnsigned16(
    values: Map<String, String>,
    key: String,
): Int {
    val raw = values[key] ?: return 0
    val parsed = raw.toIntOrNull()
    require(parsed != null && parsed in 0..U16_MAX) { "AmneziaWG $key must fit in 16 bits" }
    return parsed
}

private fun amneziaJunkSize(
    values: Map<String, String>,
    key: String,
): Int {
    val size = amneziaUnsigned16(values, key)
    require(size <= MAX_AMNEZIA_JUNK_SIZE) { "AmneziaWG $key must be at most $MAX_AMNEZIA_JUNK_SIZE bytes" }
    return size
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putAmneziaHeaders(
    values: Map<String, String>,
    obfuscated: Boolean,
) {
    val defaults = listOf("h1" to 1L, "h2" to 2L, "h3" to 3L, "h4" to 4L)
    val fields = listOf("header_initiation", "header_response", "header_cookie", "header_transport")
    val ranges =
        defaults.map { (key, fallback) ->
            if (obfuscated) amneziaHeaderRange(values, key) else fallback to fallback
        }

    ranges.indices.forEach { index ->
        ranges.drop(index + 1).forEach { other ->
            require(ranges[index].first > other.second || other.first > ranges[index].second) {
                "AmneziaWG H1..H4 must not overlap"
            }
        }
    }
    fields.forEachIndexed { index, field ->
        val (start, end) = ranges[index]
        put(field, if (start == end) JsonPrimitive(start) else JsonPrimitive("$start-$end"))
    }
}

private fun amneziaHeaderRange(
    values: Map<String, String>,
    key: String,
): Pair<Long, Long> {
    val raw = values[key]?.trim().orEmpty()
    val malformed = "AmneziaWG $key must be N or N-M"
    val separator = raw.indexOf('-')
    if (separator < 0) {
        val single = raw.toLongOrNull()
        require(single != null && single in 0..U32_MAX) { malformed }
        return single to single
    }
    val start = raw.substring(0, separator).toLongOrNull()
    val end = raw.substring(separator + 1).toLongOrNull()
    require(start != null && end != null && start in 0..U32_MAX && end in 0..U32_MAX) { malformed }
    require(end >= start) { "AmneziaWG $key range ends before it starts" }
    return start to end
}

private fun amneziaTimers(values: Map<String, String>): JsonObject? {
    val declared = AMNEZIA_TIMER_KEYS.filter { (key, _) -> key in values }
    if (declared.isEmpty()) {
        return null
    }
    return buildJsonObject {
        declared.forEach { (key, field) ->
            val (start, end) = amneziaTimerRange(values, key)
            put(field, if (start == end) JsonPrimitive(start) else JsonPrimitive("$start-$end"))
        }
    }
}

private fun amneziaTimerRange(
    values: Map<String, String>,
    key: String,
): Pair<Int, Int> {
    val raw = values[key]?.trim().orEmpty()
    val malformed = "AmneziaWG $key must be N or N-M seconds"
    val separator = raw.indexOf('-')
    if (separator < 0) {
        val single = raw.toIntOrNull()
        require(single != null && single in 0..U16_MAX) { malformed }
        return single to single
    }
    val start = raw.substring(0, separator).toIntOrNull()
    val end = raw.substring(separator + 1).toIntOrNull()
    require(start != null && end != null && start in 0..U16_MAX && end in 0..U16_MAX) { malformed }
    require(end >= start) { "AmneziaWG $key range ends before it starts" }
    return start to end
}

private fun amneziaInitPackets(values: Map<String, String>): JsonElement? {
    var seenGap = false
    val specs = mutableListOf<Pair<String, String>>()
    for (index in 1..MAX_AMNEZIA_INIT_PACKETS) {
        val key = "i$index"
        val spec = values[key]
        if (spec == null) {
            seenGap = true
            continue
        }
        require(!seenGap) { "AmneziaWG init packets must be numbered from I1 without gaps" }
        specs += key to spec
    }
    if (specs.isEmpty()) {
        return null
    }
    return buildJsonArray {
        specs.forEach { (key, spec) ->
            add(
                buildJsonObject {
                    put("tags", amneziaInitTags(spec, key))
                },
            )
        }
    }
}

private fun amneziaInitTags(
    spec: String,
    key: String,
): JsonElement {
    val malformed = "AmneziaWG $key template is malformed"
    var rendered = 0
    var rest = spec
    val tags =
        buildJsonArray {
            while (true) {
                val start = rest.indexOf('<')
                if (start < 0) {
                    break
                }
                val end = rest.indexOf('>', startIndex = start)
                require(end > start) { malformed }
                val fields = rest.substring(start + 1, end).trim().split(Regex("\\s+"))
                rest = rest.substring(end + 1)
                val name = fields.firstOrNull()?.takeIf(String::isNotBlank)
                require(name != null) { malformed }
                val argument = fields.getOrNull(1).orEmpty()
                val parsed = amneziaInitTag(name, argument, malformed)
                // Unknown tags must not silently shorten an on-wire template.
                require(parsed != null) { malformed }
                rendered += parsed.second
                add(parsed.first)
            }
        }
    require(tags.isNotEmpty()) { malformed }
    require(rendered <= MAX_AMNEZIA_JUNK_SIZE) {
        "AmneziaWG $key must render at most $MAX_AMNEZIA_JUNK_SIZE bytes"
    }
    requireRendersBytes(rendered, key)
    return tags
}

private fun requireRendersBytes(
    rendered: Int,
    key: String,
) {
    require(rendered > 0) { "AmneziaWG $key renders no bytes, which would be sent as an empty datagram" }
}

private fun amneziaInitTag(
    name: String,
    argument: String,
    malformed: String,
): Pair<JsonObject, Int>? {
    val length = {
        val parsed = argument.toIntOrNull()
        require(parsed != null && parsed in 0..U16_MAX) { malformed }
        parsed
    }
    return when (name) {
        "b" -> {
            val hex = argument.removePrefix("0x")
            require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it.isHexDigit() }) { malformed }
            buildJsonObject {
                put("tag", "bytes")
                put("hex", hex.lowercase())
            } to hex.length / 2
        }

        "t" ->
            buildJsonObject { put("tag", "timestamp") } to TIMESTAMP_TAG_BYTES

        "r" -> sizedTag("random", length()) to length()
        "rc" -> sizedTag("random_letters", length()) to length()
        "rd" -> sizedTag("random_digits", length()) to length()

        "d" -> buildJsonObject { put("tag", "payload") } to 0
        "ds" -> buildJsonObject { put("tag", "payload_base64") } to 0
        "dz" -> sizedTag("payload_size", length()) to length()
        else -> null
    }
}

private fun sizedTag(
    tag: String,
    len: Int,
): JsonObject =
    buildJsonObject {
        put("tag", tag)
        put("len", len)
    }

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun lowercasedFirstValues(section: Map<String, List<String>>): Map<String, String> =
    section
        .asSequence()
        .mapNotNull { (key, values) ->
            values.firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let { key.lowercase() to it }
        }.toMap()
