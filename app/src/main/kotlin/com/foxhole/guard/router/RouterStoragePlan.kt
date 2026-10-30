package com.foxhole.guard.router

internal enum class RouterStorageMode { FLASH, RAM }

internal data class RouterResourceFacts(val availableFlashBytes: Long, val availableRamBytes: Long)

internal data class RouterInstallBudget(
    val flashPersistentBytes: Long,
    val ramPersistentBytes: Long,
    val flashWorkingMemoryBytes: Long,
    val ramWorkingMemoryBytes: Long,
)

internal fun chooseRouterStorage(facts: RouterResourceFacts, budget: RouterInstallBudget): RouterStorageMode? {
    if (facts.availableFlashBytes < 0 || facts.availableRamBytes < 0 ||
        listOf(budget.flashPersistentBytes, budget.ramPersistentBytes,
            budget.flashWorkingMemoryBytes, budget.ramWorkingMemoryBytes).any { it <= 0 }) return null
    // Budgets include verified payloads, dependencies, staging and reserves.
    if (facts.availableFlashBytes >= budget.flashPersistentBytes && facts.availableRamBytes >= budget.flashWorkingMemoryBytes) {
        return RouterStorageMode.FLASH
    }
    if (facts.availableFlashBytes >= budget.ramPersistentBytes && facts.availableRamBytes >= budget.ramWorkingMemoryBytes) {
        return RouterStorageMode.RAM
    }
    return null
}
