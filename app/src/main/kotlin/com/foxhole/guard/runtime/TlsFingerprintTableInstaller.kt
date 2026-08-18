package com.foxhole.guard.runtime

import com.foxhole.core.runtime.FoxholeNativeEngine

class TlsFingerprintTableInstaller(
    private val documentInEffect: () -> ByteArray?,
    private val isDownloaded: () -> Boolean,
    private val installTables: (ByteArray) -> Int = FoxholeNativeEngine::nativeInstallTlsFingerprintTables,
    private val clearTables: () -> Unit = FoxholeNativeEngine::nativeClearTlsFingerprintTables,
) {
    constructor(
        provider: TlsFingerprintProvider,
        installTables: (ByteArray) -> Int = FoxholeNativeEngine::nativeInstallTlsFingerprintTables,
        clearTables: () -> Unit = FoxholeNativeEngine::nativeClearTlsFingerprintTables,
    ) : this(
        documentInEffect = provider::documentInEffect,
        isDownloaded = provider::isUsingDownloadedTables,
        installTables = installTables,
        clearTables = clearTables,
    )

    fun install(): TlsFingerprintTableInstall {
        val document =
            runCatching(documentInEffect).getOrNull()
                ?: return clearedBecause("no table document could be read")
        return runCatching { installTables(document) }
            .fold(
                onSuccess = { replaced ->
                    if (replaced >= 0) {
                        TlsFingerprintTableInstall(
                            profilesReplaced = replaced,
                            downloaded = runCatching(isDownloaded).getOrDefault(false),
                        )
                    } else {
                        clearedBecause("the core refused the document (code $replaced)")
                    }
                },
                onFailure = { error -> clearedBecause(error.message ?: error.javaClass.simpleName) },
            )
    }

    private fun clearedBecause(reason: String): TlsFingerprintTableInstall {
        runCatching(clearTables)
        return TlsFingerprintTableInstall(profilesReplaced = 0, downloaded = false, reason = reason)
    }
}

data class TlsFingerprintTableInstall(
    val profilesReplaced: Int,
    val downloaded: Boolean,
    val reason: String? = null,
)
