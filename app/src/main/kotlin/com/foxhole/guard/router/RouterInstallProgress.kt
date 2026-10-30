package com.foxhole.guard.router

import java.io.InputStream

internal enum class RouterInstallProgressStage { DOWNLOAD, UPLOAD, INSTALL, CHECK }

internal data class RouterInstallProgress(
    val stage: RouterInstallProgressStage,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    init {
        require(completedBytes >= 0)
        require(stage == RouterInstallProgressStage.DOWNLOAD || stage == RouterInstallProgressStage.UPLOAD || totalBytes == null)
        require(totalBytes == null || totalBytes > 0 && completedBytes <= totalBytes)
    }
    val percent: Int? get() = totalBytes?.let { (completedBytes.toDouble() * 100 / it).toInt().coerceIn(0, 100) }
}

internal class RouterInstallInputStream(
    private val source: InputStream,
    private val total: Long,
    private val onProgress: (RouterInstallProgress) -> Unit,
) : InputStream() {
    private var completed = 0L
    @Volatile private var closed = false
    private var eofReported = false
    init {
        require(total > 0)
        onProgress(RouterInstallProgress(RouterInstallProgressStage.UPLOAD, 0, total))
    }
    override fun read(): Int {
        if (closed) return -1
        return source.read().also { observed(if (it < 0) -1 else 1) }
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (closed) return -1
        return source.read(bytes, offset, length).also(::observed)
    }
    private fun observed(count: Int) {
        if (closed) return
        if (count > 0) {
            completed += count
            onProgress(RouterInstallProgress(RouterInstallProgressStage.UPLOAD, completed, total))
        } else if (count < 0 && !eofReported && completed == total) {
            eofReported = true
            onProgress(RouterInstallProgress(RouterInstallProgressStage.INSTALL))
        }
    }
    override fun close() { closed = true; source.close() }
}
