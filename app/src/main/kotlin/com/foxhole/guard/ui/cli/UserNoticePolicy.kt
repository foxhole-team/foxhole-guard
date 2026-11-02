package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone

internal const val CLI_EXPERIMENTAL_TOP_NOTICES_ENABLED = true

internal fun retainUserNotice(current: FoxholeBannerEvent?, incoming: FoxholeBannerEvent): FoxholeBannerEvent? = when {
    incoming.action != null -> current
    !incoming.showInTopNotice && !incoming.tone.isCritical() -> current
    current?.tone.isCritical() && !incoming.tone.isCritical() -> current
    else -> incoming
}

internal fun userNoticeDurationMillis(notice: FoxholeBannerEvent): Long? =
    if (notice.tone.isCritical() || notice.action != null) null else NOTICE_DURATION_MILLIS

private fun FoxholeBannerTone?.isCritical(): Boolean =
    this == FoxholeBannerTone.ERROR || this == FoxholeBannerTone.WARNING
