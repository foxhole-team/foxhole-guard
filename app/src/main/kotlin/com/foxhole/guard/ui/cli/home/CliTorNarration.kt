package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Ordered, truthful Tor narration stages. A late CONNECTED snapshot replays skipped stages. */
internal enum class CliTorNarrationStage { CONNECTING, CIRCUITS, CONNECTED, LOOKUP }

/** Small queue owned by [CliTerminalState]; it contains no journal or connection decisions. */
internal class CliTorNarration(
    private val strings: CliTerminalStrings,
    private val onStage: (text: String, tone: CliLineTone) -> Unit,
) {
    private var current: CliTorNarrationStage? = null
    private var highestStage = -1
    private val queue = ArrayDeque<CliTorNarrationStage>()

    var revision: Int by mutableIntStateOf(0)
        private set

    val hasQueuedStages: Boolean
        get() = queue.isNotEmpty()

    val isLookupVisible: Boolean
        get() = current == CliTorNarrationStage.LOOKUP

    fun request(target: CliTorNarrationStage, allowDisplay: Boolean) {
        if (target.ordinal > highestStage) {
            for (ordinal in (highestStage + 1)..target.ordinal) {
                queue.addLast(CliTorNarrationStage.entries[ordinal])
            }
            highestStage = target.ordinal
        }
        val stage = if (current == null && allowDisplay && queue.isNotEmpty()) queue.removeFirst() else null
        if (stage != null) show(stage)
        revision += 1
    }

    fun advance(): Boolean {
        if (queue.isEmpty()) return false
        show(queue.removeFirst())
        revision += 1
        return true
    }

    fun reset(completedThrough: CliTorNarrationStage? = null) {
        queue.clear()
        current = null
        highestStage = completedThrough?.ordinal ?: -1
        revision += 1
    }

    /** A confirmed identity arrived while LOOKUP was already visible; wake the UI timer. */
    fun signalIdentityReady() {
        revision += 1
    }

    private fun show(stage: CliTorNarrationStage) {
        current = stage
        val (text, tone) = when (stage) {
            CliTorNarrationStage.CONNECTING -> strings.torConnecting to CliLineTone.WARN
            CliTorNarrationStage.CIRCUITS -> strings.torCircuits to CliLineTone.WARN
            CliTorNarrationStage.CONNECTED -> strings.torConnected to CliLineTone.OK
            CliTorNarrationStage.LOOKUP -> strings.torExitLookup to CliLineTone.TOR
        }
        onStage(text, tone)
    }
}
