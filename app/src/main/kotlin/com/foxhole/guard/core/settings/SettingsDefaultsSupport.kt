package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.NetworkRulesSettings
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.RoutingRuleAction
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.model.UiSettings
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.withLane
import com.foxhole.core.model.withoutAssignments
import com.foxhole.guard.BuildConfig

// The two resets below deliberately keep the POSITIVE `ExpertSettings(...)` form, unlike
// [disarmedBySafeMode]. They are factory resets: a field added later SHOULD come back as its
// default, and only the three identity/consent fields listed here are meant to outlive the reset.
// The negative-copy rule exists for disarming (where preserving is the expected default), not here.
internal fun Settings.resetExpertSettingsToSafeDefaults(): Settings =
    copy(
        connection = connection.copy(safeModeEnabled = true),
        traffic = TrafficSettings(),
        expert = ExpertSettings(
            unlockedAt = expert.unlockedAt,
            warningAcknowledgedAt = null,
            blockScreenshots = expert.blockScreenshots,
        ),
    )

internal fun Settings.resetExperimentalSettingsToDefaults(): Settings =
    copy(
        expert = ExpertSettings(
            unlockedAt = expert.unlockedAt,
            warningAcknowledgedAt = null,
            blockScreenshots = expert.blockScreenshots,
        ),
    )

internal fun Settings.resetApplicationSettingsToDefaults(): Settings =
    copy(
        ui = UiSettings(),
        connection = ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT),
        traffic = TrafficSettings(),
        networkRules = NetworkRulesSettings(),
        expert = ExpertSettings(),
    )

internal fun Settings.withExpertSettingsVisibility(visible: Boolean): Settings =
    if (visible) {
        copy(
            ui = ui.copy(showExpertSettings = true),
            expert = expert.copy(unlockedAt = expert.unlockedAt ?: System.currentTimeMillis()),
        )
    } else {
        copy(
            ui = ui.copy(showExpertSettings = false),
        )
    }

internal fun ExpertSettings.quarantinePackage(
    packageName: String,
    details: PendingQuarantineAppDetails? = null,
): ExpertSettings {
    val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank) ?: return this
    val nextDetails =
        details
            ?.takeIf { candidate -> candidate.packageName.trim() == normalizedPackageName }
            ?.let { candidate ->
                pendingQuarantineAppDetails.filterNot { existing ->
                    existing.packageName == normalizedPackageName
                } + candidate.copy(packageName = normalizedPackageName)
            }
            ?: pendingQuarantineAppDetails
    return withLane(AppTunnelLane.BLOCK, listOf(normalizedPackageName)).copy(
        firewallEnabled = true,
        pendingQuarantinePackages = (pendingQuarantinePackages + normalizedPackageName).distinct(),
        pendingQuarantineAppDetails = nextDetails,
        blockedPackagesEnabled = true,
        blockAppsAlways = true,
    )
}

internal fun ExpertSettings.removeAppPackage(packageName: String): ExpertSettings {
    val remaining = withoutAssignments(listOf(packageName))
    val blockedLeft = remaining.blockedLanePackages().isNotEmpty()
    return remaining.copy(
        quarantineKnownApplications =
        quarantineKnownApplications.filterNot { application -> application.packageName == packageName },
        pendingQuarantinePackages = pendingQuarantinePackages - packageName,
        pendingQuarantineAppDetails =
        pendingQuarantineAppDetails.filterNot { details -> details.packageName == packageName },
        blockedPackagesEnabled = blockedPackagesEnabled && blockedLeft,
        blockAppsAlways = blockAppsAlways && blockedLeft,
    )
}

internal fun ExpertSettings.withKnownQuarantineIdentity(identity: KnownApplicationIdentity): ExpertSettings =
    copy(
        quarantineKnownApplications =
        (quarantineKnownApplications.filterNot { application -> application.packageName == identity.packageName } + identity)
            .sortedBy(KnownApplicationIdentity::packageName),
    )

internal fun RoutingRuleAction.coerceSiteRoutingAction(): RoutingRuleAction =
    when (this) {
        RoutingRuleAction.PROXY,
        RoutingRuleAction.DIRECT,
        -> this
        RoutingRuleAction.BLOCK,
        RoutingRuleAction.TOR,
        -> RoutingRuleAction.PROXY
    }

internal fun selectedPackagesRoutingMode(
    currentMode: PerAppRoutingMode,
    selectedPackages: List<String>,
): PerAppRoutingMode =
    when {
        selectedPackages.isEmpty() -> PerAppRoutingMode.FULL_TUNNEL
        currentMode == PerAppRoutingMode.FULL_TUNNEL -> PerAppRoutingMode.INCLUDE_SELECTED_APPS
        else -> currentMode
    }

internal fun splitTunnelTrafficMode(
    currentMode: TrafficMode,
    perAppRoutingMode: PerAppRoutingMode,
): TrafficMode =
    if (perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL) {
        currentMode
    } else {
        TrafficMode.TUNNEL
    }
