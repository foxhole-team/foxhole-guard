package com.foxhole.guard.ui.cli.logs

import com.foxhole.core.model.InstalledAppInventoryChange
import com.foxhole.core.model.InstalledAppOption

internal const val SENTINEL_INSTALLATION_PREVIEW_COUNT = 6

internal fun sentinelInstallations(apps: List<InstalledAppOption>): List<InstalledAppOption> =
    apps.sortedWith(
        compareByDescending<InstalledAppOption> { it.firstInstallTime?.takeIf { time -> time > 0L } ?: Long.MIN_VALUE }
            .thenBy { it.packageName }
            .thenBy { it.label },
    ).distinctBy(InstalledAppOption::packageName)

internal fun sentinelInstallationPreview(apps: List<InstalledAppOption>): List<InstalledAppOption> =
    sentinelInstallations(apps.filterNot(InstalledAppOption::isSystemApp)).take(SENTINEL_INSTALLATION_PREVIEW_COUNT)

internal fun sentinelInstallationHistory(
    changes: List<InstalledAppInventoryChange>,
    cutoff: Long,
): List<InstalledAppInventoryChange> = changes.filter { it.detectedAt >= cutoff }.sortedWith(
    compareByDescending<InstalledAppInventoryChange>(InstalledAppInventoryChange::detectedAt)
        .thenBy { it.packageName }
        .thenBy { it.type.name },
)
