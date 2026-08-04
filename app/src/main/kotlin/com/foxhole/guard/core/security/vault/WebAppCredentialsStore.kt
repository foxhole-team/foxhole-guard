package com.foxhole.guard.core.security.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A web-app credential in the vault's AUTH section: login and password are stored encrypted only.
 * There is deliberately no autofill into the frame — the WebView keeps its own cookie session, and
 * this is a protected notebook for logging in again by hand.
 */
@Serializable
internal data class WebAppCredentials(
    val login: String,
    val password: String,
    val note: String = "",
)

internal class WebAppCredentialsStore(
    private val vault: FoxholeVault,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(webAppId: Long, credentials: WebAppCredentials) {
        vault.put(
            FoxholeVault.Section.AUTH,
            keyFor(webAppId),
            json.encodeToString(WebAppCredentials.serializer(), credentials).toByteArray(),
        )
    }

    fun load(webAppId: Long): WebAppCredentials? {
        val payload = vault.get(FoxholeVault.Section.AUTH, keyFor(webAppId)) ?: return null
        return runCatching {
            json.decodeFromString(WebAppCredentials.serializer(), String(payload))
        }.getOrNull()
    }

    fun delete(webAppId: Long) {
        vault.delete(FoxholeVault.Section.AUTH, keyFor(webAppId))
    }

    private fun keyFor(webAppId: Long): String = "webapp/$webAppId"
}
