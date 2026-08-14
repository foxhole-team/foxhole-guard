package com.foxhole.core.runtime

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * On-disk home of the updatable Tor bridge list. The bundled `pt_config.json` is the fallback; a
 * successfully installed download (normalized groups JSON + metadata) takes precedence in
 * [TorRuntimeInstaller.writeRuntimeTorrcDefaults] via [buildBridgeTorrcLines]. Installation
 * validates every line and is atomic (temp file + rename); the "last updated / success" status is
 * tracked separately in settings (PrivacyRouteSettings.bridges*).
 */
@Serializable
data class TorBridgeMetadata(
    val source: String,
    @SerialName("downloaded_at_ms") val downloadedAtMs: Long,
    @SerialName("bridge_count") val bridgeCount: Int,
    @SerialName("recommended_default") val recommendedDefault: String? = null,
)

class TorBridgeStore(
    context: Context,
    private val json: Json,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    /** The normalized groups JSON consumed by [buildBridgeTorrcLines], or null when none downloaded. */
    fun readGroupsJsonOrNull(): String? =
        bridgesFile()
            .takeIf(File::isFile)
            ?.let { file -> runCatching { file.readText() }.getOrNull() }
            ?.takeIf(String::isNotBlank)

    // Content-derived key so a changed list forces a torrc-defaults rewrite; empty when no override.
    fun payloadFingerprint(): String = readGroupsJsonOrNull()?.hashCode()?.toString() ?: ""

    fun readMetadata(): TorBridgeMetadata? =
        metadataFile()
            .takeIf(File::isFile)
            ?.let { file -> runCatching { json.decodeFromString<TorBridgeMetadata>(file.readText()) }.getOrNull() }

    /**
     * Validates a raw bridge payload (a bare group object, a `{"bridges": {...}}` wrapper, possibly
     * with `recommendedDefault`) and, when it holds at least one well-formed bridge line, installs a
     * normalized `{"recommendedDefault", "bridges"}` document. Throws when the payload is malformed
     * or empty so a bad download never replaces a working list.
     */
    fun install(
        rawPayload: String,
        source: String,
    ): TorBridgeMetadata {
        require(rawPayload.length <= MAX_PAYLOAD_BYTES) { "bridge payload too large: ${rawPayload.length} bytes" }
        val root = json.parseToJsonElement(rawPayload).jsonObject
        val recommended = root["recommendedDefault"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        val groupsObject =
            root["bridges"]?.jsonObject
                ?: root.takeIf { obj -> obj.values.isNotEmpty() && obj.values.all { it is JsonArray } }
                ?: error("bridge payload has no bridge groups")
        val groups =
            groupsObject
                .mapNotNull { (key, value) ->
                    val lines =
                        (value as? JsonArray)
                            ?.mapNotNull { line -> line.jsonPrimitive.contentOrNull?.trim()?.takeIf(::isBridgeLine) }
                            .orEmpty()
                    if (lines.isEmpty()) null else key to lines
                }.toMap()
        val bridgeCount = groups.values.sumOf { it.size }
        require(bridgeCount >= MIN_BRIDGE_LINES) { "bridge payload has no usable bridges" }
        val normalized =
            buildJsonObject {
                recommended?.let { put("recommendedDefault", it) }
                put(
                    "bridges",
                    buildJsonObject {
                        groups.forEach { (key, lines) ->
                            put(
                                key,
                                buildJsonArray { lines.forEach { line -> add(json.parseToJsonElement("\"$line\"")) } }
                            )
                        }
                    },
                )
            }
        writeAtomically(
            bridgesFile(),
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), normalized)
        )
        val metadata =
            TorBridgeMetadata(
                source = source,
                downloadedAtMs = nowProvider(),
                bridgeCount = bridgeCount,
                recommendedDefault = recommended,
            )
        writeAtomically(metadataFile(), json.encodeToString(TorBridgeMetadata.serializer(), metadata))
        return metadata
    }

    fun clearOverride(): Boolean = bridgeDirectory().deleteRecursively()

    private fun writeAtomically(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun bridgeDirectory(): File = File(appContext.filesDir, BRIDGE_DIR_NAME)

    private fun bridgesFile(): File = File(bridgeDirectory(), BRIDGES_FILE_NAME)

    private fun metadataFile(): File = File(bridgeDirectory(), METADATA_FILE_NAME)

    companion object {
        const val BRIDGE_DIR_NAME = "tor-bridges"
        const val BRIDGES_FILE_NAME = "bridges.json"
        const val METADATA_FILE_NAME = "bridges-meta.json"
        const val MIN_BRIDGE_LINES = 1
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        // A usable line is "<transport> <host:port> …", e.g. "obfs4 1.2.3.4:443 FINGERPRINT cert=…".
        private val BRIDGE_LINE_PREFIX = Regex("""^[A-Za-z0-9_-]+\s+\S+:\d+""")

        fun isBridgeLine(line: String): Boolean = BRIDGE_LINE_PREFIX.containsMatchIn(line.trim())
    }
}
