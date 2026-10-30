package com.foxhole.guard.core.settings

import com.foxhole.core.model.UiSettings

internal const val LEGACY_UPDATE_NOTICE_CONTENT_ID = "foxhole.alpha-notice.v0"
private val UPDATE_NOTICE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._:-]{0,127}$")

internal fun UiSettings.normalizedUpdateNoticeAcknowledgement(): UiSettings {
    val id = updateNoticeAcknowledgedContentId.trim()
    val valid = id.matches(UPDATE_NOTICE_ID_PATTERN) && updateNoticeAcknowledgedRevision > 0L
    val legacy = !valid && alphaNoticeShownVersionCode > 0
    return copy(
        updateNoticeAcknowledgedContentId = when {
            valid -> id
            legacy -> LEGACY_UPDATE_NOTICE_CONTENT_ID
            else -> ""
        },
        updateNoticeAcknowledgedRevision = when {
            valid -> updateNoticeAcknowledgedRevision
            legacy -> 1L
            else -> 0L
        },
    )
}
