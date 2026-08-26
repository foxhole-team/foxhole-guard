package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.ThreatIndicatorKind

fun ThreatIndicatorKind.networkIocSeverity(): AnomalySeverity =
    when (this) {
        ThreatIndicatorKind.COMMAND_AND_CONTROL -> AnomalySeverity.HIGH

        ThreatIndicatorKind.MALWARE_DISTRIBUTION -> AnomalySeverity.NOTIFICATION

        ThreatIndicatorKind.SHARED_INFRASTRUCTURE -> AnomalySeverity.ACTIVITY_LOG

        ThreatIndicatorKind.UNCLASSIFIED -> AnomalySeverity.NOTIFICATION
    }

fun ThreatIndicatorKind.networkIocScore(): Int =
    when (this) {
        ThreatIndicatorKind.COMMAND_AND_CONTROL -> NETWORK_IOC_COMMAND_AND_CONTROL_SCORE
        ThreatIndicatorKind.MALWARE_DISTRIBUTION -> NETWORK_IOC_MALWARE_DISTRIBUTION_SCORE
        ThreatIndicatorKind.SHARED_INFRASTRUCTURE -> NETWORK_IOC_SHARED_INFRASTRUCTURE_SCORE
        ThreatIndicatorKind.UNCLASSIFIED -> NETWORK_IOC_UNCLASSIFIED_SCORE
    }

const val NETWORK_IOC_COMMAND_AND_CONTROL_SCORE = 100
const val NETWORK_IOC_MALWARE_DISTRIBUTION_SCORE = 80
const val NETWORK_IOC_UNCLASSIFIED_SCORE = 60
const val NETWORK_IOC_SHARED_INFRASTRUCTURE_SCORE = 30
