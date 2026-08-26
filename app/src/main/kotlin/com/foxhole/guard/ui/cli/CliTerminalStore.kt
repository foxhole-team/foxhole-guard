package com.foxhole.guard.ui.cli

import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.FileCipher
import com.foxhole.guard.ui.cli.home.CliLineIcon
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal class CliTerminalStore(
    private val file: File,
    private val fileCipher: FileCipher = AndroidKeystoreFileCipher(KEYSTORE_ALIAS),
    private val maxLines: Int = MAX_PERSISTED_LINES,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private var cached: List<CliTerminalLine>? = null

    @Synchronized
    fun load(
        nowMs: Long,
        retentionHours: Int,
        limit: Int,
    ): List<CliTerminalLine> {
        val retained = readAll().retain(nowMs, retentionHours)
        cached = retained
        return retained.takeLast(limit)
    }

    @Synchronized
    fun append(
        newLines: List<CliTerminalLine>,
        nowMs: Long,
        retentionHours: Int,
    ) {
        if (newLines.isEmpty()) {
            return
        }
        val merged = (cached ?: readAll()) + newLines
        val retained = merged.retain(nowMs, retentionHours)
        cached = retained
        write(retained)
    }

    @Synchronized
    fun clear() {
        cached = emptyList()
        runCatching { file.delete() }
    }

    private fun List<CliTerminalLine>.retain(
        nowMs: Long,
        retentionHours: Int,
    ): List<CliTerminalLine> {
        val cutoffMs = nowMs - CliTerminalPrefs.normalizedHours(retentionHours) * MS_PER_HOUR
        return filter { line -> line.timestampMs >= cutoffMs }.takeLast(maxLines)
    }

    private fun readAll(): List<CliTerminalLine> {
        cached?.let { return it }
        if (!file.isFile) {
            return emptyList()
        }
        return runCatching {
            fileCipher
                .readBytes(file)
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<PersistedTerminalLine>(line) }.getOrNull()
                }
                .map(PersistedTerminalLine::toLine)
                .toList()
        }.getOrElse {
            runCatching { file.delete() }
            emptyList()
        }
    }

    private fun write(lines: List<CliTerminalLine>) {
        runCatching {
            file.parentFile?.mkdirs()
            if (lines.isEmpty()) {
                file.delete()
                return
            }
            val payload = lines
                .joinToString(separator = "\n", postfix = "\n") { line ->
                    json.encodeToString(line.toPersisted())
                }
                .toByteArray(Charsets.UTF_8)
            fileCipher.writeBytesAtomic(file, payload)
        }
    }

    private fun CliTerminalLine.toPersisted(): PersistedTerminalLine =
        PersistedTerminalLine(
            timestampMs = timestampMs,
            text = text,
            tone = tone,
            icon = icon,
            prompt = prompt,
            flagCountry = flagCountry,
            value = value,
            valueTone = valueTone,
            packages = packages,
            valueLeading = valueLeading,
            inlineValue = inlineValue,
            typed = typed,
            footnote = footnote,
        )

    @Serializable
    private data class PersistedTerminalLine(
        val timestampMs: Long,
        val text: String,
        val tone: CliLineTone = CliLineTone.PLAIN,
        val icon: CliLineIcon? = null,
        val prompt: Boolean = false,
        val flagCountry: String? = null,
        val value: String? = null,
        val valueTone: CliLineTone = tone,
        val packages: List<String> = emptyList(),
        val valueLeading: Boolean = false,
        val inlineValue: Boolean = false,
        val typed: Boolean = true,
        val footnote: Boolean = false,
    ) {
        fun toLine(): CliTerminalLine =
            CliTerminalLine(
                timestampMs = timestampMs,
                text = text,
                tone = tone,
                icon = icon,
                prompt = prompt,
                flagCountry = flagCountry,
                value = value,
                valueTone = valueTone,
                packages = packages,
                valueLeading = valueLeading,
                inlineValue = inlineValue,
                typed = typed,
                footnote = footnote,
            )
    }

    companion object {
        const val JOURNAL_DIR_NAME = "cli-terminal-journal"
        const val JOURNAL_FILE_NAME = "terminal.jsonl.enc"

        private const val KEYSTORE_ALIAS = "foxhole.cli.terminal.journal"

        private const val MAX_PERSISTED_LINES = 4_000

        private const val MS_PER_HOUR = 3_600_000L
    }
}
