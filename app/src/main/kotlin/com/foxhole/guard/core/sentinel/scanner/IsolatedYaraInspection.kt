package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.sentinel.nativeengine.NativeYara
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReason
import com.foxhole.guard.sentinel.nativeengine.NativeYaraReport
import com.foxhole.guard.sentinel.nativeengine.NativeYaraStatus

internal class IsolatedYaraInspection(source: ByteArray) {
    private val session: Long
    private val reports = mutableListOf<NativeYaraReport>()

    init {
        require(source.size in 1..262144)
        NativeYara.loadInIsolatedScannerProcess()
        val compiled = NativeYaraReport.decode(NativeYara.nativeCompile(source))
        if (compiled.status != NativeYaraStatus.COMPLETE || compiled.reason != NativeYaraReason.COMPILED || compiled.sessionId != 1L) {
            throw ScannerYaraCompileUnavailable(compiled.status, compiled.reason)
        }
        session = compiled.sessionId
    }

    fun inspectVerifiedDex(bytes: ByteArray) {
        require(reports.size < 128)
        reports += NativeYaraReport.decode(NativeYara.nativeScanBytes(session, bytes, true))
    }

    fun snapshot(): List<NativeYaraReport> = reports.toList()

    val verifiedDexCoverageComplete: Boolean get() = reports.isNotEmpty() && reports.all {
        it.status == NativeYaraStatus.COMPLETE && it.reason == NativeYaraReason.SCANNED && it.coverageComplete
    }

    fun cancel(close: Boolean) {
        NativeYaraReport.decode(NativeYara.nativeCancel(session, close))
    }
}

internal class ScannerYaraCompileUnavailable(val status: NativeYaraStatus, val reason: NativeYaraReason) :
    IllegalStateException("YARA rule compilation unavailable")
