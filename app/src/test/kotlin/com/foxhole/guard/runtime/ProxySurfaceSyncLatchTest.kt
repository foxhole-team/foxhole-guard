package com.foxhole.guard.runtime

import com.foxhole.core.model.LanProxyUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxySurfaceSyncLatchTest {

    private class Surface {
        private val latch = ProxySurfaceSyncLatch()
        var coreCalls = 0
            private set

        fun pass(ask: ProxySurfaceAsk) {
            if (!latch.shouldSync(ask)) {
                return
            }
            coreCalls += 1
            latch.recordSynced(ask)
        }

        fun failingPass(ask: ProxySurfaceAsk) {
            if (!latch.shouldSync(ask)) {
                return
            }
            coreCalls += 1
        }

        fun newSession() = latch.reset()
    }

    private val off = ProxySurfaceAsk.Released()

    @Test
    fun `an all off session settles once and then never reaches the core again`() {
        val surface = Surface()

        repeat(SESSION_PASSES) { surface.pass(off) }

        assertEquals(1, surface.coreCalls)
    }

    @Test
    fun `switching a surface on is honoured on the very next pass`() {
        val surface = Surface()
        repeat(SESSION_PASSES) { surface.pass(off) }

        surface.pass(ProxySurfaceAsk.Live)

        assertEquals(2, surface.coreCalls)
    }

    @Test
    fun `a live surface reaches the core on every pass so a binding change is picked up`() {
        val surface = Surface()

        repeat(SESSION_PASSES) { surface.pass(ProxySurfaceAsk.Live) }

        assertEquals(SESSION_PASSES, surface.coreCalls)
    }

    @Test
    fun `switching a surface off releases it exactly once and then goes quiet`() {
        val surface = Surface()
        repeat(3) { surface.pass(ProxySurfaceAsk.Live) }

        repeat(SESSION_PASSES) { surface.pass(off) }

        assertEquals(4, surface.coreCalls)
    }

    @Test
    fun `a fresh session never skips its first teardown`() {
        val surface = Surface()
        repeat(SESSION_PASSES) { surface.pass(off) }
        assertEquals(1, surface.coreCalls)

        surface.newSession()
        repeat(SESSION_PASSES) { surface.pass(off) }

        assertEquals(2, surface.coreCalls)
    }

    @Test
    fun `a changed refusal is republished while an unchanged one is not`() {
        val surface = Surface()

        repeat(3) { surface.pass(ProxySurfaceAsk.Released(LanProxyUnavailableReason.NO_WIFI)) }
        repeat(3) { surface.pass(ProxySurfaceAsk.Released(LanProxyUnavailableReason.NO_CREDENTIALS)) }
        repeat(3) { surface.pass(ProxySurfaceAsk.Released(LanProxyUnavailableReason.NO_WIFI)) }

        assertEquals(3, surface.coreCalls)
    }

    @Test
    fun `a release that failed is retried instead of being latched as done`() {
        val surface = Surface()

        surface.failingPass(off)
        surface.pass(off)
        repeat(SESSION_PASSES) { surface.pass(off) }

        assertEquals(2, surface.coreCalls)
    }

    private companion object {
        const val SESSION_PASSES = 10
    }
}
