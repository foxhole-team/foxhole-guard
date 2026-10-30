package com.foxhole.guard.sentinel.nativeengine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val NATIVE_YARA_ENGINE = "yara-x/1.21.0;wasmtime/49.0.2;pulley;profile/sentinel-v2"

internal enum class NativeYaraStatus(val wire: String) {
    COMPLETE("complete"), PARTIAL("partial"), TIMEOUT("timeout"), UNSUPPORTED("unsupported"),
    INCONCLUSIVE("inconclusive"), CANCELLED("cancelled"), ERROR("error"),
}

internal enum class NativeYaraReason(val wire: String) {
    COMPILED("compiled"), SCANNED("scanned"), SOURCE_LIMIT("source_limit"), SOURCE_ENCODING("source_encoding"),
    SYNTAX_BUDGET("syntax_budget"), LITERAL_LIMIT("literal_limit"), RULE_SYNTAX("rule_syntax"),
    RULE_REJECTED("rule_rejected"), INCLUDES_FORBIDDEN("includes_forbidden"), MODULE_FORBIDDEN("module_forbidden"),
    RULE_PATTERN_LIMIT("rule_pattern_limit"), IDENTIFIER_LIMIT("identifier_limit"), EMPTY_RULES("empty_rules"),
    COMPILED_LIMIT("compiled_limit"), DATA_LIMIT("data_limit"), INPUT_LIMIT("input_limit"), INPUT_ERROR("input_error"),
    UNOWNED_COMPILED_BLOB("unowned_compiled_blob"), OWNED_COMPILED_REUSED("owned_compiled_reused"),
    CANCEL_REQUESTED("cancel_requested"), CANCEL_SETTLEMENT_REQUIRED("cancel_requested_settlement_required"),
    SESSION_BUSY("session_busy"), INVALID_SESSION("invalid_session"), SESSION_CLOSED("session_closed"),
    PROCESS_SESSION_CONSUMED("process_session_consumed"), OWNER_ERROR("owner_error"),
    PROCESS_NOT_ISOLATED("process_not_isolated"),
    ENGINE_TIMEOUT("engine_timeout"), ENGINE_ERROR("engine_error"), MATCH_TRACKING_LIMIT("match_tracking_limit"),
    CALLER_INCOMPLETE_INPUT("caller_incomplete_input"), REPORTING_LIMIT("reporting_limit"),
    INVALID_RANGE("invalid_range"), DESCRIPTOR_ERROR("descriptor_error"),
    DESCRIPTOR_NOT_READONLY("descriptor_not_readonly"), DESCRIPTOR_NOT_REGULAR("descriptor_not_regular"),
    TRUNCATED_DESCRIPTOR("truncated_descriptor"), DESCRIPTOR_READ_ERROR("descriptor_read_error"),
    DESCRIPTOR_CHANGED("descriptor_changed"), RESPONSE_LIMIT("response_limit"),
}

