package com.foxhole.core.runtime

import java.io.File

internal fun interface RuntimeChildProcessLauncher {
    fun launch(
        command: List<String>,
        workingDirectory: File,
    ): Process

    fun launch(
        command: List<String>,
        workingDirectory: File,
        control: RuntimeProcessLaunchControl,
    ): Process = launch(command, workingDirectory).also(control.onSpawn)
}
