package com.foxhole.guard.core.sentinel.scanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class ScannerRuleCatalog(val sha256: String, val ruleIds: Set<String>, val mitreCandidates: Map<String, Set<String>>)

internal fun validateScannerRuleCatalog(catalogBytes: ByteArray, source: ByteArray): ScannerRuleCatalog {
    require(catalogBytes.size in 1..262144 && source.size in 1..262144)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(catalogBytes)).toString()
    requireScannerCatalogDepth(text)
    val parsed = Json.parseToJsonElement(text)
    require(parsed.toString() == text) { "Noncanonical scanner catalog" }
    val catalog = parsed.objectValue()
    require(catalog.keys == CATALOG_FIELDS)
    require(catalog.number("schema") == "1" && catalog.number("maxSourceBytes") == "262144")
    require(
        catalog.text("scope") == "verified_classic_dex_only" && catalog.text("semantics") == "reference_presence_only"
    )
    require(catalog.text("bundleId").matches(Regex("[A-Za-z0-9_.-]{1,96}")))
    require(catalog.text("license") == "GPL-3.0-or-later")
    require(catalog.text("mitreCatalogVersion") == "19.2")
    require(catalog.text("mitreCatalogSource") == "https://attack.mitre.org/resources/versions/")
    val hash = catalog.text("sourceSha256")
    require(hash.matches(Regex("[0-9a-f]{64}")) && scannerRuleDigest(source) == hash)
    val rules = requireNotNull(catalog["rules"] as? JsonArray)
    require(rules.size in 1..64)
    val entries = rules.map { value -> decodeScannerCatalogRule(value.objectValue()) }
    require(entries.map { it.first }.toSet().size == entries.size)
    val declared = Regex("(?m)^rule ([A-Za-z0-9_]{1,64})[ \t]*[{]").findAll(source.decodeToString()).map {
        it.groupValues[1]
    }.toSet()
    require(declared == entries.map { it.first }.toSet())
    return ScannerRuleCatalog(hash, declared, entries.toMap())
}

private fun decodeScannerCatalogRule(value: JsonObject): Pair<String, Set<String>> {
    val optional = setOf("mitreSource", "mitreTechniqueVersion")
    require(value.keys.containsAll(RULE_FIELDS) && value.keys.all { it in RULE_FIELDS || it in optional })
    val id = value.text("id").also { require(it.matches(Regex("[A-Za-z0-9_]{1,64}"))) }
    require(value.text("observation") in OBSERVATIONS)
    require(value.text("claim").length in 1..512)
    val raw = value["candidateMitre"] as? JsonArray ?: throw IllegalArgumentException("Invalid candidate set")
    require(raw.size <= 1)
    val ids = raw.map {
        (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: throw IllegalArgumentException("Invalid candidate")
    }.toSet()
    require(
        ids.isEmpty() || ids == setOf("T1407") && value.text("mitreSource") == "https://attack.mitre.org/techniques/T1407/" && value.text("mitreTechniqueVersion") == "1.5"
    )
    return id to ids
}

private fun JsonObject.text(key: String): String {
    val value = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("Missing catalog field")
    require(value.isString)
    return value.content
}
private fun JsonObject.number(key: String): String {
    val value = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("Missing catalog number")
    require(!value.isString)
    return value.content
}
private fun requireScannerCatalogDepth(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= 8)
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0)
                }
            }
        }
    }
    require(!quoted && depth == 0)
}

internal fun scannerRuleDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun readBoundedScannerSource(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(count > 0 && output.size() <= 262144 - count) { "Scanner source limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
private val CATALOG_FIELDS =
    setOf("schema", "bundleId", "scope", "semantics", "license", "maxSourceBytes", "sourceSha256", "rules", "mitreCatalogVersion", "mitreCatalogSource")
private val RULE_FIELDS = setOf("id", "observation", "candidateMitre", "claim")
private val OBSERVATIONS =
    setOf("DEX_CLASS_LOADER_REFERENCE", "RUNTIME_EXEC_REFERENCE", "ACCESSIBILITY_API_REFERENCE", "SMS_API_REFERENCE")

private fun JsonElement.objectValue(): JsonObject = requireNotNull(this as? JsonObject)
