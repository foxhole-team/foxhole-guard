package com.foxhole.core.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.DocumentStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent

private sealed interface ProfileYamlFrame
private class ProfileYamlArray(val values: MutableList<JsonElement> = mutableListOf()) : ProfileYamlFrame
private class ProfileYamlObject(
    val values: MutableMap<String, JsonElement> = linkedMapOf(),
    var key: String? = null
) : ProfileYamlFrame

internal fun parseBoundedProfileYaml(raw: String): JsonElement {
    require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "YAML profile is too large" }
    val settings = LoadSettings.builder().setCodePointLimit(1024 * 1024).setMaxAliasesForCollections(0).build()
    return try {
        ProfileYamlReader().read(Parse(settings).parseString(raw))
    } catch (_: org.snakeyaml.engine.v2.exceptions.YamlEngineException) {
        throw IllegalArgumentException("invalid YAML profile")
    }
}

private class ProfileYamlReader {
    private val stack = mutableListOf<ProfileYamlFrame>()
    private var result: JsonElement? = null
    private var events = 0
    private var documents = 0

    fun read(input: Iterable<Event>): JsonElement {
        input.forEach { event ->
            require(++events <= 16384 && stack.size <= 32) { "YAML profile exceeds parser limits" }
            require(event !is AliasEvent && (event !is NodeEvent || !event.anchor.isPresent)) {
                "YAML references are not supported"
            }
            accept(event)
        }
        require(stack.isEmpty()) { "incomplete YAML profile" }
        return requireNotNull(result) { "empty YAML profile" }
    }

    private fun accept(event: Event) {
        when (event) {
            is DocumentStartEvent -> require(++documents == 1) { "multiple YAML documents are not supported" }
            is CollectionStartEvent -> startCollection(event)
            is ScalarEvent -> appendScalar(event)
            is MappingEndEvent -> finishMapping()
            is SequenceEndEvent -> finishSequence()
        }
    }

    private fun startCollection(event: CollectionStartEvent) {
        require(!event.tag.isPresent) { "YAML tags are not supported" }
        require(stack.size < 32) { "YAML profile is too deep" }
        stack += if (event is MappingStartEvent) ProfileYamlObject() else ProfileYamlArray()
    }

    private fun appendScalar(event: ScalarEvent) {
        require(!event.tag.isPresent || event.tag.get() == "tag:yaml.org,2002:str") { "YAML tags are not supported" }
        require(event.value.length <= 16384) { "YAML scalar is too large" }
        // Keep scalar spelling so numeric passwords do not lose leading zeros.
        append(JsonPrimitive(event.value))
    }

    private fun finishMapping() {
        val frame = stack.removeLastOrNull() as? ProfileYamlObject ?: error("invalid YAML mapping")
        require(frame.key == null) { "incomplete YAML mapping" }
        append(JsonObject(frame.values))
    }

    private fun finishSequence() {
        val frame = stack.removeLastOrNull() as? ProfileYamlArray ?: error("invalid YAML sequence")
        append(JsonArray(frame.values))
    }

    private fun append(value: JsonElement) {
        when (val frame = stack.lastOrNull()) {
            null -> {
                require(result == null) { "multiple YAML roots are not supported" }
                result = value
            }
            is ProfileYamlArray -> frame.values += value
            is ProfileYamlObject -> appendObject(frame, value)
        }
    }

    private fun appendObject(frame: ProfileYamlObject, value: JsonElement) {
        val key = frame.key
        if (key == null) {
            require(value is JsonPrimitive && value.isString && value.content.length in 1..128) { "invalid YAML key" }
            require(value.content !in frame.values) { "duplicate YAML field" }
            frame.key = value.content
        } else {
            frame.values[key] = value
            frame.key = null
        }
    }
}
