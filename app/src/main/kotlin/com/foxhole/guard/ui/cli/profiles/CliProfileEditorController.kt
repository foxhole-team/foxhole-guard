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
import com.foxhole.guard.ui.saveProfileProtocolConfigs
import com.foxhole.guard.ui.setSmartProfileProtocolEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The editing session behind [CliProfileEditorScreen]: the parsed configs of every protocol, which
 * card is open, and the one-at-a-time gate around the suspending actions.
 *
 * Two kinds of change meet here. Field edits stay local — they mutate the parsed tree and are
 * written back by [save] through the profile's own resolved-config path, one call per changed
 * protocol. Structural changes (add/remove a protocol) cannot be expressed that way, so they flush
 * the pending field edits first and then re-read everything ([reloadKey]) — the editor never shows
 * a config the store does not have.
 */
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
        slots = slots?.withOutboundAt(ref, outbound)
    }

    fun toggleExpanded(ref: CliEditorProtocolRef) {
        expanded = ref.takeUnless { it == expanded }
    }

    fun toggleEnabled(
        optionId: String,
        enabled: Boolean,
    ) {
        viewModel.setSmartProfileProtocolEnabled(profileId, optionId, enabled)
    }

    /**
     * Removing a protocol means one of two things: a config that carries several proxy outbounds
     * just loses one (local, saved with the rest), while a one-outbound protocol option leaves the
     * profile for good. The last protocol of a profile is refused either way.
     */
    fun deleteProtocol(ref: CliEditorProtocolRef) =
        runExclusive {
            val current = slots.orEmpty()
            val slot = current.getOrNull(ref.slotIndex) ?: return@runExclusive
            when {
                slot.root.cliProxyOutboundIndices().size > 1 -> {
                    expanded = null
                    slots = current.withSlot(ref.slotIndex) { it.withoutOutbound(ref.outboundIndex) }
                }
                slot.optionId != null && current.size > 1 -> removeOption(slot.optionId)
                else -> viewModel.emitError(messages.lastProtocol)
            }
        }

    fun addProtocol(add: suspend (List<CliEditorSlot>) -> Boolean) =
        runExclusive {
            if (flush() && add(slots.orEmpty())) {
                expanded = null
                reloadKey++
            }
        }

    fun save(onSaved: () -> Unit) =
        runExclusive {
            if (flush()) {
                onSaved()
            }
        }

    private suspend fun removeOption(optionId: String) {
        if (!flush()) {
            return
        }
        expanded = null
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
