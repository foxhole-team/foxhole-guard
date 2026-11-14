package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Serializable
private data class ScanReportDocument(val schema: Int = 1, val reports: List<SentinelScanReport>)

internal data class ScanReportHistoryState(
    val reports: List<SentinelScanReport> = emptyList(),
    val loaded: Boolean = false,
    val unavailable: Boolean = false,
)

internal class SentinelScanReportStore(
    private val file: File,
    private val cipher: FileCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    private val mutableHistory = MutableStateFlow(ScanReportHistoryState())
    val history: StateFlow<ScanReportHistoryState> = mutableHistory.asStateFlow()

    fun unavailable() { mutableHistory.value = ScanReportHistoryState(unavailable = true) }

    fun restore(processOwner: String) = synchronized(lock) {
        val previous = read()
        val restored = previous.filter { it.startedAtMs >= now() - RETENTION_MILLIS }.map { report ->
            if (report.status == ScanReportStatus.RUNNING && report.processOwner != processOwner) report.copy(
                status = ScanReportStatus.INTERRUPTED,
                finishedAtMs = maxOf(report.startedAtMs, now()),
                issues = report.issues + "PROCESS_INTERRUPTED",
            ) else report
        }
        if (restored != previous) write(restored) else {
            mutableHistory.value = ScanReportHistoryState(restored, loaded = true)
        }
    }

    fun save(report: SentinelScanReport) = synchronized(lock) {
        validate(report)
        val cutoff = now() - RETENTION_MILLIS
        val retained = (read().filter { it.id != report.id && it.startedAtMs >= cutoff } + report)
            .sortedByDescending { it.startedAtMs }.take(MAX_REPORTS).toMutableList()
        while (encode(retained).size > MAX_BYTES && retained.size > 1) retained.removeAt(retained.lastIndex)
        write(retained)
    }

    fun clear() = synchronized(lock) {
        check(!file.exists() || file.delete()) { "Scan report cleanup failed" }
        mutableHistory.value = ScanReportHistoryState(loaded = true)
    }

    private fun read(): List<SentinelScanReport> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() in 1..(MAX_BYTES + 1024).toLong())
        val bytes = cipher.readBytes(file)
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        checkStructure(text)
        val document = json.decodeFromString(ScanReportDocument.serializer(), text)
        require(document.schema == 1 && document.reports.size <= MAX_REPORTS)
        require(document.reports.map { it.id }.toSet().size == document.reports.size)
        document.reports.forEach(::validate)
        return document.reports
    }

    private fun write(reports: List<SentinelScanReport>) {
        reports.forEach(::validate)
        val bytes = encode(reports)
        require(bytes.size <= MAX_BYTES)
        cipher.writeBytesAtomic(file, bytes)
        mutableHistory.value = ScanReportHistoryState(reports, loaded = true)
    }

    private fun encode(reports: List<SentinelScanReport>): ByteArray =
        json.encodeToString(ScanReportDocument.serializer(), ScanReportDocument(reports = reports)).encodeToByteArray()

    private fun validate(report: SentinelScanReport) {
        require(report.id.matches(ID) && report.processOwner.matches(ID) && report.requestSessionId.matches(ID))
        report.packageName?.let { require(it.length <= 255 && it.matches(PACKAGE)) }
        require(report.requestType != "PACKAGE" || report.packageName != null)
        require(report.installationId == null || report.installationId.matches(ID))
        require(report.packageVersion == null || report.packageVersion >= 0)
        require(report.startedAtMs >= 0 && (report.finishedAtMs == null || report.finishedAtMs >= report.startedAtMs))
        require((report.status == ScanReportStatus.RUNNING) == (report.finishedAtMs == null))
        require(report.requestType in setOf("PACKAGE", "BASELINE", "AUTOMATIC"))
        require(report.appVersion.length in 1..128 && (report.ruleEpoch == null || report.ruleEpoch.length in 1..128))
        require(report.artifactByteLimit in setOf(16 * 1024 * 1024, 64 * 1024 * 1024))
        val requestedPackages = report.requestedPackages
        require(if (requestedPackages == null) report.completedPackages == 0 else requestedPackages in 0..4096 && report.completedPackages in 0..requestedPackages)
        require(report.analyzerVersion == null || report.analyzerVersion.length in 1..128)
        require(report.artifacts.size <= 32 && report.artifacts.map { it.sourceId }.toSet().size == report.artifacts.size)
        report.artifacts.forEach { artifact ->
            require(artifact.sourceId.matches(ID) && artifact.kind in setOf("BASE", "SPLIT"))
            require(artifact.inputBytes in 0..64L * 1024 * 1024)
            require(artifact.sha256 == null || artifact.inputComplete && artifact.sha256.matches(SHA256))
            require(artifact.entriesDeclared in 0..4096 && artifact.entriesVerified in 0..artifact.entriesDeclared)
        }
        val coverage = report.coverage
        require(coverage.requestedArtifacts in 0..32 && coverage.openedArtifacts in 0..coverage.requestedArtifacts)
        require(coverage.declaredDexEntries in 0..4096 && coverage.completedDexEntries in 0..minOf(coverage.declaredDexEntries, 128))
        require(coverage.expandedBytes in 0..128L * 1024 * 1024 && coverage.workUnits in 0..10_000_000L)
        for (codes in listOf(report.observations, report.ruleIds, report.issues, report.limitations)) {
            require(codes.size <= 64 && codes.all { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char in "_:-." } })
        }
        if (report.status == ScanReportStatus.COMPLETE) {
            require(report.issues.isEmpty() && report.artifacts.isNotEmpty())
            require(report.artifacts.size == coverage.requestedArtifacts && coverage.openedArtifacts == coverage.requestedArtifacts)
            require(report.artifacts.all { it.inputComplete && it.containerValidated && it.sha256 != null && it.entriesVerified == it.entriesDeclared })
            require(coverage.completedDexEntries > 0 && coverage.completedDexEntries == coverage.declaredDexEntries)
        }
    }

    private fun checkStructure(text: String) {
        var depth = 0
        var containers = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; containers++; require(depth <= 12 && containers <= 8192) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
    }

    private companion object {
        const val MAX_REPORTS = 100
        const val MAX_BYTES = 2 * 1024 * 1024
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        val ID = Regex("[A-Za-z0-9_.-]{1,128}")
        val PACKAGE = Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)*")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
