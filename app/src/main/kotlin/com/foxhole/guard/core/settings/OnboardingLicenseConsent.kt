package com.foxhole.guard.core.settings

internal const val ONBOARDING_LICENSE_AGREEMENT_VERSION = 1

internal fun onboardingLicenseAccepted(acceptedLicenseAgreementVersion: Int): Boolean =
    acceptedLicenseAgreementVersion == ONBOARDING_LICENSE_AGREEMENT_VERSION

internal fun requiresOnboarding(
    hydrated: Boolean,
    onboardingCompleted: Boolean,
    acceptedLicenseAgreementVersion: Int,
): Boolean = hydrated &&
    (!onboardingCompleted || !onboardingLicenseAccepted(acceptedLicenseAgreementVersion))

internal inline fun completeOnboardingWithConsent(
    licenseAccepted: Boolean,
    persist: (acceptedVersion: Int) -> Unit,
): Boolean {
    if (!licenseAccepted) return false
    persist(ONBOARDING_LICENSE_AGREEMENT_VERSION)
    return true
}
