package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterStoragePlanTest {
    private val budget = RouterInstallBudget(100, 20, 50, 100)

    @Test fun flashIsPreferredWhenTheCompleteVerifiedBudgetsFit() {
        assertEquals(RouterStorageMode.FLASH, chooseRouterStorage(RouterResourceFacts(100, 100), budget))
    }

    @Test fun ramRequiresEnoughPersistentSpaceAndWorkingMemory() {
        assertEquals(RouterStorageMode.RAM, chooseRouterStorage(RouterResourceFacts(20, 100), budget))
        assertNull(chooseRouterStorage(RouterResourceFacts(19, 1000), budget))
        assertNull(chooseRouterStorage(RouterResourceFacts(99, 99), budget))
    }

    @Test fun invalidMeasurementsOrPackageBudgetsNeverProduceAnInstallPlan() {
        assertNull(chooseRouterStorage(RouterResourceFacts(-1, 1000), budget))
        assertNull(chooseRouterStorage(RouterResourceFacts(1000, -1), budget))
        assertNull(chooseRouterStorage(RouterResourceFacts(1000, 1000), budget.copy(flashPersistentBytes = 0)))
        assertNull(chooseRouterStorage(RouterResourceFacts(1000, 1000), budget.copy(ramWorkingMemoryBytes = -1)))
    }

    @Test fun largeBudgetsCannotOverflowIntoAnApparentlyFreeInstallation() {
        assertNull(chooseRouterStorage(RouterResourceFacts(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1),
            RouterInstallBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)))
    }
}
