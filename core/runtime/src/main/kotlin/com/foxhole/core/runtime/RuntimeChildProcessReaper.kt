package com.foxhole.core.runtime

import java.io.File

/** Process identity reduced to the fields needed for private-child teardown. */
data class RunningProcessInfo(
    val pid: Int,
    val args: List<String>,
)

/**
 * Last-resort teardown for helper processes owned by this app.
 *
 * A native engine can outlive its graceful close deadline while a managed pluggable transport or
 * I2P helper still holds sockets. The executable and data-directory roots passed here are private
 * to the app UID, so matching both avoids touching unrelated processes.
 */
class RuntimeChildProcessReaper(
    private val selfPid: Int,
    private val killProcess: (Int) -> Unit,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
    private val listProcesses: () -> List<RunningProcessInfo> = ::scanProcCmdlines,
) {
    /** Kills every matching child except the current process; returns the number reaped. */
    fun reapOrphans(
        executablePath: String,
        dataDirectoryRoot: String,
    ): Int {
        val orphans =
            runCatching { listProcesses() }
                .getOrDefault(emptyList())
                .filter { process ->
                    process.pid != selfPid &&
                        matches(process.args, executablePath, dataDirectoryRoot)
                }
        orphans.forEach { process -> runCatching { killProcess(process.pid) } }
        if (orphans.isNotEmpty()) {
            diagnosticsLogger?.recordStructured(
                "runtime",
                "private runtime child orphans reaped",
                "count=${orphans.size}",
                "pids=${orphans.joinToString(",") { process -> process.pid.toString() }}",
            )
        }
        return orphans.size
    }

    companion object {
        private val CMDLINE_ARG_SEPARATOR = Char.MIN_VALUE

        /**
         * Matches either the exact private executable or an argument rooted in the private data
         * directory. Managed transports inherit that directory even when their executable differs.
         */
        fun matches(
            args: List<String>,
            executablePath: String,
            dataDirectoryRoot: String,
        ): Boolean {
            if (args.firstOrNull() == executablePath) {
                return true
            }
            val normalizedRoot = dataDirectoryRoot.trimEnd('/')
            return normalizedRoot.isNotEmpty() &&
                args.any { argument ->
                    argument == normalizedRoot || argument.startsWith("$normalizedRoot/")
                }
        }

        /** Reads `/proc/<pid>/cmdline` for every visible numeric pid. */
        internal fun scanProcCmdlines(): List<RunningProcessInfo> =
            File("/proc")
                .listFiles { file -> file.isDirectory && file.name.all(Char::isDigit) }
                .orEmpty()
                .mapNotNull { directory ->
                    val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
                    val raw =
                        runCatching { File(directory, "cmdline").readBytes() }.getOrNull()
                            ?: return@mapNotNull null
                    val args =
                        String(raw, Charsets.UTF_8)
                            .split(CMDLINE_ARG_SEPARATOR)
                            .filter(String::isNotEmpty)
                    if (args.isEmpty()) null else RunningProcessInfo(pid, args)
                }
    }
}
