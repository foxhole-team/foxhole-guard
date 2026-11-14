package com.foxhole.guard.core.sentinel

import com.foxhole.core.model.InstalledAppAssessmentStatus
import com.foxhole.core.model.InstalledAppThreatConfidence
import com.foxhole.guard.R

internal fun InstalledAppThreatConfidence.confidenceLabelRes(): Int = when (this) {
    InstalledAppThreatConfidence.UNKNOWN -> R.string.sentinel_assessment_confidence_unknown
    InstalledAppThreatConfidence.NO_CORROBORATING_EVIDENCE -> R.string.sentinel_assessment_confidence_none
    InstalledAppThreatConfidence.LIMITED_INDICATOR_MATCH -> R.string.sentinel_assessment_confidence_limited
    InstalledAppThreatConfidence.STRONG_INDICATOR_MATCH -> R.string.sentinel_assessment_confidence_strong
}

internal fun InstalledAppAssessmentStatus.assessmentStatusLabelRes(): Int = when (this) {
    InstalledAppAssessmentStatus.COMPLETE -> R.string.sentinel_assessment_status_complete
    InstalledAppAssessmentStatus.PARTIAL -> R.string.sentinel_assessment_status_partial
    InstalledAppAssessmentStatus.UNAVAILABLE -> R.string.sentinel_assessment_status_unavailable
}
