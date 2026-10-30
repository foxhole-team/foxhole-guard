package com.foxhole.guard.router

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class RouterHandoffReceipt(val phase: String, val expiresAtElapsedMs: Long, val policyRevision: Long, val observedUptimeMs: Long)

internal fun parseRouterHandoff(value: JsonObject, claim: String, mode: String, now: Long, requestDuration: Long,
    expectedProof: RouterHomeProof? = null): RouterHandoffReceipt {
    fun text(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    fun number(key: String) = (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    if (number("schema") != 1L || text("claimId") != claim || !claim.matches(Regex("[a-f0-9]{32}")) || text("mode") != mode ||
        mode !in setOf("phone_vpn", "router_vpn") || now < 0 || requestDuration !in 0..5000) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val phase = text("phase")
    val remaining = number("remainingMs")
    val revision = number("policyRevision")
    if (phase !in setOf("active", "validating", "released") || remaining !in 0..60000 || revision < 0 || number("observedUptimeMs") !in 0..9007199254740991L ||
        mode == "phone_vpn" && phase != "active" || mode == "router_vpn" && phase == "active" || mode == "router_vpn" && remaining != 0L)
        throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    for ((key, pattern) in listOf("serverId" to Regex("server-[a-f0-9]{12}"), "generation" to Regex("[a-f0-9]{16}"))) {
        val item = value[key]
        if (item != JsonNull && (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.matches(pattern) != true)
            throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    }
    if (expectedProof != null && (mode != "router_vpn" || text("serverId") != expectedProof.serverId || text("generation") != expectedProof.generation))
        throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    val usable = (remaining - requestDuration).coerceAtLeast(0)
    if (now > Long.MAX_VALUE - usable) throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
    return RouterHandoffReceipt(phase, now + usable, revision, number("observedUptimeMs"))
}

internal fun routerHandoffCanRelease(status: RouterPhoneStatus, ownedClaim: String?): Boolean = status.handoffKnown &&
    (status.handoff == null || status.handoff.claimId == ownedClaim)