internal data class NativeYaraOffset(val start: Long, val length: Long)
internal data class NativeYaraPattern(val patternId: String, val offsets: List<NativeYaraOffset>)
internal data class NativeYaraRule(val ruleId: String, val patterns: List<NativeYaraPattern>)
internal data class NativeYaraReport(
    val sessionId: Long,
    val status: NativeYaraStatus,
    val reason: NativeYaraReason,
    val engine: String,
    val coverageComplete: Boolean,
    val requestedBytes: Long,
    val scannedBytes: Long,
    val elapsedMillis: Long,
    val reportingTruncated: Boolean,
    val diagnosticCount: Int,
    val diagnostics: List<String>,
    val matches: List<NativeYaraRule>,
) {
    val cancellationRequiresSettlement: Boolean
        get() = reason == NativeYaraReason.CANCEL_SETTLEMENT_REQUIRED

    companion object {
        fun decode(bytes: ByteArray): NativeYaraReport {
            require(bytes.size <= 256 * 1024) { "Native response limit" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            checkJsonStructure(text)
            val element = Json.parseToJsonElement(text)
            // Native serde emits compact JSON; canonical equality also refuses duplicate keys.
            require(element.toString() == text) { "Noncanonical native JSON" }
            val json = element.jsonObject
            json.exactKeys(
                "protocolVersion", "sessionId", "status", "reason", "engine", "coverageComplete",
                "requestedBytes", "scannedBytes", "elapsedMillis", "reportingTruncated", "diagnosticCount", "diagnostics", "matches"
            )
            require(json.getInt("protocolVersion") == 1) { "Native protocol version" }
            val status = NativeYaraStatus.entries.single { it.wire == json.getString("status") }
            val reason = NativeYaraReason.entries.single { it.wire == json.getString("reason") }
            require(reason in allowedReasons(status)) { "Native status reason mismatch" }
            val sessionId = json.getLong("sessionId").also { require(it in 0..1) }
            val complete = json.getBoolean("coverageComplete")
            val requested = json.getLong("requestedBytes")
            val scanned = json.getLong("scannedBytes").also { require(it <= requested && it <= 16_777_216) }
            val truncated = json.getBoolean("reportingTruncated")
            require(complete == (status == NativeYaraStatus.COMPLETE && reason == NativeYaraReason.SCANNED))
            require(!complete || (sessionId == 1L && scanned == requested && !truncated))
            require(truncated == (reason == NativeYaraReason.REPORTING_LIMIT))
            val diagnostics = decodeDiagnostics(json)
            val diagnosticCount = json.getInt("diagnosticCount").also { require(it in diagnostics.size..32_768) }
            require(diagnostics.size == minOf(diagnosticCount, 8))
            val rules = decodeRules(json.getJSONArray("matches"), scanned)
            require(
                rules.isEmpty() || reason in setOf(NativeYaraReason.SCANNED, NativeYaraReason.CALLER_INCOMPLETE_INPUT)
            )
            requireCompiledReportConsistent(reason, sessionId, requested, scanned, rules)

            return NativeYaraReport(
                sessionId, status, reason,
                json.getString("engine").also { require(it == NATIVE_YARA_ENGINE) }, complete,
                requested, scanned, json.getLong("elapsedMillis"), truncated, diagnosticCount, diagnostics, rules
            )
        }
    }
}

private fun JsonPrimitive.stringValue(): String = content.also { require(isString) }
private fun JsonObject.getString(key: String): String = getValue(key).jsonPrimitive.stringValue()
private val unsignedInteger = Regex("0|[1-9][0-9]*")
private fun JsonObject.getLong(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString && it.content.matches(unsignedInteger))
    it.long.also { value -> require(value >= 0) }
}
private fun JsonObject.getInt(key: String): Int = getLong(
    key
).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun JsonObject.getBoolean(key: String): Boolean = getValue(
    key
).jsonPrimitive.let {
    require(!it.isString)
    it.boolean
}
private fun JsonObject.getJSONArray(key: String): JsonArray = getValue(key).jsonArray
private fun JsonArray.length(): Int = size
private fun JsonArray.getString(index: Int): String = get(index).jsonPrimitive.stringValue()
private fun JsonArray.getJSONObject(index: Int): JsonObject = get(index).jsonObject

private fun JsonObject.exactKeys(vararg keys: String) {
    require(
        this.keys == keys.toSet()
    ) { "Unexpected native fields" }
}

