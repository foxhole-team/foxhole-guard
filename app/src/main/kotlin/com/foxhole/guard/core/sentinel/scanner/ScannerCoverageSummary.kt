package com.foxhole.guard.core.sentinel.scanner

import com.foxhole.guard.core.sentinel.apk.StaticLimitation

internal fun scannerSummaryLimitations(limitations: Set<StaticLimitation>, verifiedDexYaraComplete: Boolean): Set<StaticLimitation> =
    if (verifiedDexYaraComplete) limitations - StaticLimitation.NO_YARA_ENGINE else limitations
