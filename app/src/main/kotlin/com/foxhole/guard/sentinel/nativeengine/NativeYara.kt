package com.foxhole.guard.sentinel.nativeengine

import android.os.Process

internal object NativeYara {
    private var loaded = false

    @Synchronized
    fun loadInIsolatedScannerProcess() {
        check(Process.myUid() % 100_000 in 99_000..99_999) { "Isolated scanner process required" }
        if (!loaded) {
            System.loadLibrary("foxhole_sentinel_yara")
            loaded = true
        }
    }

    external fun nativeIsReadonlyRegularFd(fd: Int): Boolean
    external fun nativeCompile(source: ByteArray): ByteArray
    external fun nativeScanBytes(sessionId: Long, data: ByteArray, completeInput: Boolean): ByteArray
    external fun nativeScanFd(sessionId: Long, fd: Int, offset: Long, length: Long, completeInput: Boolean): ByteArray
    external fun nativeAcceptOwnedBlob(sessionId: Long, blob: ByteArray): ByteArray
    external fun nativeCompiledBlob(sessionId: Long): ByteArray?
    external fun nativeCancel(sessionId: Long, close: Boolean): ByteArray
}
