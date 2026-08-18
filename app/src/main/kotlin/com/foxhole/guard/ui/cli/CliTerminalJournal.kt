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

internal fun cliTerminalJournalFile(context: Context): File =
    File(File(context.filesDir, CliTerminalStore.JOURNAL_DIR_NAME), CliTerminalStore.JOURNAL_FILE_NAME)

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
    LaunchedEffect(writer) {
        val restored = withContext(Dispatchers.IO) { writer.load() }
        terminal.onJournalRestored(restored)
    }
    DisposableEffect(writer) {
        onDispose { writer.shutdown() }
    }
    return terminal
}

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
