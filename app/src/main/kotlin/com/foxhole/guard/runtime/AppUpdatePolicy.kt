package com.foxhole.guard.runtime

object AppUpdatePolicy {
    const val GITHUB_CHANNEL = "github"
    const val FDROID_CHANNEL = "fdroid"

    fun selfUpdateAllowed(channel: String): Boolean = channel == GITHUB_CHANNEL

    fun backgroundCheckAllowed(
        channel: String,
        componentUpdateCheckEnabled: Boolean,
    ): Boolean = selfUpdateAllowed(channel) && componentUpdateCheckEnabled

    fun updateNotificationRequired(
        availableVersionCode: Long,
        installedVersionCode: Long,
        lastNotifiedVersionCode: Long,
    ): Boolean =
        availableVersionCode > installedVersionCode &&
            availableVersionCode > lastNotifiedVersionCode

    fun updateNotificationRequired(
        severity: AppUpdateSeverity,
        availableVersionName: String,
        lastNotifiedVersionName: String,
    ): Boolean {
        if (!severity.notifies) {
            return false
        }
        val available = AppUpdateVersion.parseOrNull(availableVersionName) ?: return false
        val announced = AppUpdateVersion.parseOrNull(lastNotifiedVersionName) ?: return true
        return available > announced
    }

    fun fdroidUpdateNoticeRequired(
        channel: String,
        installedVersionCode: Long,
        floorVersionCode: Long,
        supportedUntilEpochDay: Long,
        todayEpochDay: Long,
    ): Boolean {
        if (channel != FDROID_CHANNEL) {
            return false
        }
        val superseded = floorVersionCode > 0L && installedVersionCode < floorVersionCode
        val outOfSupport = supportedUntilEpochDay > 0L && todayEpochDay > supportedUntilEpochDay
        return superseded || outOfSupport
    }
}
