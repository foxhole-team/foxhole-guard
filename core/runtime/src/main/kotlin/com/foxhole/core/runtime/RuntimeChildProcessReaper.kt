package com.foxhole.core.runtime

import java.io.File

data class RunningProcessInfo(
    val pid: Int,
    val args: List<String>,
)

class RuntimeChildProcessReaper(
    private val selfPid: Int,
    private val killProcess: (Int) -> Unit,
    private val diagnosticsLogger: RuntimeDiagnosticsSink? = null,
    private val listProcesses: () -> List<RunningProcessInfo> = ::scanProcCmdlines,
) {
    fun reapOrphans(
        executablePath: String,
        dataDirectoryRoot: String,
    ): Int = reapOrphans(setOf(executablePath), dataDirectoryRoot)

    fun reapOrphans(
        executablePaths: Set<String>,
        dataDirectoryRoot: String,
    ): Int {
        val normalizedExecutablePaths = executablePaths.filter(String::isNotBlank).toSet()
        val orphans =
            runCatching { listProcesses() }
                .getOrDefault(emptyList())
                .filter { process ->
                    process.pid != selfPid &&
                        matches(process.args, normalizedExecutablePaths, dataDirectoryRoot)
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

        fun matches(
            args: List<String>,
            executablePath: String,
            dataDirectoryRoot: String,
        ): Boolean = matches(args, setOf(executablePath), dataDirectoryRoot)

        fun matches(
            args: List<String>,
            executablePaths: Set<String>,
            dataDirectoryRoot: String,
        ): Boolean {
            if (args.firstOrNull() in executablePaths) {
                return true
            }
            val normalizedRoot = dataDirectoryRoot.trimEnd('/')
            return normalizedRoot.isNotEmpty() &&
                args.any { argument ->
                    argument == normalizedRoot || argument.startsWith("$normalizedRoot/")
                }
        }

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
