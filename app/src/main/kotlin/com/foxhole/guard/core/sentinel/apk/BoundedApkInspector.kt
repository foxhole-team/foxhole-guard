package com.foxhole.guard.core.sentinel.apk

import com.foxhole.guard.core.sentinel.dex.BoundedDexInspector
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Finite, synchronous work. The caller chooses its IO owner and supplies the complete current APK set. */
internal class BoundedApkInspector(private val nanoTime: () -> Long = System::nanoTime) {
    fun inspect(
        inputs: List<ApkScanInput>,
        budget: ScanBudget = ScanBudget(),
        cancellation: ScanCancellation = ScanCancellation { false },
        onVerifiedDex: ((ByteArray) -> Unit)? = null,
    ): ApkInspection {
        val control = ScanControl(budget, cancellation, nanoTime)
        val state = ApkInspectionState(inputs.size, control)
        if (!validInputs(inputs, budget)) {
            state.issue(InspectionReason.INVALID_INPUT_SET, InspectionStatus.INCONCLUSIVE)
            return state.result()
        }
        for (source in inputs) {
            val progress = ApkArtifactProgress(source)
            try {
                control.checkpoint()
                val bytes = source.open().use {
                    readOwnedInput(
                        it,
                        control
                    ) { bytes -> progress.inputBytes = bytes.toLong() }
                }
                progress.inputBytes = bytes.size.toLong()
                progress.inputComplete = true
                progress.sha256 = bytes.sha256()
                inspectArtifact(bytes, progress, state, control, onVerifiedDex)
            } catch (stopped: InspectionStopped) {
                state.issue(stopped.reason, stopped.status, source.sourceId)
            } catch (_: CancellationException) {
                state.issue(InspectionReason.CANCELLED, InspectionStatus.PARTIAL, source.sourceId)
            } catch (_: IOException) {
                state.issue(InspectionReason.INPUT_UNAVAILABLE, InspectionStatus.UNAVAILABLE, source.sourceId)
            } catch (_: SecurityException) {
                state.issue(InspectionReason.INPUT_UNAVAILABLE, InspectionStatus.UNAVAILABLE, source.sourceId)
            }
            state.artifacts.add(progress.record())
            if (state.issues.any { it.reason in STOP_REASONS }) break
        }
        if (state.declaredDexEntries == 0 && state.issues.isEmpty()) {
            state.issue(
                InspectionReason.NO_DEX_PRESENT,
                InspectionStatus.INCONCLUSIVE
            )
        }
        return state.result()
    }

    private fun inspectArtifact(
        bytes: InspectionBytes,
        progress: ApkArtifactProgress,
        state: ApkInspectionState,
        control: ScanControl,
        onVerifiedDex: ((ByteArray) -> Unit)?,
    ) {
        val entries = BoundedZipArchive(bytes, control).entries()
        progress.entriesDeclared = entries.size
        if (entries.size > control.budget.maxZipEntries - state.declaredZipEntries) {
            stopInspection(
                InspectionReason.ENTRY_COUNT_LIMIT
            )
        }
        state.declaredZipEntries += entries.size
        val dexNames = entries.filter { isDexName(it.name) }
        state.declaredDexEntries += dexNames.size
        if (state.declaredDexEntries > control.budget.maxDexEntries) stopInspection(InspectionReason.DEX_ENTRY_LIMIT)
        val decoder = ZipEntryDecoder(bytes, control)
        for (entry in entries) {
            control.checkpoint()
            val isDex = isDexName(entry.name)
            if (!isDex && entry.name.endsWith(".dex", ignoreCase = true)) {
                state.issue(
                    InspectionReason.NONSTANDARD_DEX_LOCATION,
                    InspectionStatus.INCONCLUSIVE,
                    progress.source.sourceId
                )
            }
            val decoded = decoder.verify(entry, retain = isDex)
            progress.entriesVerified++
            if (decoded != null) {
                val inspection = BoundedDexInspector().inspect(decoded, control)
                state.dexEntries.add(
                    ApkDexInspection(
                        progress.source.sourceId,
                        entry.name,
                        InspectionBytes(decoded).sha256(),
                        inspection
                    )
                )
                if (inspection.status == InspectionStatus.COMPLETE && onVerifiedDex != null) {
                    control.checkpoint()
                    onVerifiedDex(decoded)
                    control.checkpoint()
                }
                inspection.reason?.let { state.issue(it, inspection.status, progress.source.sourceId, entry.name) }
                if (inspection.reason in STOP_REASONS) stopInspection(requireNotNull(inspection.reason))
            }
        }
        progress.containerValidated = true
    }

