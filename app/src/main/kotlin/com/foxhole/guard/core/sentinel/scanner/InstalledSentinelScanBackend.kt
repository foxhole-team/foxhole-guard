package com.foxhole.guard.core.sentinel.scanner

import android.content.Context
import com.foxhole.guard.core.sentinel.apk.InspectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class InstalledSentinelScanBackend(
    context: Context,
    private val ruleSource: suspend () -> ScannerYaraSource?,
) {
    private val appContext = context.applicationContext
    private val sourceProvider = InstalledScannerSourceProvider(appContext)
    private val client = IsolatedScannerClient(appContext)
    private var captured: InstalledScannerSource? = null

    val packagedEngineAvailable: Boolean get() =
        File(appContext.applicationInfo.nativeLibraryDir, "libfoxhole_sentinel_yara.so").isFile

    suspend fun capture(packageName: String): SentinelScanSource {
        check(packagedEngineAvailable) { "Packaged scanner engine unavailable" }
        val source = sourceProvider.capture(packageName)
        captured = source
        return SentinelScanSource(source.packageName, source.installationId)
    }

    suspend fun isCurrent(source: SentinelScanSource): Boolean {
        val current = captured ?: return false
        return current.packageName == source.packageName && current.installationId == source.installationId &&
            sourceProvider.isCurrent(current)
    }

    suspend fun inspect(
        source: SentinelScanSource,
        policy: SentinelScanWorkPolicy,
        current: () -> Boolean,
    ): SentinelScanSummary {
        val installation = requireNotNull(captured)
        require(installation.installationId == source.installationId && current())
        val rules = if (policy.yaraEnabled) {
            requireNotNull(
                ruleSource()
            ) { "Verified scanner rules unavailable" }
        } else {
            null
        }
        val owner = ScannerRequestOwner(UUID.randomUUID().toString(), installation.installationId, policy.ruleEpoch)
        val inputs = installation.artifacts.map {
            ScannerInputFile(ScannerDescriptorClaim(it.label, it.kind, it.bytes), File(it.path))
        }
        val result = client.inspect(inputs, owner, rules, policy.maxArtifactBytes) {
            if (current()) owner else owner.copy(installationId = "stale")
        }
        val complete = result.status == InspectionStatus.COMPLETE && result.coverageComplete &&
            (!policy.yaraEnabled || result.yara?.verifiedDexComplete == true)
        return SentinelScanSummary(
            source,
            complete,
            result.weakObservations.map { it.name }.toSet(),
            result.yara?.matchedRuleIds.orEmpty().toSet(),
            result.issues + scannerSummaryLimitations(result.limitations, result.yara?.verifiedDexComplete == true).map { it.name },
            outcome = when {
                complete -> SentinelScanStage.COMPLETE
                result.status == InspectionStatus.UNAVAILABLE -> SentinelScanStage.UNAVAILABLE
                result.status == InspectionStatus.UNSUPPORTED -> SentinelScanStage.UNSUPPORTED
                else -> SentinelScanStage.PARTIAL
            },
        )
    }

    suspend fun visiblePackages(): List<String> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        appContext.packageManager.getInstalledApplications(0).map { it.packageName }.distinct().take(4096)
    }
}
