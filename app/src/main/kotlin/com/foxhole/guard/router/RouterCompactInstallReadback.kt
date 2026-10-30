package com.foxhole.guard.router

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A schema-2 readback binds the actual whole selected closure; no legacy Core or implicit server role. */
internal fun verifyRouterCompactInstalledPackage(bytes: ByteArray, binding: RouterInstallBinding,
    system: RouterSystemInspection, packages: List<RouterPackageIdentity>) {
    val compact = requireNotNull(binding.compact)
    val platform = compact.platform
    val value = routerCompactDocument(bytes, 524288)
    value.compactExact("schema", "kind", "architecture", "supportedTargets", "supportedOpenWrt", "protocols", "includeServer",
        "mode", "catalogSha256", "engineCatalogSha256", "basePackage", "modules", "server", "packages")
    require(value.compactLong("schema") == 2L && value.compactText("kind") == "compact-installed-package-v1" &&
        value.compactText("architecture") == binding.architecture && value.compactHash("catalogSha256") == binding.selection.catalogSha256 &&
        value.compactHash("engineCatalogSha256") == platform.engineCatalog.sha256 && value.compactText("mode") == binding.storageMode.name.lowercase() &&
        value.compactBoolean("includeServer") == binding.selection.includeServer &&
        value.compactStrings("protocols", 1..12).toSet() == binding.selection.protocols.map { it.canonical }.toSet() &&
        value.compactStrings("supportedTargets", 1..1).toSet() == platform.supportedTargets && system.platform.target in platform.supportedTargets &&
        value.compactStrings("supportedOpenWrt", 1..32).toSet() == platform.supportedOpenWrt && system.openWrtVersion in platform.supportedOpenWrt)
    val base = value.compactObject("basePackage")
    base.compactExact("name", "file", "sha256", "packageIdentity")
    require(base.compactText("name") == platform.basePackage.name && base.compactFile("file") == platform.basePackage.file &&
        base.compactHash("sha256") == platform.basePackage.sha256 && base.compactHash("packageIdentity") == platform.basePackage.packageIdentity)
    val modules = value.compactObjects("modules", 1..12)
    require(modules.size == compact.modules.size && modules.map { it.compactText("id") }.distinct().size == modules.size)
    for (client in compact.modules) {
        val actual = requireNotNull(modules.singleOrNull { it.compactText("id") == client.protocol.canonical })
        actual.compactExact("id", "storage", "entry")
        require(actual.compactText("storage") == client.storage.name.lowercase() && actual.compactObject("entry") == client.entry)
    }
    require(value["server"] == (compact.server ?: JsonNull))
    val actualPackages = value.compactObjects("packages", 1..512).map { item ->
        item.compactExact("name", "version", "file", "sha256")
        RouterPackageIdentity(item.compactText("name"), item.compactText("version"), item.compactFile("file"), item.compactHash("sha256"))
    }
    require(packages.isNotEmpty() && packages.size <= 512 && actualPackages.size == packages.size &&
        actualPackages.map { it.name }.distinct().size == actualPackages.size && actualPackages.toSet() == packages.toSet())
}
