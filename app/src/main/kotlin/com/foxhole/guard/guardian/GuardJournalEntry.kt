package com.foxhole.guard.guardian

import kotlinx.serialization.Serializable

@Serializable
enum class GuardEventType {
    GENESIS,
    PACKAGE_ADDED,
    PACKAGE_REMOVED,
    PACKAGE_REPLACED,
    BOOT_COMPLETED,
    MY_PACKAGE_REPLACED,
    SERVICE_STARTED,
    SERVICE_STOPPED,
    SERVICE_DESTROYED,
    TASK_REMOVED,
    HEARTBEAT,
    BLACKOUT_SUSPECTED,
    CLOCK_ANOMALY,
    SECURITY_SETTING_CHANGED,
    UNLOCK_OK,
    UNLOCK_FAILED,
    GUARD_ENABLED,
    GUARD_DISABLE_REQUESTED,
    GUARD_DISABLED,
    STATS_PAUSED,
    STATS_RESUMED,
    MONITORING_TOGGLE_ATTEMPT,
    HISTORY_CLEARED,
    VPN_RESTORED_FROM_SNAPSHOT,
    VPN_RESTORE_DEFERRED,
    CORE_FLOW_BLOCKED,
    CORE_DNS_BLOCKED,
    CORE_CONFIG_APPLIED,
    CORE_CONFIRMATION_REQUIRED,
    CORE_CONFIRMATION_EXPIRED,
    CORE_OUTBOUND_UNAVAILABLE,
    CORE_OUTBOUND_RESTORED,
    CORE_FLOWS_REVOKED,
    CORE_EVENT_GAP,
}

/**
 * Sealed payload of one journal record. Flat on purpose: every field is optional
 * except the type, so one schema carries package events, tamper events and
 * lifecycle markers without a serializer hierarchy.
 */
@Serializable
data class GuardEvent(
    val type: GuardEventType,
    val packageName: String? = null,
    val installer: String? = null,
    val uid: Int? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val signerSha256: String? = null,
    val firstInstallTime: Long? = null,
    val riskLevel: String? = null,
    val riskSignals: List<String> = emptyList(),
    val service: String? = null,
    val detail: String? = null,
    val gapMs: Long? = null,
    val deltaMs: Long? = null,
    val attempt: Int? = null,
)

/**
 * Plaintext envelope of one JSONL line. The payload is crypto_box_seal'ed to the
 * guard public key (writable while locked, readable only with the password); the
 * envelope itself stays readable so the chain can be walked without unlocking.
 * prevHash = SHA-256 hex of the previous line's exact bytes; seq is strictly
 * sequential; the timestamp triple (wallClock, elapsedRealtime, bootCount) makes
 * clock rollbacks and reboots visible to the verifier.
 */
@Serializable
data class GuardJournalRecord(
    val seq: Long,
    val prevHash: String,
    val wallClock: Long,
    val elapsedRealtime: Long,
    val bootCount: Int,
    val sealed: String,
)

/** Envelope + decrypted payload, as surfaced to the verified journal UI. */
data class GuardJournalEntry(
    val record: GuardJournalRecord,
    val event: GuardEvent?,
)
