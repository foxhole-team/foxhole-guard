package com.foxhole.guard.router

import android.app.Application
import androidx.annotation.Keep
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class GuardRouterPage { ENTRY, WIZARD, CONTROL }

@Keep
internal class GuardRouterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as FoxholeApplication).appGraph.profileRepository
    private val runtime = GuardRouterRuntime(application, repository)
    val profileProbe get() = runtime.profileProbe
    val wizard get() = runtime.wizard
    val control get() = runtime.control
    private val currentPage = MutableStateFlow(GuardRouterPage.ENTRY)
    val page = currentPage.asStateFlow()
    private val loading = MutableStateFlow(true)
    val initialising = loading.asStateFlow()
    private val messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notices = messages.asSharedFlow()
    val profiles = repository.profiles.map { values -> values.map { it.routerSummary() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var wizardJob: Job? = null
    private var openGeneration = 0L

    fun open() {
        closeScreen()
        loading.value = true
        val generation = openGeneration
        viewModelScope.launch {
            control.loadPairingState()
            if (generation != openGeneration) return@launch
            if (control.state.value.selectedRouterId != null) currentPage.value = GuardRouterPage.CONTROL
            else { wizard.begin(RouterWizardPath.PAIR, null); currentPage.value = GuardRouterPage.ENTRY }
            loading.value = false
        }
    }

    fun openSettings(): Long {
        closeScreen()
        loading.value = true
        val generation = openGeneration
        viewModelScope.launch {
            if (generation != openGeneration) return@launch
            control.loadPairingState()
            if (generation == openGeneration) loading.value = false
        }
        return generation
    }

    fun setSettingsEnabled(generation: Long, enabled: Boolean) {
        if (generation != openGeneration || loading.value || !control.state.value.canToggleManagement) return
        action { if (generation == openGeneration) control.setEnabled(enabled) }
    }

    fun closeSettings(generation: Long) {
        if (generation == openGeneration) closeScreen()
    }

    fun action(secret: CharArray? = null, operation: suspend () -> Unit) {
        if (wizardJob?.isActive == true) { secret?.fill('\u0000'); return }
        wizardJob = viewModelScope.launch { try { operation() } finally { secret?.fill('\u0000') } }
            .also { job -> job.invokeOnCompletion { secret?.fill('\u0000') } }
    }

    fun find() { wizard.begin(RouterWizardPath.PAIR, null, addingNew = wizard.isAddingRouter, keepNotFoundPresentation = true); action { wizard.findRouter() } }
    fun manualPair(adding: Boolean = wizard.isAddingRouter) {
        wizardJob?.cancel(); control.close(); wizard.begin(RouterWizardPath.PAIR, null, addingNew = adding)
        currentPage.value = GuardRouterPage.WIZARD
    }
    fun retryPairing() {
        wizardJob?.cancel(); control.close(); wizard.beginPairingRetry()
        currentPage.value = GuardRouterPage.WIZARD
    }
    fun entry(adding: Boolean = false) {
        wizardJob?.cancel(); control.close(); wizard.begin(RouterWizardPath.PAIR, null, addingNew = adding)
        currentPage.value = GuardRouterPage.ENTRY
    }
    fun discovered(reset: Boolean?) {
        val generation = openGeneration
        action {
            if (reset == null) wizard.installDiscovered() else wizard.connectDiscovered(reset)
            val state = wizard.state.value
            if (generation == openGeneration && state.error == null && state.stage != RouterWizardStage.ENTRY) currentPage.value = GuardRouterPage.WIZARD
        }
    }
    suspend fun inspect(address: String) {
        wizard.inspectAddress(address)
        val state = wizard.state.value
        if (state.stage == RouterWizardStage.IDENTITY && state.error == null && !state.busy) {
            if (state.identityChanged) { messages.emit(R.string.guard_router_vpn_router_error_identity); entry() }
            else wizard.acceptIdentity()
        }
    }
    fun completed() {
        if (currentPage.value != GuardRouterPage.WIZARD) return
        val generation = openGeneration
        wizard.close()
        viewModelScope.launch {
            control.loadPairingState()
            if (generation == openGeneration) currentPage.value = GuardRouterPage.CONTROL
        }
    }
    fun closeScreen() { openGeneration++; wizardJob?.cancel(); wizardJob = null; runtime.close() }
    override fun onCleared() { closeScreen() }
}
