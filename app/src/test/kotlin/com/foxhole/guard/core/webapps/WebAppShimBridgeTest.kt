package com.foxhole.guard.core.webapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppShimBridgeTest {

    private val script = webAppShimJs(initialBadge = 0)

    @Test
    fun `no shim failure is swallowed`() {
        assertFalse(script.contains("catch (e) {}"))
    }

    @Test
    fun `signals are queued and flushed instead of dropped`() {
        assertTrue(script.contains("queue.push"))
        assertTrue(script.contains("function flush()"))
        assertTrue(script.contains("scheduleFlush"))
        assertTrue(script.contains("DOMContentLoaded"))
    }

    @Test
    fun `the queue is bounded so a page without a bridge cannot grow it forever`() {
        assertTrue(script.contains("queue.length >= 32"))
        assertTrue(script.contains("flushAttempts >= 20"))
    }

    @Test
    fun `a replaced notification does not inflate the badge and a closed one releases it`() {
        assertTrue(script.contains("function bump(tag)"))
        assertTrue(script.contains("if (key !== null && tags[key]) { return; }"))
        assertTrue(script.contains("count = Math.max(0, count - 1);"))
    }

    @Test
    fun `setAppBadge without a count keeps the badge instead of clearing it`() {
        assertTrue(script.contains("count = Math.max(count, 1);"))
    }

    @Test
    fun `an override that fails to install reports itself`() {
        assertTrue(script.contains("report('notification_override'"))
        assertTrue(script.contains("report('badge_override'"))
        assertTrue(script.contains("report('service_worker_override'"))
    }

    @Test
    fun `install failures reach the watchdog as a diagnostic`() {
        val signal = parseShimSignal("""{"error":"notification_override: denied"}""")

        assertEquals("notification_override: denied", signal?.installError)
        assertNull(signal?.badge)
        assertNull(signal?.notification)
    }

    @Test
    fun `a badge signal carries no install error`() {
        assertNull(parseShimSignal("""{"badge":3}""")?.installError)
    }

    @Test
    fun `the frame installs the API surface without a bridge to retry against`() {
        val frameScript = webAppShimJs(initialBadge = 0, bridged = false)

        assertTrue(frameScript.contains("var bridged = false;"))
        assertTrue(frameScript.contains("if (!bridged) { return; }"))
        assertTrue(frameScript.contains("Object.defineProperty(window, 'Notification'"))
        assertFalse(frameScript.contains("__FHG_BRIDGED__"))
    }
}
