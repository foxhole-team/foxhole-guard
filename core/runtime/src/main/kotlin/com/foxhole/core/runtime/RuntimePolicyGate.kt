package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface RuntimePolicyUpdate {
    suspend fun stage(session: VpnSession)
    suspend fun commit(): Result<Unit>
}

class RuntimePolicyUpdateException(val reason: Reason) : IllegalStateException("atomic_policy_${reason.name.lowercase()}") {
    enum class Reason { UNSUPPORTED, BUSY, FAILED, SUPERSEDED }
}

internal fun FoxCoreTunPlan.capturesWholeDevice(): Boolean =
    allowedApplications.isEmpty() && disallowedApplications.isEmpty() &&
        ipv6Address != null && routes.any { it.address == "0.0.0.0" && it.prefixLength == 0 } &&
        routes.any { it.address == "::" && it.prefixLength == 0 }

internal fun FoxCoreSessionConfig.withPolicyGate(blocked: Boolean, revision: Long?): FoxCoreSessionConfig {
    val source = Json.parseToJsonElement(policyConfigJson).jsonObject
    val traffic = source.getValue("traffic").jsonObject
    val policy = buildJsonObject {
        source.forEach { (key, value) ->
            if (key != "expected_revision" && key != "traffic") put(key, value)
        }
        revision?.let { put("expected_revision", it) }
        put("traffic", buildJsonObject {
            traffic.forEach { (key, value) -> if (key != "kill_switch") put(key, value) }
            put("kill_switch", blocked || traffic["kill_switch"]?.jsonPrimitive?.booleanOrNull == true)
        })
    }
    return copy(policyConfigJson = policy.toString())
}
