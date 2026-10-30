package com.foxhole.guard.router

import com.foxhole.core.network.ensurePublicSubscriptionUrl
import com.foxhole.guard.router.profile.InsecureTlsConsentRequired
import com.foxhole.guard.router.profile.ProfileImportFailure
import com.foxhole.guard.router.profile.VpnProfileImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

internal suspend fun routerWholeShareContent(value: JsonObject): String {
    val source = (value["subscription"] as? JsonObject)?.shareText("sourceUrl")
    if (source != null) {
        if (source.toByteArray(Charsets.UTF_8).size > 4096) unavailable()
        return try { source.ensurePublicSubscriptionUrl(resolveHost = false); source } catch (_: IllegalArgumentException) { unavailable() }
    }
    val portable = value["portableProfile"] as? JsonObject
    val content = if (value.shareText("engine") == "core") {
        val bytes = portable?.shareText("content") ?: unavailable()
        val expected = portable.shareText("sha256") ?: unavailable()
        if (!expected.matches(Regex("[a-f0-9]{64}")) || digest(bytes) != expected) unavailable()
        bytes
    } else value.shareText("config") ?: unavailable()
    if (content.toByteArray(Charsets.UTF_8).size !in 1..262144) unavailable()
    return withContext(Dispatchers.IO) {
        try { if (VpnProfileImporter().import(content).profiles.isEmpty()) unavailable() }
        catch (_: InsecureTlsConsentRequired) { /* Copying does not grant the receiving app TLS consent. */ }
        catch (_: ProfileImportFailure) { unavailable() }
        content
    }
}

private fun JsonObject.shareText(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun unavailable(): Nothing = throw RouterSetupFailure(RouterWizardError.PROFILE_EXPORT)