private fun checkJsonStructure(text: String) {
    var quoted = false
    var escaped = false
    var depth = 0
    var containers = 0
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    containers++
                    require(depth <= 8 && containers <= 2048)
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

private fun decodeDiagnostics(json: JsonObject): List<String> {
    val values = json.getJSONArray("diagnostics")
    require(values.size <= 8)
    return List(
        values.size
    ) { values.getString(it).also { code -> require(code in setOf("rule_syntax", "rule_rejected")) } }
}

private fun decodeRules(values: JsonArray, scanned: Long): List<NativeYaraRule> {
    require(values.size <= 64)
    var offsets = 0
    return List(values.size) { ruleIndex ->
        val rule = values.getJSONObject(ruleIndex).also { it.exactKeys("ruleId", "patterns") }
        val id = rule.getString(
            "ruleId"
        ).also { require(it.length <= 128 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) }
        val patterns = rule.getJSONArray("patterns").also { require(it.size <= 8) }
        NativeYaraRule(
            id,
            List(patterns.size) { patternIndex ->
                val pattern = patterns.getJSONObject(patternIndex).also { it.exactKeys("patternId", "offsets") }
                val name = pattern.getString(
                    "patternId"
                ).also { require(it.length <= 128 && it.matches(Regex("\\$[A-Za-z0-9_]*"))) }
                val positions = pattern.getJSONArray("offsets")
                offsets += positions.size
                require(offsets <= 128)
                NativeYaraPattern(
                    name,
                    List(positions.size) { offsetIndex ->
                        val offset = positions.getJSONObject(offsetIndex).also { it.exactKeys("start", "length") }
                        val start = offset.getLong("start").also { require(it <= scanned) }
                        val length = offset.getLong("length").also { require(it <= scanned - start) }
                        NativeYaraOffset(start, length)
                    }
                )
            }
        )
    }
}

private fun allowedReasons(status: NativeYaraStatus): Set<NativeYaraReason> = when (status) {
    NativeYaraStatus.COMPLETE -> setOf(
        NativeYaraReason.COMPILED,
        NativeYaraReason.SCANNED,
        NativeYaraReason.OWNED_COMPILED_REUSED
    )
    NativeYaraStatus.PARTIAL -> setOf(
        NativeYaraReason.CALLER_INCOMPLETE_INPUT,
        NativeYaraReason.REPORTING_LIMIT,
        NativeYaraReason.TRUNCATED_DESCRIPTOR,
        NativeYaraReason.DESCRIPTOR_CHANGED
    )
    NativeYaraStatus.TIMEOUT -> setOf(NativeYaraReason.ENGINE_TIMEOUT)
    NativeYaraStatus.UNSUPPORTED -> setOf(
        NativeYaraReason.SOURCE_ENCODING, NativeYaraReason.INCLUDES_FORBIDDEN, NativeYaraReason.MODULE_FORBIDDEN, NativeYaraReason.EMPTY_RULES,
        NativeYaraReason.UNOWNED_COMPILED_BLOB, NativeYaraReason.PROCESS_SESSION_CONSUMED, NativeYaraReason.PROCESS_NOT_ISOLATED, NativeYaraReason.DESCRIPTOR_NOT_READONLY, NativeYaraReason.DESCRIPTOR_NOT_REGULAR
    )
    NativeYaraStatus.INCONCLUSIVE -> setOf(
        NativeYaraReason.SOURCE_LIMIT, NativeYaraReason.SYNTAX_BUDGET, NativeYaraReason.LITERAL_LIMIT, NativeYaraReason.RULE_PATTERN_LIMIT,
        NativeYaraReason.IDENTIFIER_LIMIT, NativeYaraReason.COMPILED_LIMIT, NativeYaraReason.DATA_LIMIT, NativeYaraReason.INPUT_LIMIT, NativeYaraReason.MATCH_TRACKING_LIMIT
    )
    NativeYaraStatus.CANCELLED -> setOf(NativeYaraReason.CANCEL_REQUESTED, NativeYaraReason.CANCEL_SETTLEMENT_REQUIRED)
    NativeYaraStatus.ERROR -> setOf(
        NativeYaraReason.RULE_SYNTAX, NativeYaraReason.RULE_REJECTED, NativeYaraReason.INPUT_ERROR, NativeYaraReason.SESSION_BUSY, NativeYaraReason.INVALID_SESSION,
        NativeYaraReason.SESSION_CLOSED, NativeYaraReason.OWNER_ERROR, NativeYaraReason.ENGINE_ERROR, NativeYaraReason.INVALID_RANGE, NativeYaraReason.DESCRIPTOR_ERROR,
        NativeYaraReason.DESCRIPTOR_READ_ERROR, NativeYaraReason.RESPONSE_LIMIT
    )
}

private fun requireCompiledReportConsistent(
    reason: NativeYaraReason,
    sessionId: Long,
    requested: Long,
    scanned: Long,
    rules: List<NativeYaraRule>,
) {
    if (reason in setOf(NativeYaraReason.COMPILED, NativeYaraReason.OWNED_COMPILED_REUSED)) {
        require(sessionId == 1L && requested == 0L && scanned == 0L && rules.isEmpty())
    }
}
