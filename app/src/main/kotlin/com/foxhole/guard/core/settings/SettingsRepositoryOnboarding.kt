package com.foxhole.guard.core.settings

import com.foxhole.guard.BuildConfig

suspend fun SettingsRepository.completeOnboarding(licenseAccepted: Boolean): Boolean =
    completeOnboardingWithConsent(licenseAccepted) { acceptedVersion ->
        updateVerified(
            transform = { settings ->
                settings.copy(
                    ui = settings.ui.copy(
                        onboardingCompleted = true,
                        acceptedLicenseAgreementVersion = acceptedVersion,
                        alphaNoticeShownVersionCode = BuildConfig.VERSION_CODE,
                    ),
                )
            },
            verifyPersisted = {
                check(storage.readAcceptedLicenseAgreementVersion() == acceptedVersion) { "license_consent_not_persisted" }
            },
        )
    }

suspend fun SettingsRepository.acknowledgeBetaNotice() =
    update { settings ->
        settings.copy(ui = settings.ui.copy(betaNoticeAcknowledged = true))
    }

suspend fun SettingsRepository.markQuickStartShown() =
    update { settings ->
        settings.copy(ui = settings.ui.copy(quickStartShown = true))
    }
