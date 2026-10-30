package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RuntimeNetworkServiceOwnerTest {
    @Test
    fun networkCallbackLookupNeverCreatesCurrentRuntime() {
        var created = 0
        val store = RuntimeInstanceStore {
            created += 1
            runtime()
        }
        val owner = store.claimServiceOwner()
        assertTrue(store.isCurrentServiceOwner(owner))
        assertNull(store.currentForServiceOwner(owner))
        assertEquals(0, created)
    }

    @Test
    fun oldServiceCannotLookUpSuccessorRuntime() {
        val store = RuntimeInstanceStore { runtime() }
        val old = store.claimServiceOwner()
        val current = store.get()
        assertSame(current, store.currentForServiceOwner(old))
        val successor = store.claimServiceOwner()
        assertFalse(store.isCurrentServiceOwner(old))
        assertNull(store.currentForServiceOwner(old))
        assertSame(current, store.currentForServiceOwner(successor))
    }

    @Test
    fun teardownOwnerCannotDispatchNetworkChange() {
        val store = RuntimeInstanceStore { runtime() }
        val owner = store.claimServiceOwner()
        store.get()
        assertTrue(store.beginServiceDestroy(owner))
        assertFalse(store.isCurrentServiceOwner(owner))
        assertNull(store.currentForServiceOwner(owner))
    }

    private fun runtime(): FoxholeRuntime = Proxy.newProxyInstance(
        FoxholeRuntime::class.java.classLoader,
        arrayOf(FoxholeRuntime::class.java),
    ) { _, _, _ -> null } as FoxholeRuntime
}
