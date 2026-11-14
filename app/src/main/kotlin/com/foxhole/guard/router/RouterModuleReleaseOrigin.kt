package com.foxhole.guard.router

internal data class RouterModuleReleaseOrigin(val repository: String, val version: String) {
    val baseUrl: String get() = "https://github.com/foxhole-team/$repository/releases/download/v$version/"
}

internal fun routerModuleReleaseOrigin(indexUrl: String): RouterModuleReleaseOrigin {
    val match = Regex("https://github\\.com/foxhole-team/(foxhole-core|foxhole-openwrt-client)/releases/download/v([0-9]+\\.[0-9]+\\.[0-9]+(?:[+-][A-Za-z0-9.-]+)?)/engines\\.adb")
        .matchEntire(indexUrl) ?: error("compact_module_origin")
    return RouterModuleReleaseOrigin(match.groupValues[1], match.groupValues[2])
}

internal fun requireRouterCoreModuleOrigin(indexUrl: String, packageName: String, packageVersion: String) {
    val origin = routerModuleReleaseOrigin(indexUrl)
    if (origin.repository != "foxhole-core") return
    require(packageName.matches(Regex("foxcore-engine-(hysteria2|vless|wireguard)-(flash|ram)-[a-f0-9]{64}")) &&
        packageVersion.matches(Regex(Regex.escape(origin.version) + "-r[1-9][0-9]*")))
}
