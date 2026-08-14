package com.foxhole.core.sentinel.detection

import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.ThreatIndicatorKind

/**
 * How loud a hit on a published network indicator is allowed to be.
 *
 * One severity for every indicator was the bug: a threat's CDN address and its command-and-control
 * endpoint are both "on the list", but thousands of ordinary apps talk to the first one and nothing
 * benign talks to the second. Scoring them alike turned routine traffic into confident HIGH
 * findings, and a detector that cries wolf about the CDN is a detector nobody reads when the C2
 * entry finally hits.
 *
 * The severity therefore follows what the feed says the indicator IS, and only
 * [ThreatIndicatorKind.COMMAND_AND_CONTROL] can reach [AnomalySeverity.HIGH] on its own.
 */
fun ThreatIndicatorKind.networkIocSeverity(): AnomalySeverity =
    when (this) {
        // Nothing legitimate talks to a controller. This is the one hit that stands alone.
        ThreatIndicatorKind.COMMAND_AND_CONTROL -> AnomalySeverity.HIGH
        // A payload host is a strong signal, but they are routinely compromised legitimate sites,
        // so a hit is worth telling the user about without claiming the device is controlled.
        ThreatIndicatorKind.MALWARE_DISTRIBUTION -> AnomalySeverity.NOTIFICATION
        // The feed said this address is shared. On its own it says nothing about the app that
        // reached it, so it is recorded and never notified — deliberately not a HIGH.
        ThreatIndicatorKind.SHARED_INFRASTRUCTURE -> AnomalySeverity.ACTIVITY_LOG
        // The feed listed it and did not say what it is. Surfaced, because a curated list is still
        // evidence, but never at a confidence the feed never expressed.
        ThreatIndicatorKind.UNCLASSIFIED -> AnomalySeverity.NOTIFICATION
    }

/**
 * The finding's score on the same 0..100 scale as the traffic detectors, following the same
 * ordering as [networkIocSeverity]. Also the tie-break when one indicator is listed twice under
 * different kinds: the louder classification is the one the feed actually asserted.
 */
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
