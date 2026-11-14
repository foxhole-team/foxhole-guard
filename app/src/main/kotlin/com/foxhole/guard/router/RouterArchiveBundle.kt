package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject

internal data class RouterArchivedBundleEntry(val sha256: String, val bytes: Long)

/** Mirrors the native installer's bounded descriptor, including its canonical byte representation. */
internal data class RouterArchiveBundle(
    val archive: RouterBundleAsset,
    val entries: Map<String, RouterArchivedBundleEntry>,
    val workingRamBytes: Long,
) {
    fun materialize(assets: MutableMap<String, RouterBundleAsset>) {
        require(archive.name !in assets)
        for ((name, entry) in entries) {
            val asset = assets[name] ?: continue
            require(asset.contents == null && asset.sha256 == entry.sha256 && asset.bytes == entry.bytes)
            assets.remove(name)
        }
        assets[archive.name] = archive
    }
}

internal fun parseRouterArchiveBundle(bytes: ByteArray, manifest: Map<String, String>): RouterArchiveBundle {
    require(bytes.size in 1..1048576 && routerReleaseSha256(bytes) == manifest["bundle-transport.json"])
    val document = routerCompactDocument(bytes, 1048576)
    document.compactExact("schema", "archive", "entries")
    require(document.compactLong("schema", 1L..1L) == 1L)
    val value = document.compactObject("archive")
    value.compactExact("file", "sha256", "bytes")
    require(value.compactFile("file") == "native-install.tar")
    val hash = value.compactHash("sha256")
    val size = value.compactLong("bytes", 1024L..536870912L)
    require(size % 512L == 0L && manifest["native-install.tar"] == hash)
    val declared = document.compactObject("entries")
    require(declared.size in 1..1024)
    val entries = linkedMapOf<String, RouterArchivedBundleEntry>()
    val folded = mutableSetOf<String>()
    var total = 0L
    var minimum = 1024L
    for ((name, element) in declared) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}")))
        require(folded.add(name.lowercase()) && name.lowercase() !in setOf(
            "sha256sums", "sha256sums.asc", "bundle-transport.json", "native-install.tar"))
        val row = element as? JsonObject ?: error("router_archive_entry")
        row.compactExact("sha256", "bytes")
        val entry = RouterArchivedBundleEntry(row.compactHash("sha256"), row.compactLong("bytes", 0L..134217728L))
        require(manifest[name] == entry.sha256)
        entries[name] = entry
        total = Math.addExact(total, entry.bytes)
        minimum = Math.addExact(minimum, 512L + ((entry.bytes + 511L) / 512L) * 512L)
    }
    require(total <= 2147483648L && size in minimum..Math.addExact(minimum, 10240L))
    val rows = entries.toSortedMap().map { (name, entry) ->
        "\"$name\":{\"bytes\":${entry.bytes},\"sha256\":\"${entry.sha256}\"}"
    }.joinToString(",")
    val canonical = "{\"archive\":{\"bytes\":$size,\"file\":\"native-install.tar\",\"sha256\":\"$hash\"}," +
        "\"entries\":{$rows},\"schema\":1}\n"
    require(bytes.contentEquals(canonical.toByteArray(Charsets.UTF_8)))
    val working = Math.addExact(Math.addExact(size, 16777216L), bytes.size.toLong())
    return RouterArchiveBundle(RouterBundleAsset("native-install.tar", hash, size), entries, working)
}

internal fun routerInstallBundleRequiredRam(workingRamBytes: Long, plan: RouterInstallBundlePlan): Long {
    require(workingRamBytes > 0L)
    return Math.addExact(Math.addExact(workingRamBytes, plan.stagingBytes), plan.transportWorkingRamBytes)
}

internal fun routerInstallBundleRequiredTmp(workingRamBytes: Long, plan: RouterInstallBundlePlan): Long {
    require(workingRamBytes > 0L && plan.residentRuntimeBytes in 0L..workingRamBytes)
    // Resident execution memory is not a tmpfs file; keep staging, backup and reserves in both budgets.
    return Math.addExact(Math.addExact(workingRamBytes - plan.residentRuntimeBytes, plan.stagingBytes), plan.transportWorkingRamBytes)
}
