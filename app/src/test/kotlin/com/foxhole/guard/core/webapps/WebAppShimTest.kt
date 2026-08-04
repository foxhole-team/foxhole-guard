package com.foxhole.guard.core.webapps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сведение сигналов вотчдога в бейдж: значение шима (Notification/setAppBadge) приоритетно,
 * `(N)`-эвристика заголовка — запасная, а полное молчание сайта сохраняет прежний бейдж
 * (сайт мог просто не догрузиться — сбрасывать непрочитанное нельзя).
 */
internal class WebAppShimTest {
    @Test
    fun `shim signal wins over the title heuristic`() {
        assertEquals(4, computeBadge(shimCount = 4, title = "(2) inbox", previous = 0))
    }

    @Test
    fun `title heuristic applies when the shim is silent`() {
        assertEquals(2, computeBadge(shimCount = null, title = "(2) inbox", previous = 0))
    }

    @Test
    fun `no signals keep the previous badge`() {
        assertEquals(3, computeBadge(shimCount = null, title = "quiet page", previous = 3))
    }

    @Test
    fun `explicit shim zero clears the badge`() {
        assertEquals(0, computeBadge(shimCount = 0, title = "(5) inbox", previous = 5))
    }

    @Test
    fun `shim value is clamped to a sane range`() {
        assertEquals(9999, computeBadge(shimCount = 1_000_000, title = null, previous = 0))
        assertEquals(0, computeBadge(shimCount = -7, title = null, previous = 2))
    }
}
