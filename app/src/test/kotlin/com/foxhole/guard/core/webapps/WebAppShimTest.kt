package com.foxhole.guard.core.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `badge growth notifies when no frame is open`() {
        assertTrue(shouldNotifyBadgeIncrease(previous = 1, updated = 2, appId = 7L, foregroundAppId = null))
    }

    @Test
    fun `badge growth stays silent while that app's frame is open`() {
        assertFalse(shouldNotifyBadgeIncrease(previous = 1, updated = 2, appId = 7L, foregroundAppId = 7L))
    }

    @Test
    fun `badge growth notifies when a different app's frame is open`() {
        assertTrue(shouldNotifyBadgeIncrease(previous = 1, updated = 2, appId = 7L, foregroundAppId = 3L))
    }

    @Test
    fun `flat or shrinking badge never notifies`() {
        assertFalse(shouldNotifyBadgeIncrease(previous = 2, updated = 2, appId = 7L, foregroundAppId = null))
        assertFalse(shouldNotifyBadgeIncrease(previous = 2, updated = 0, appId = 7L, foregroundAppId = null))
    }

    @Test
    fun `notification count starts from the stored badge`() {
        val script = webAppShimJs(initialBadge = 7)

        assertTrue(script.contains("var count = 7;"))
        assertFalse(script.contains("__FHG_INITIAL_BADGE__"))
    }

    @Test
    fun `shim carries sanitized notification content`() {
        val signal = parseShimSignal("""{"badge":8,"title":" Telegram ","body":"one\n two"}""")

        assertEquals(8, signal?.badge)
        assertEquals(WebAppNotificationContent("Telegram", "one two"), signal?.notification)
    }

    @Test
    fun `junk shim messages stay ignored`() {
        assertEquals(null, parseShimSignal("not json"))
        assertEquals(null, parseShimSignal("{}"))
    }

    @Test
    fun `telegram receives a longer post-load window`() {
        assertEquals(TELEGRAM_POST_LOAD_GRACE_MS, webAppPostLoadGraceMs("https://web.telegram.org/k/"))
        assertEquals(DEFAULT_POST_LOAD_GRACE_MS, webAppPostLoadGraceMs("https://example.org/inbox"))
        assertEquals(DEFAULT_POST_LOAD_GRACE_MS, webAppPostLoadGraceMs("https://web.telegram.org.evil.test/"))
    }
}
