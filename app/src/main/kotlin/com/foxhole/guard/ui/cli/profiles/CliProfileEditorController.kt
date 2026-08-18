package com.foxhole.guard.ui.cli.profiles

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foxhole.core.model.Profile
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.emitError
import com.foxhole.guard.ui.removeProfileProtocolOption
import com.foxhole.guard.ui.saveManualProfileConfig
import com.foxhole.guard.ui.saveProfileEditorChanges
import com.foxhole.guard.ui.saveProfileProtocolConfigs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Stable
internal class CliProfileEditorController(
    private val viewModel: HomeViewModel,
    private val profileId: Long,
    private val scope: CoroutineScope,
    private val messages: CliProfileEditorMessages,
) {
    var slots by mutableStateOf<List<CliEditorSlot>?>(null)
        private set
    var expanded by mutableStateOf<CliEditorProtocolRef?>(null)
        private set
    var rawEditor by mutableStateOf<CliEditorProtocolRef?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var reloadKey by mutableIntStateOf(0)
        private set

    val dirty: Boolean
        get() = slots.orEmpty().any(CliEditorSlot::dirty)

    suspend fun load(
        profile: Profile,
        onUnreadable: () -> Unit,
    ) {
        val loaded = loadCliEditorSlots(viewModel, profile)
        if (loaded.isEmpty()) {
            viewModel.emitError(messages.loadFailed)
            onUnreadable()
        } else {
            slots = loaded
        }
    }

    fun changeOutbound(
        ref: CliEditorProtocolRef,
        outbound: JsonObject,
    ) {
        slots = slots?.withEntryAt(ref, outbound)
    }

    fun toggleExpanded(ref: CliEditorProtocolRef) {
        val next = ref.takeUnless { it == expanded }
        expanded = next
        if (rawEditor != next) {
            rawEditor = null
        }
    }

    fun openManualEditor() {
        val target = expanded ?: slots.orEmpty().protocolRefs().firstOrNull() ?: return
        expanded = target
        rawEditor = target
    }

    fun closeManualEditor() {
        rawEditor = null
    }

    fun manualConfigText(ref: CliEditorProtocolRef): String =
        slots.orEmpty().getOrNull(ref.slotIndex)?.prettyConfigText().orEmpty()

    fun saveManualConfig(
        profileName: String,
        ref: CliEditorProtocolRef,
        text: String,
        onSaved: () -> Unit,
    ) =
        runExclusive {
            val slot = slots.orEmpty().getOrNull(ref.slotIndex) ?: return@runExclusive
            if (
                viewModel.saveManualProfileConfig(
                    profileId = profileId,
                    profileName = profileName,
                    protocolOptionId = slot.optionId,
                    rawText = text,
                )
            ) {
                onSaved()
            }
        }

    fun deleteProtocol(ref: CliEditorProtocolRef) =
        runExclusive {
            val current = slots.orEmpty()
            val slot = current.getOrNull(ref.slotIndex) ?: return@runExclusive
            when {
                slot.protocolEntryCount() > 1 -> {
                    expanded = null
                    rawEditor = null
                    slots = current.withSlot(ref.slotIndex) { it.withoutEntry(ref) }
                }
                slot.optionId != null && current.size > 1 -> removeOption(slot.optionId)
                else -> viewModel.emitError(messages.lastProtocol)
            }
        }

    fun addProtocol(add: suspend (List<CliEditorSlot>) -> Boolean) =
        runExclusive {
            if (flush() && add(slots.orEmpty())) {
                expanded = null
                rawEditor = null
                reloadKey++
            }
        }

    fun save(
        profileName: String,
        onSaved: () -> Unit,
    ) =
        runExclusive {
            if (viewModel.saveProfileEditorChanges(profileId, profileName, slots?.pendingEdits().orEmpty())) {
                onSaved()
            }
        }

    private suspend fun removeOption(optionId: String) {
        if (!flush()) {
            return
        }
        expanded = null
        rawEditor = null
        viewModel.removeProfileProtocolOption(profileId, optionId)
        reloadKey++
    }

    private suspend fun flush(): Boolean {
        val edits = slots?.pendingEdits().orEmpty()
        return edits.isEmpty() || viewModel.saveProfileProtocolConfigs(profileId, edits)
    }

    private fun runExclusive(block: suspend () -> Unit) {
        if (busy) {
            return
        }
        scope.launch {
            busy = true
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
}

internal data class CliProfileEditorMessages(
    val loadFailed: String,
    val lastProtocol: String,
)
