package com.foxhole.core.runtime

import java.io.File

internal fun interface RuntimeChildProcessLauncher {
    fun launch(
        command: List<String>,
        workingDirectory: File,
    ): Process
}
