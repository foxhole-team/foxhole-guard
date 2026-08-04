package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport

internal sealed interface ImportProfilePlan {
    data class Single(
        val parsed: ParsedImport,
    ) : ImportProfilePlan

    data class Multi(
        val parsed: ParsedSubscriptionImport,
    ) : ImportProfilePlan
}
