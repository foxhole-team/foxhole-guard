package com.foxhole.guard.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tor/I2P operation state: the running-operation banner, the transition prompt and the timeout /
 * background-refresh / protocol-revert jobs the Tor support files juggle. HomeViewModel exposes
 * same-named aliases so the Support-file call sites read unchanged.
 */
internal class HomeTorOperationState {
    val torOperationMutable = MutableStateFlow(HomeTorOperationUiState())
    val torTransitionPromptMutable = MutableStateFlow<TorTransitionPrompt?>(null)
    var torOperationTimeoutJob: Job? = null
    var torExitBackgroundRefreshJob: Job? = null
    var protocolSwitchRevertJob: Job? = null
    var liveModeSwitchCountdownJob: Job? = null
}
