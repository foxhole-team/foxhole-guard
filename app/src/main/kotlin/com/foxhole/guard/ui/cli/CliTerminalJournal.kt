package com.foxhole.guard.ui.cli

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import com.foxhole.guard.ui.cli.home.CliTerminalState
import com.foxhole.guard.ui.cli.home.CliTerminalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Where the home terminal's journal lives. Listed in the factory-reset targets. */
internal fun cliTerminalJournalFile(context: Context): File =
    File(File(context.filesDir, CliTerminalStore.JOURNAL_DIR_NAME), CliTerminalStore.JOURNAL_FILE_NAME)

/**
 * The home terminal, with its history read back from disk and every new line written to it.
 *
 * This is what makes the retention row in settings mean what it says. The state holder itself is
 * still a plain in-memory object created by `remember`, so it dies with the composition — but it no
 * longer *is* the history: on the next composition the journal is read back, and nothing except
 * expiry or the user clearing local data removes a line. Rotation, a system theme or font-size
 * change, entering split screen, a language change and the process being reclaimed in the
 * background all stop wiping the log.
 *
 * Writes go to a single background thread, never the caller's: the terminal is written from
 * composition and from status callbacks, and an encrypted rewrite must not land on the frame.
 */
@Composable
internal fun rememberCliTerminalState(strings: CliTerminalStrings): CliTerminalState {
    val appContext = LocalContext.current.applicationContext
    val writer = remember(appContext) {
        CliTerminalJournalWriter(
            store = CliTerminalStore(cliTerminalJournalFile(appContext)),
            retentionHours = { CliTerminalPrefs.readRetentionHours(appContext) },
        )
    }
    val terminal = remember(writer) {
        CliTerminalState(
            strings = strings,
            retentionHours = { CliTerminalPrefs.readRetentionHours(appContext) },
            onCommitted = writer::record,
            onCleared = writer::clear,
        )
    }
    // Read back off the main thread; doing it inside `remember` would put a file read plus a
    // decrypt on the very first composition of the app. The terminal decides WHEN this becomes
    // visible: on a cold start it waits for the profile store too and then shows the whole log at
    // once, because history prepended onto already-visible lines pushed all of them down.
    LaunchedEffect(writer) {
        val restored = withContext(Dispatchers.IO) { writer.load() }
        terminal.onJournalRestored(restored)
    }
    DisposableEffect(writer) {
        onDispose { writer.shutdown() }
    }
    return terminal
}

/**
 * Serialises journal writes onto one background thread and coalesces bursts.
 *
 * Every committed line is queued and a flush is posted; because the flush drains the whole queue
 * and the executor is single-threaded, a burst of connect narration collapses into one rewrite
 * instead of one per line. [shutdown] posts a last flush, so lines committed in the final frames
 * before the activity goes away still reach the file.
 */
internal class CliTerminalJournalWriter(
    private val store: CliTerminalStore,
    private val retentionHours: () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val queue = ConcurrentLinkedQueue<PendingJournalLine>()
    private var generation = 0L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cli-terminal-journal").apply { isDaemon = true }
    }

    fun load(): List<CliTerminalLine> =
        store.load(
            nowMs = nowMs(),
            retentionHours = retentionHours(),
            limit = CliTerminalState.SCREEN_LINE_LIMIT,
        )

    @Synchronized
    fun record(line: CliTerminalLine) {
        val lineGeneration = generation
        queue.add(PendingJournalLine(lineGeneration, line))
        // A rejected task means the writer is already shutting down; the final flush covers it.
        runCatching { executor.execute { flush(lineGeneration) } }
    }

    @Synchronized
    fun clear() {
        generation += 1L
        val clearGeneration = generation
        runCatching {
            executor.execute {
                while (queue.peek()?.generation?.let { it < clearGeneration } == true) {
                    queue.poll()
                }
                store.clear()
            }
        }
    }

    @Synchronized
    fun shutdown() {
        val finalGeneration = generation
        runCatching { executor.execute { flush(finalGeneration) } }
        runCatching { executor.shutdown() }
    }

    private fun flush(targetGeneration: Long) {
        val batch = generateSequence {
            queue.peek()
                ?.takeIf { pending -> pending.generation <= targetGeneration }
                ?.let { queue.poll() }
        }.mapNotNull { pending ->
            pending.takeIf { it.generation == targetGeneration }?.line
        }.toList()
        if (batch.isEmpty()) {
            return
        }
        store.append(newLines = batch, nowMs = nowMs(), retentionHours = retentionHours())
    }

    private data class PendingJournalLine(
        val generation: Long,
        val line: CliTerminalLine,
    )
}
