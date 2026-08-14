package com.foxhole.guard.core.data

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

class RoomValueConverters {
    private val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(ListSerializer, value)

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching { json.decodeFromString(ListSerializer, value) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromStringLongMap(value: Map<String, Long>): String = json.encodeToString(StringLongMapSerializer, value)

    @TypeConverter
    fun toStringLongMap(value: String?): Map<String, Long> {
        if (value.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching { json.decodeFromString(StringLongMapSerializer, value) }.getOrDefault(emptyMap())
    }

    @TypeConverter
    fun fromStringStringMap(value: Map<String, String>): String = json.encodeToString(StringStringMapSerializer, value)

    @TypeConverter
    fun toStringStringMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching { json.decodeFromString(StringStringMapSerializer, value) }.getOrDefault(emptyMap())
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
        val StringLongMapSerializer =
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<Long>(),
            )
        val StringStringMapSerializer =
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<String>(),
            )
    }
}