    private fun validInputs(inputs: List<ApkScanInput>, budget: ScanBudget): Boolean =
        inputs.size in 1..budget.maxArtifacts && inputs.count { it.kind == ApkSourceKind.BASE } == 1 &&
            inputs.map { it.sourceId }.toSet().size == inputs.size && inputs.all { SOURCE_ID.matches(it.sourceId) }

    private fun isDexName(name: String): Boolean = DEX_NAME.matches(name)

    private companion object {
        val SOURCE_ID = Regex("[A-Za-z0-9_.-]{1,64}")
        val DEX_NAME = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
        val STOP_REASONS = setOf(
            InspectionReason.CANCELLED,
            InspectionReason.DEADLINE_REACHED,
            InspectionReason.WORK_LIMIT,
            InspectionReason.TOTAL_EXPANDED_BYTE_LIMIT
        )
    }
}

private class ApkArtifactProgress(val source: ApkScanInput) {
    var inputBytes = 0L
    var inputComplete = false
    var sha256: String? = null
    var entriesDeclared = 0
    var entriesVerified = 0
    var containerValidated = false
    fun record(): ArtifactInspection = ArtifactInspection(
        source.sourceId,
        source.kind,
        inputBytes,
        inputComplete,
        sha256,
        entriesDeclared,
        entriesVerified,
        containerValidated
    )
}

private class ApkInspectionState(private val requestedArtifacts: Int, private val control: ScanControl) {
    val artifacts = mutableListOf<ArtifactInspection>()
    val dexEntries = mutableListOf<ApkDexInspection>()
    val issues = mutableListOf<InspectionIssue>()
    private val failures = mutableListOf<InspectionStatus>()
    var declaredDexEntries = 0
    var declaredZipEntries = 0

    fun issue(reason: InspectionReason, status: InspectionStatus, sourceId: String? = null, dexName: String? = null) {
        val issue = InspectionIssue(reason, sourceId, dexName)
        if (issue !in issues) {
            issues.add(issue)
            failures.add(status)
        }
    }

    fun result(): ApkInspection {
        val zipComplete = artifacts.size == requestedArtifacts && artifacts.all {
            it.inputComplete && it.containerValidated && it.entriesDeclared == it.entriesVerified
        }
        val dexComplete = declaredDexEntries > 0 && declaredDexEntries == dexEntries.size && dexEntries.all {
            it.inspection.status == InspectionStatus.COMPLETE
        }
        val complete = zipComplete && dexComplete && issues.isEmpty()
        val completed = buildSet {
            if (zipComplete) add(StaticCoverage.ZIP_ENTRY_INTEGRITY)
            if (dexComplete) {
                add(StaticCoverage.DEX_REFERENCE_TABLES)
                add(StaticCoverage.DEX_CODE_LAYOUT)
            }
        }
        val coverage =
            InspectionCoverage(
                StaticCoverage.entries.toSet(),
                completed,
                requestedArtifacts,
                artifacts.count { it.inputComplete },
                declaredDexEntries,
                dexEntries.count { it.inspection.status == InspectionStatus.COMPLETE },
                complete
            )
        return ApkInspection(
            "bounded-apk-dex-2", status(complete), artifacts.toList(), dexEntries.toList(), issues.toList(), coverage,
            StaticLimitation.entries.toSet(), fingerprint(), control.elapsedMillis(), control.expandedBytes, control.workUnits,
        )
    }

    private fun status(complete: Boolean): InspectionStatus = when {
        complete -> InspectionStatus.COMPLETE
        InspectionStatus.PARTIAL in failures || dexEntries.any {
            it.inspection.status == InspectionStatus.COMPLETE
        } -> InspectionStatus.PARTIAL
        InspectionStatus.UNSUPPORTED in failures -> InspectionStatus.UNSUPPORTED
        InspectionStatus.UNAVAILABLE in failures -> InspectionStatus.UNAVAILABLE
        else -> InspectionStatus.INCONCLUSIVE
    }

    private fun fingerprint(): String? {
        if (artifacts.size != requestedArtifacts || artifacts.any { !it.inputComplete || it.sha256 == null }) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("bounded-apk-dex-2\n".toByteArray(Charsets.US_ASCII))
        artifacts.sortedBy { it.sourceId }.forEach {
            digest.update(
                "${it.kind.name}:${it.sourceId}:${it.inputBytes}:${it.sha256}\n".toByteArray(Charsets.US_ASCII)
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
