package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType

internal fun repairResolvedConfigIfNeeded(
    parser: ProfileImportParser,
    loaded: LoadedResolvedConfig,
): RepairedResolvedConfig {
    val repaired =
        parser.normalizeLegacyRawResolvedConfig(
            raw = loaded.resolvedConfig,
            settings = loaded.settings,
            allowInsecureTls =
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = loaded.settings.expert.allowInsecureTls,
                secret = loaded.secret,
            ),
        )
    val runtimeConfig = repaired ?: loaded.resolvedConfig
    require(runtimeConfig.trimStart().startsWith("{")) { "stored profile config is not valid JSON" }
    return RepairedResolvedConfig(
        runtimeConfig = runtimeConfig,
        legacyRawConfigRepaired = repaired != null,
    )
}

internal fun missingResolvedConfig(profile: Profile): Nothing {
    val suffix =
        if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
            "; refresh the subscription before loading runtime config"
        } else {
            ""
        }
    error("profile has no resolved config$suffix")
}
