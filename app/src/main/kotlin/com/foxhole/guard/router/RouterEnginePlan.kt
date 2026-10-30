package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject

internal enum class RouterEngineOperation { ADD, UPDATE, REMOVE }
internal enum class RouterEngineImpact { KEEP_ACTIVE, RECONNECT_REQUIRED }
internal enum class RouterEngineWarning { RECONNECT_REQUIRED, RAM_DOWNLOAD_REQUIRED, OLD_SLOT_RETAINED }

internal data class RouterEngineChange(
    val protocol: RouterInstallProtocol,
    val fromVersion: String?,
    val toVersion: String?,
    val storage: RouterEngineStorage,
    val archiveBytes: Long,
    val installedBytes: Long,
)

internal data class RouterEngineBudget(
    val downloadBytes: Long,
    val stagingBytes: Long,
    val flashRequiredBytes: Long,
    val flashAvailableBytes: Long,
    val ramRequiredBytes: Long,
    val ramAvailableBytes: Long,
    val reservedBytes: Long,
)

internal data class RouterEnginePlan(
    val routerId: String,
    val planId: String,
    val planSha256: String,
    val revision: Long,
    val catalogSha256: String,
    val bootId: String,
    val expiresAtUptimeMs: Long,
    val operation: RouterEngineOperation,
    val protocols: Set<RouterInstallProtocol>,
    val changes: List<RouterEngineChange>,
    val budget: RouterEngineBudget,
    val impact: RouterEngineImpact,
    val warnings: Set<RouterEngineWarning>,
)

internal fun parseRouterEnginePlan(value: JsonObject, status: RouterEngineStatus,
    operation: RouterEngineOperation, protocols: Set<RouterInstallProtocol>): RouterEnginePlan {
    value.engineSchema()
    val selected = engineStrings(value["engineIds"], 12).map(::engineProtocol)
    if (selected.isEmpty() || selected.toSet().size != selected.size || selected.toSet() != protocols ||
        value.engineId("routerId") != status.routerId || value.engineNumber("revision") != status.revision ||
        value.engineEnum<RouterEngineOperation>("operation") != operation || !value.engineBoolean("confirmationRequired")) invalidEngineResponse()
    val changes = value.engineObjects("changes", 12).map { item ->
        RouterEngineChange(item.engineProtocol("id"), item.engineNullableText("fromVersion", 96),
            item.engineNullableText("toVersion", 96), item.engineEnum("storage"),
            item.engineNumber("archiveBytes"), item.engineNumber("installedBytes"))
    }
    if (changes.map { it.protocol }.toSet() != protocols || changes.size != protocols.size) invalidEngineResponse()
    val budget = value.engineNullableObject("budget") ?: invalidEngineResponse()
    val warnings = engineStrings(value["warnings"], RouterEngineWarning.entries.size).map { engineEnumValue<RouterEngineWarning>(it) }
    if (warnings.toSet().size != warnings.size) invalidEngineResponse()
    val impact = value.engineEnum<RouterEngineImpact>("impact")
    if (operation == RouterEngineOperation.UPDATE && status.activeEngine in protocols && impact != RouterEngineImpact.RECONNECT_REQUIRED)
        invalidEngineResponse()
    return RouterEnginePlan(status.routerId, value.engineId("planId"), value.engineHash("planSha256"), status.revision, status.catalogSha256,
        value.engineId("bootId"), value.engineNumber("expiresAtUptimeMs"), operation, protocols.toSet(), changes,
        RouterEngineBudget(budget.engineNumber("downloadBytes"), budget.engineNumber("stagingBytes"),
            budget.engineNumber("flashRequiredBytes"), budget.engineNumber("flashAvailableBytes"),
            budget.engineNumber("ramRequiredBytes"), budget.engineNumber("ramAvailableBytes"), budget.engineNumber("reservedBytes")),
        impact, warnings.toSet())
}
