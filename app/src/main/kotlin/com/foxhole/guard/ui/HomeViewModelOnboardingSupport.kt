package com.foxhole.guard.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pRelayTransitTraffic
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.runtime.RemoteUpdatePhase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// First-run wizard. Nothing here is applied while the user is still paging: every choice is held in
// [OnboardingChoices] and written once, on finish. A wizard abandoned by killing the app therefore
// leaves the install exactly as a fresh one — VPN only, every component off.

/** What the wizard downloads, in the order the progress bar walks them. */
enum class OnboardingDownload {
    DNS_FILTER,
    TOR_BRIDGES,
}

@Immutable
data class OnboardingDownloadState(
    val item: OnboardingDownload,
    val phase: RemoteUpdatePhase?,
    val done: Boolean = false,
    val failed: Boolean = false,
)

@Immutable
data class OnboardingProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    val items: List<OnboardingDownloadState> = emptyList(),
) {
    /** 0f..1f across every item; a failed item still counts as walked. */
    val fraction: Float
        get() = if (items.isEmpty()) 0f else items.count { it.done || it.failed }.toFloat() / items.size
}

internal val HomeViewModel.onboardingProgress: StateFlow<OnboardingProgress>
    get() = onboardingProgressMutable.asStateFlow()

/**
 * Persist the wizard's choices and mark it done.
 *
 * Order matters: the component flags are written before [completeOnboarding] so the first composition
 * after the wizard already sees the finished configuration, with no frame in between where the app is
 * unlocked but still default.
 */
internal fun HomeViewModel.onOnboardingFinished(
    torEnabled: Boolean,
    i2pEnabled: Boolean,
    i2pRelay: Boolean,
    dnsFilterEnabled: Boolean,
    autoUpdate: Boolean,
) {
    viewModelScope.launch {
        val repository = container.settingsRepository
        if (torEnabled) {
            repository.updatePrivacyRoutePermitted(true)
        }
        if (i2pEnabled) {
            repository.updateI2pEnabled(true)
            // Relaying is a separate, explicitly opted-in decision: enabling I2P alone never turns
            // the device into a transit node.
            repository.updateI2pRelayTransitTraffic(i2pRelay)
        }
        if (dnsFilterEnabled) {
            // updateDnsSettings auto-arms interception on the off->on transition; without it the
            // filter would be inert at the DNS layer.
            repository.updateDnsSettings(repository.current().dns.copy(filteringEnabled = true))
        }
        repository.updateComponentAutoUpdate(autoUpdate)
        repository.completeOnboarding()
    }
}

/** Leaves every component off — the wizard's own default — and never blocks startup again. */
internal fun HomeViewModel.onOnboardingSkipped() {
    viewModelScope.launch {
        container.settingsRepository.completeOnboarding()
    }
}

/**
 * Fetch the lists the chosen components need, reporting each phase to the progress bar.
 *
 * Failures are recorded and walked past rather than thrown: a first run without network must still
 * reach the app. The components stay enabled — their own update workers retry later, and the DNS
 * filter falls back to the rule set bundled in assets.
 */
internal fun HomeViewModel.onOnboardingDownload(dnsFilter: Boolean, torBridges: Boolean) {
    if (onboardingProgressMutable.value.running) {
        return
    }
    val planned = buildList {
        if (dnsFilter) add(OnboardingDownload.DNS_FILTER)
        if (torBridges) add(OnboardingDownload.TOR_BRIDGES)
    }
    if (planned.isEmpty()) {
        onboardingProgressMutable.value = OnboardingProgress(finished = true)
        return
    }
    onboardingProgressMutable.value =
        OnboardingProgress(
            running = true,
            items = planned.map { item -> OnboardingDownloadState(item = item, phase = null) },
        )
    viewModelScope.launch {
        planned.forEach { item ->
            val outcome = runCatching {
                when (item) {
                    OnboardingDownload.DNS_FILTER ->
                        container.dnsFilterUpdateRepository.refreshNow(
                            requireAutoEnabled = false,
                            // The wizard has not written settings yet, so the repository would still
                            // read filtering as disabled and skip the download.
                            dnsSettingsOverride = container.settingsRepository.current().dns.copy(
                                filteringEnabled = true,
                            ),
                            onPhase = { phase -> onboardingPhase(item, phase) },
                        )

                    OnboardingDownload.TOR_BRIDGES ->
                        container.torBridgeUpdateRepository.refreshNow(
                            onPhase = { phase -> onboardingPhase(item, phase) },
                        )
                }
            }
            onboardingProgressMutable.value = onboardingProgressMutable.value.mark(
                item = item,
                failed = outcome.isFailure,
            )
        }
        onboardingProgressMutable.value =
            onboardingProgressMutable.value.copy(running = false, finished = true)
    }
}

private fun HomeViewModel.onboardingPhase(item: OnboardingDownload, phase: RemoteUpdatePhase) {
    onboardingProgressMutable.value = onboardingProgressMutable.value.copy(
        items = onboardingProgressMutable.value.items.map { state ->
            if (state.item == item) state.copy(phase = phase) else state
        },
    )
}

private fun OnboardingProgress.mark(item: OnboardingDownload, failed: Boolean): OnboardingProgress =
    copy(
        items = items.map { state ->
            if (state.item == item) state.copy(done = !failed, failed = failed, phase = null) else state
        },
    )
