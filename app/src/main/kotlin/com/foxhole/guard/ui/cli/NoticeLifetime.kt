package com.foxhole.guard.ui.cli

internal const val NOTICE_DURATION_MILLIS = 15_000L

internal data class NoticeLifetime(
    val startedAtElapsedMs: Long,
    val durationMillis: Long = NOTICE_DURATION_MILLIS,
) {
    init {
        require(startedAtElapsedMs >= 0L && durationMillis in 1L..Int.MAX_VALUE.toLong())
    }

    fun remainingMillis(nowElapsedMs: Long): Long =
        if (nowElapsedMs < startedAtElapsedMs) 0L
        else (durationMillis - (nowElapsedMs - startedAtElapsedMs)).coerceAtLeast(0L)

    fun remainingFraction(nowElapsedMs: Long): Float =
        (remainingMillis(nowElapsedMs).toFloat() / durationMillis).coerceIn(0f, 1f)
}

internal fun shouldDismissNoticeUp(offsetY: Float, height: Float, maxDistance: Float): Boolean =
    height > 0f && offsetY <= -minOf(height * 0.4f, maxDistance)
