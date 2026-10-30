package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsFilterCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer

@Serializable
internal data class CategorizedDnsApkCatalog(
    val schema: Int,
    val variants: Map<String, CategorizedDnsApkEntry>,
)

@Serializable
internal data class CategorizedDnsApkEntry(
    val name: String,
    val path: String,
    val sha256: String,
    val size: ULong,
    @SerialName("block_entries") val blockEntries: ULong,
    @SerialName("category_entries") val categoryEntries: CategorizedDnsCounts,
    @SerialName("source_info_asset") val sourceInfoAsset: String,
) {
    fun availableCategories(): Set<DnsFilterCategory> = DnsFilterCategory.entries
        .filterIndexed { index, _ -> categoryEntries.values()[index] > 0uL }
        .toSet()

    fun requireArtifact(bytes: ByteArray) {
        require(bytes.size.toULong() == size && bytes.isFoxCoreDnsFst())
        require(CategorizedDnsContract.sha256(bytes) == sha256)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.getLong(8).toULong() == blockEntries)
        require(buffer.getLong(16).toULong() <= 5000000uL)
        val blockSize = buffer.getLong(24).toULong()
        val allowSize = buffer.getLong(32).toULong()
        require(blockSize <= size && allowSize <= size && 80uL + blockSize + allowSize == size)
        require(bytes.copyOfRange(72, 80).all { it == 0.toByte() })
        // Native admission derives FST flags and counts; this package-owned catalog is never a signed download.
    }
}

internal enum class CategorizedDnsUnavailable {
    CATALOG_MISSING,
    CATALOG_INVALID,
    REQUIRED_CATEGORY_MISSING,
    SIGNED_DATA_INVALID,
}

internal sealed interface CategorizedDnsPreparation {
    data object Disabled : CategorizedDnsPreparation
    data class Ready(val paths: com.foxhole.core.runtime.DnsFilterRuntimePaths) : CategorizedDnsPreparation
    data class Unavailable(val reason: CategorizedDnsUnavailable) : CategorizedDnsPreparation
}

internal object CategorizedDnsApkContract {
    private val json = Json { ignoreUnknownKeys = false }
    private val variants = setOf("normal-medium", "normal-full", "pro-medium", "pro-full")

    fun parse(bytes: ByteArray): CategorizedDnsApkCatalog {
        require(bytes.size in 1..16384)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.all { it.code in 0..127 })
        val value = json.parseToJsonElement(text)
        require(canonicalAsciiJson(value) + "\n" == text)
        integer(value.jsonObject.getValue("schema"))
        value.jsonObject.getValue("variants").jsonObject.values.forEach { element ->
            val entry = element.jsonObject
            listOf("size", "block_entries").forEach { integer(entry.getValue(it)) }
            entry.getValue("category_entries").jsonObject.values.forEach(::integer)
        }
        val catalog = json.decodeFromString<CategorizedDnsApkCatalog>(text)
        require(catalog.schema == 1 && catalog.variants.keys == variants)
        catalog.variants.forEach { (variant, entry) -> validate(variant, entry) }
        return catalog
    }

    fun select(catalog: CategorizedDnsApkCatalog, selection: CategorizedDnsSelection): CategorizedDnsApkEntry {
        val entry = catalog.variants.getValue(selection.variant)
        require(
            entry.availableCategories().containsAll(selection.categories)
        ) { "required APK DNS category is unavailable" }
        return entry
    }

    private fun validate(variant: String, entry: CategorizedDnsApkEntry) {
        require(entry.name == CategorizedDnsContract.NAME_PREFIX + variant)
        require(entry.path == "dns-categories/$variant/dns-categories.fhds")
        require(entry.sourceInfoAsset == "dns-categories/$variant/source-info.json")
        require(entry.sha256.matches(Regex("[0-9a-f]{64}")))
        require(entry.size in 80uL..67108864uL && entry.blockEntries in 1uL..5000000uL)
        require(entry.categoryEntries.values().any { it > 0uL })
        require(entry.categoryEntries.values().all { it <= entry.blockEntries })
    }

    private fun integer(value: JsonElement) {
        val number = value as? JsonPrimitive ?: error("expected integer")
        require(!number.isString && number.content.matches(Regex("0|[1-9][0-9]*")))
        require(number.content.toULongOrNull() != null)
    }
}
