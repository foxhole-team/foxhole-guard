package com.foxhole.core.runtime

import java.io.File

/** Injectable launcher shared by the remaining app-managed native helpers. */
internal fun interface RuntimeChildProcessLauncher {
    fun launch(
        command: List<String>,
        workingDirectory: File,
    ): Process
}
