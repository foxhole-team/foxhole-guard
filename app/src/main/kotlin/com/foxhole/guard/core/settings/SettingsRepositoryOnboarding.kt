package com.foxhole.guard.core.settings

/** Small UI-lifecycle mutations live outside the encrypted repository owner. */
suspend fun SettingsRepository.completeOnboarding() =
    update { settings ->
        settings.copy(ui = settings.ui.copy(onboardingCompleted = true))
    }

suspend fun SettingsRepository.acknowledgeBetaNotice() =
    update { settings ->
        settings.copy(ui = settings.ui.copy(betaNoticeAcknowledged = true))
    }

suspend fun SettingsRepository.markQuickStartShown() =
    update { settings ->
        settings.copy(ui = settings.ui.copy(quickStartShown = true))
    }
