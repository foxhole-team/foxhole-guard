package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsFilterCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.security.MessageDigest

@Serializable
internal data class CategorizedDnsManifest(
    val schema: Int,
    val name: String,
    val format: String,
    val sequence: ULong,
    @SerialName("generated_at_unix") val generatedAtUnix: ULong,
    @SerialName("expires_at_unix") val expiresAtUnix: ULong,
    @SerialName("key_sha256") val keySha256: String,
    val categories: List<String>,
    val tiers: CategorizedDnsTiers,
    @SerialName("tagged_input_sha256") val taggedInputSha256: String,
    val sources: List<CategorizedDnsSource>,
    val artifact: CategorizedDnsArtifact,
    val compatibility: CategorizedDnsCompatibility,
)

@Serializable
internal data class CategorizedDnsTiers(val trackers: String, val malicious: String) {
    fun variant(): String {
        require(trackers in setOf("normal", "pro") && malicious in setOf("medium", "full"))
        return "$trackers-$malicious"
    }
}

@Serializable
internal data class CategorizedDnsSource(
    val id: String,
    val repo: String,
    val commit: String,
    val license: String,
    val inputs: List<CategorizedDnsInput>,
    val categories: List<String>,
)

@Serializable
internal data class CategorizedDnsInput(val path: String, val sha256: String)

@Serializable
internal data class CategorizedDnsArtifact(
    val file: String,
    val size: ULong,
    val sha256: String,
    @SerialName("block_entries") val blockEntries: ULong,
    @SerialName("allow_entries") val allowEntries: ULong,
    @SerialName("category_entries") val categoryEntries: CategorizedDnsCounts,
)

@Serializable
internal data class CategorizedDnsCounts(
    val malicious: ULong,
    val telemetry: ULong,
    val trackers: ULong,
    val ads: ULong,
) {
    fun values(): List<ULong> = listOf(malicious, telemetry, trackers, ads)
}

@Serializable
internal data class CategorizedDnsCompatibility(
    @SerialName("core_schema") val coreSchema: Int,
    @SerialName("category_schema") val categorySchema: Int,
)

internal object CategorizedDnsContract {
    val categoryNames = listOf("malicious", "telemetry", "trackers", "ads")
    const val NAME_PREFIX = "foxhole-dns-categories-"
    const val ARTIFACT_NAME = "dns-categories.fhds"
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val commitPattern = Regex("[0-9a-f]{40}")
    private val idPattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    fun parseVerifiedManifest(bytes: ByteArray, nowUnix: ULong, keySha256: String): CategorizedDnsManifest {
        require(bytes.size in 1..65536)
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.all { it.code in 0..127 })
        val element = json.parseToJsonElement(text)
        require((canonicalAsciiJson(element) + "\n") == text) { "categorized manifest is not canonical" }
        requireExactNumericTokens(element.jsonObject)
        val manifest = json.decodeFromString<CategorizedDnsManifest>(text)
        validate(manifest, nowUnix, keySha256)
        return manifest
    }

    private fun validate(manifest: CategorizedDnsManifest, now: ULong, keySha256: String) {
        require(manifest.schema == 3 && manifest.format == "foxhole-dns-fst-v1")
        require(manifest.name == NAME_PREFIX + manifest.tiers.variant())
        require(manifest.sequence > 0uL && manifest.generatedAtUnix > 0uL)
        require(manifest.generatedAtUnix <= now.saturatingAdd(600uL))
        require(manifest.expiresAtUnix > manifest.generatedAtUnix)
        require(manifest.expiresAtUnix - manifest.generatedAtUnix <= 2678400uL && now < manifest.expiresAtUnix)
        require(manifest.keySha256 == keySha256 && hashPattern.matches(manifest.keySha256))
        require(hashPattern.matches(manifest.taggedInputSha256))
        requireCategories(manifest.categories)
        require(
            manifest.sources.size in 1..16 && manifest.sources.map { it.id }.distinct().size == manifest.sources.size
        )
        manifest.sources.forEach(::requireSource)
        require(manifest.sources.map { it.id } == manifest.sources.map { it.id }.sorted())
        val contributing = manifest.sources.flatMap { it.categories }.toSet()
        require(manifest.categories.toSet() == contributing)
        val artifact = manifest.artifact
        require(artifact.file == ARTIFACT_NAME && artifact.size in 80uL..67108864uL)
        require(hashPattern.matches(artifact.sha256))
        require(artifact.blockEntries in 1uL..5000000uL && artifact.allowEntries <= 5000000uL)
        artifact.categoryEntries.values().forEachIndexed { index, count ->
            require(count <= artifact.blockEntries)
            require((categoryNames[index] in manifest.categories) == (count > 0uL))
        }
        require(manifest.compatibility.coreSchema == 1 && manifest.compatibility.categorySchema == 1)
    }

    private fun requireSource(source: CategorizedDnsSource) {
        require(idPattern.matches(source.id) && commitPattern.matches(source.commit))
        require(source.repo.length in 1..512 && source.repo.all { it.code in 32..126 })
        val uri = URI(source.repo)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(source.license.length in 1..128 && source.license.all { it.code in 32..126 })
        require(source.inputs.size in 1..16 && source.inputs.map { it.path }.distinct().size == source.inputs.size)
        require(source.inputs.map { it.path } == source.inputs.map { it.path }.sorted())
        source.inputs.forEach { input ->
            require(input.path.length in 1..256 && input.path.all { it.code in 33..126 })
            require(
                !input.path.startsWith('/') && '\\' !in input.path && input.path.split('/').none { it in setOf("", ".", "..") }
            )
            require(hashPattern.matches(input.sha256))
        }
        requireCategories(source.categories)
    }

    private fun requireCategories(names: List<String>) {
        require(names.size in 1..4 && names == categoryNames.filter { it in names })
    }

    private fun requireExactNumericTokens(root: JsonObject) {
        listOf(
            "schema",
            "sequence",
            "generated_at_unix",
            "expires_at_unix"
        ).forEach { requireInteger(root.getValue(it)) }
        val artifact = root.getValue("artifact").jsonObject
        listOf("size", "block_entries", "allow_entries").forEach { requireInteger(artifact.getValue(it)) }
        artifact.getValue("category_entries").jsonObject.values.forEach(::requireInteger)
        root.getValue("compatibility").jsonObject.values.forEach(::requireInteger)
    }

    private fun requireInteger(value: JsonElement) {
        val primitive = value as? JsonPrimitive ?: error("expected integer")
        require(!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]*")))
        require(primitive.content.toULongOrNull() != null)
    }

    fun categories(manifest: CategorizedDnsManifest): Set<DnsFilterCategory> =
        manifest.categories.map { DnsFilterCategory.valueOf(it.uppercase()) }.toSet()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun ULong.saturatingAdd(other: ULong): ULong = if (ULong.MAX_VALUE - this < other) ULong.MAX_VALUE else this + other
}

internal fun canonicalAsciiJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
        JsonPrimitive(key).toString() + ":" + canonicalAsciiJson(value)
    }
    is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalAsciiJson)
    else -> element.toString()
}
