package com.foxhole.guard.router

import java.net.URLEncoder

internal fun routerClientProfileForImport(content: String, name: String): String {
    require(validRouterClientName(name)) { "invalid_router_client_name" }
    val fragment = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")
    return content.substringBefore('#') + "#" + fragment
}
