package com.foxhole.guard.ui.cli.sentinel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.CapabilityState
import com.foxhole.core.model.InstalledAppAssessment
import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppCapability
import com.foxhole.core.model.InstalledAppEvidenceKind
import com.foxhole.core.model.InstalledAppThreatConfidence
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.confidenceLabelRes
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliRowDivider

@Composable
internal fun InstalledAppAssessmentContent(assessment: InstalledAppAssessment) {
    Text(stringResource(R.string.sentinel_assessment_title), style = CliType.body)
    Text(stringResource(assessment.status.assessmentStatusResource()), style = CliType.small)
    Text(stringResource(assessment.threatConfidence.confidenceLabelRes()), style = CliType.small)
    Text(stringResource(R.string.sentinel_assessment_context), style = CliType.small, color = LocalCliColors.current.dim)
    for (capability in assessment.capabilityExposure) {
        Column(Modifier.fillMaxWidth().padding(vertical = CliSpacing.sm)) {
            Text(stringResource(capability.capability.capabilityResource()), style = CliType.body)
            Text("${stringResource(R.string.sentinel_assessment_declared)}: ${stringResource(capability.declared.stateResource())}", style = CliType.small)
            Text("${stringResource(R.string.sentinel_assessment_enabled)}: ${stringResource(capability.enabled.stateResource())}", style = CliType.small)
            Text("${stringResource(R.string.sentinel_assessment_granted)}: ${stringResource(capability.granted.stateResource())}", style = CliType.small)
        }
    }
    if (assessment.matchedEvidence.isNotEmpty()) {
        Text(stringResource(R.string.sentinel_assessment_matches), style = CliType.body)
        Text(stringResource(R.string.sentinel_assessment_match_note), style = CliType.small)
        for (match in assessment.matchedEvidence) {
            CliRowDivider()
            Text(stringResource(match.kind.evidenceResource()), style = CliType.body)
            SentinelTechnicalValue(match.observedValue)
            Text("${stringResource(R.string.sentinel_assessment_source)}: ${match.sourceId ?: stringResource(R.string.sentinel_assessment_source_unknown)}", style = CliType.small)
            Text("${stringResource(R.string.sentinel_assessment_feed)}: ${match.feedVersion ?: stringResource(R.string.sentinel_assessment_unknown)}", style = CliType.small)
            match.limitations.forEach { Text(it, style = CliType.small, color = LocalCliColors.current.dim) }
        }
    }
    if (assessment.limitations.isNotEmpty()) {
        Text(stringResource(R.string.sentinel_assessment_limitations), style = CliType.body)
        assessment.limitations.forEach { Text(it, style = CliType.small, color = LocalCliColors.current.dim) }
    }
}

@Composable
internal fun SentinelTechnicalValue(value: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.sentinel_assessment_value)
    val displayed = value.take(8192)
    Text(displayed, style = CliType.small)
    if (value.length > displayed.length) Text(stringResource(R.string.sentinel_report_display_limited), style = CliType.small)
    CliActionRow(stringResource(R.string.sentinel_assessment_copy), onTap = {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, displayed))
    })
}


private fun InstalledAppAssessmentStatus.assessmentStatusResource(): Int = when (this) {
    InstalledAppAssessmentStatus.COMPLETE -> R.string.sentinel_assessment_status_complete
    InstalledAppAssessmentStatus.PARTIAL -> R.string.sentinel_assessment_status_partial
    InstalledAppAssessmentStatus.UNAVAILABLE -> R.string.sentinel_assessment_status_unavailable
}

private fun CapabilityState.stateResource(): Int = when (this) {
    CapabilityState.UNKNOWN -> R.string.sentinel_assessment_unknown
    CapabilityState.NO -> R.string.sentinel_assessment_no
    CapabilityState.YES -> R.string.sentinel_assessment_yes
}

private fun InstalledAppCapability.capabilityResource(): Int = when (this) {
    InstalledAppCapability.ACCESSIBILITY -> R.string.sentinel_assessment_accessibility
    InstalledAppCapability.NOTIFICATION_LISTENER -> R.string.sentinel_assessment_notification
    InstalledAppCapability.DEVICE_ADMIN -> R.string.sentinel_assessment_admin
    InstalledAppCapability.VPN -> R.string.sentinel_assessment_vpn
    InstalledAppCapability.OVERLAY -> R.string.sentinel_assessment_overlay
    InstalledAppCapability.BATTERY_OPTIMIZATION_EXEMPTION -> R.string.sentinel_assessment_battery
    InstalledAppCapability.AUTOSTART -> R.string.sentinel_assessment_autostart
}

private fun InstalledAppEvidenceKind.evidenceResource(): Int = when (this) {
    InstalledAppEvidenceKind.PACKAGE_NAME -> R.string.sentinel_assessment_package_match
    InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA256 -> R.string.sentinel_assessment_current_sha256
    InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA256 -> R.string.sentinel_assessment_historical_sha256
    InstalledAppEvidenceKind.CURRENT_CERTIFICATE_SHA1 -> R.string.sentinel_assessment_current_sha1
    InstalledAppEvidenceKind.HISTORICAL_CERTIFICATE_SHA1 -> R.string.sentinel_assessment_historical_sha1
}
