package com.foxhole.guard.router

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Published catalog bytes and derived signed-index relationships; no network/native acceptance is implied. */
internal object ReleasedRouterBootstrapFixtures {
    private val hashes = mapOf(
        "openwrt-1.0.6-catalog.json" to "329f68eb598745b895bf5a08ddcc2774eb288fdd20fc01dd68fb53fa15256b72",
        "openwrt-1.0.5-catalog.json" to "78ebabbe2dffeb4ecd3c7d7dae18adbf17da7587d314e732f2cf472eaba9a728",
        "openwrt-1.0.6-SHA256SUMS" to "b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d",
        "core-1.0.4-index.json" to "53fd09127118ae8ec9376be87fb13ae2e24689b7efe8aa154cab3ad4e9e39ad6",
        "core-1.0.4-auth-relationships.json" to "1844b2768f22ec2dbdabed1655d5d43fc8d352858db68fc8cf5536a128bd96f3"
    )
    private const val MANIFEST = "b7b4294e9fc2b04fdb41660303dc09d5617de183fc05ace2c74b5039108be00d"
    fun bytes(name: String): ByteArray {
        val body = requireNotNull(javaClass.getResourceAsStream("/router-bootstrap-releases/" + name)).use { it.readBytes() }
        require(sha(body) == hashes[name])
        return body
    }
    fun document(name: String): JsonObject = Json.parseToJsonElement(bytes(name).toString(Charsets.UTF_8)).jsonObject
    fun current(): JsonObject = document("openwrt-1.0.6-catalog.json")
    fun auth(): JsonObject = document("core-1.0.4-auth-relationships.json")
    fun parse(value: JsonObject): RouterInstallCatalog = value.toString().toByteArray().let {
        RouterInstallCatalog.parseVerified(it, sha(it), MANIFEST)
    }
    fun actual(name: String): RouterInstallCatalog = bytes(name).let {
        RouterInstallCatalog.parseVerified(it, requireNotNull(hashes[name]), MANIFEST)
    }
    fun entry(value: JsonObject, position: Int = 0, block: (JsonObject) -> JsonObject): JsonObject {
        val platforms = value["platforms"]!!.jsonArray.toMutableList()
        val platform = platforms[0].jsonObject
        val clients = platform["clients"]!!.jsonArray.toMutableList()
        clients[position] = clients[position].jsonObject.let { it.with("entry", block(it["entry"]!!.jsonObject)) }
        platforms[0] = platform.with("clients", JsonArray(clients))
        return value.with("platforms", JsonArray(platforms))
    }
    fun origin(entry: JsonObject, repository: String, tag: String): JsonObject {
        val base = "https://github.com/foxhole-team/$repository/releases/download/v$tag/"
        return entry.with("indexUrl", JsonPrimitive(base + "engines.adb"))
            .with("packageUrl", JsonPrimitive(base + entry["packageUrl"]!!.jsonPrimitive.content.substringAfterLast('/')))
    }
    fun index(value: JsonObject, file: String, url: String): JsonObject = value.with("repositoryIndexes", JsonArray(
        value["repositoryIndexes"]!!.jsonArray.map { if (it.jsonObject["file"]!!.jsonPrimitive.content == file)
            it.jsonObject.with("sourceUrl", JsonPrimitive(url)) else it }))
    fun verify(value: JsonObject, extraManifest: Map<String, String> = emptyMap()): RouterRepositoryBytes {
        val manifest = bytes("openwrt-1.0.6-SHA256SUMS").toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }
            .associate { it.substring(66) to it.substring(0, 64) }
        val owned = value["rootPackages"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        return verifyRouterCompactRepositoryAuthentication(value, value["packages"]!!.jsonArray.map { it.jsonObject },
            manifest + extraManifest, "x86_64", owned)
    }
    fun twoModuleAuthentication(mixed: Boolean): Pair<JsonObject, Map<String, String>> {
        val budget = auth()
        val client = current()["platforms"]!!.jsonArray.map { it.jsonObject }.single {
            it["architecture"]!!.jsonPrimitive.content == "x86_64"
        }["clients"]!!.jsonArray.map { it.jsonObject }.single {
            it["id"]!!.jsonPrimitive.content == "vless" && it["storage"]!!.jsonPrimitive.content == "flash"
        }["entry"]!!.jsonObject
        val name = client["packageName"]!!.jsonPrimitive.content
        val file = client["packageUrl"]!!.jsonPrimitive.content.substringAfterLast('/')
        val identity = document("core-1.0.4-index.json")["packages"]!!.jsonArray.map { it.jsonObject }.single {
            it["name"]!!.jsonPrimitive.content == name
        }["hashes"]!!
        val alias = "engines-alias.adb"
        val original = budget["repositoryIndexes"]!!.jsonArray.map { it.jsonObject }.single { it["file"]!!.jsonPrimitive.content == "engines.adb" }
        val url = if (mixed) "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6/engines.adb"
            else original["sourceUrl"]!!.jsonPrimitive.content
        val added = original.with("file", JsonPrimitive(alias)).with("sourceUrl", JsonPrimitive(url))
        val pkg = buildJsonObject {
            put("file", file); put("name", name); put("version", client["packageVersion"]!!)
            put("arch", "x86_64"); put("sha256", client["packageSha256"]!!)
        }
        val binding = buildJsonObject {
            put("file", file); put("name", name); put("version", client["packageVersion"]!!)
            put("architecture", "x86_64"); put("indexFile", alias); put("packageIdentity", identity)
        }
        val value = budget.with("packages", JsonArray(budget["packages"]!!.jsonArray + pkg))
            .with("packageAuthentications", JsonArray(budget["packageAuthentications"]!!.jsonArray + binding))
            .with("repositoryIndexes", JsonArray(budget["repositoryIndexes"]!!.jsonArray + added))
            .with("rootPackages", JsonArray(budget["rootPackages"]!!.jsonArray + JsonPrimitive(name)))
            .with("repositoryIndexBytes", JsonPrimitive(budget["repositoryIndexBytes"]!!.jsonPrimitive.long + original["bytes"]!!.jsonPrimitive.long))
        return value.with("filesSha256", value["filesSha256"]!!.jsonObject.with(file, client["packageSha256"]!!)
            .with(alias, original["sha256"]!!)) to mapOf(alias to original["sha256"]!!.jsonPrimitive.content)
    }
    fun JsonObject.with(key: String, value: JsonElement) = JsonObject(toMutableMap().also { it[key] = value })
    private fun sha(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
}
