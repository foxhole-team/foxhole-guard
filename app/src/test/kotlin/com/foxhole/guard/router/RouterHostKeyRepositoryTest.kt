package com.foxhole.guard.router

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterHostKeyRepositoryTest {
    @Test fun unknownKeyIsCapturedWithoutBeingAuthorized() {
        val repo = RouterHostKeyRepository("owned", null)
        val key = byteArrayOf(1, 2, 3)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("owned", key))
        assertArrayEquals(key, repo.observedKey())
        key[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.observedKey())
    }

    @Test fun knownIdentityMustMatchBothTargetAndKeyAndCannotBeOverwritten() {
        val key = byteArrayOf(1, 2, 3)
        val repo = RouterHostKeyRepository("owned", key)
        key[0] = 9
        assertEquals(HostKeyRepository.OK, repo.check("owned", byteArrayOf(1, 2, 3)))
        assertEquals(HostKeyRepository.CHANGED, repo.check("foreign", byteArrayOf(1, 2, 3)))
        assertEquals(HostKeyRepository.CHANGED, repo.check("owned", byteArrayOf(1, 2, 4)))
        assertThrows(UnsupportedOperationException::class.java) { repo.remove("owned", null) }
    }

    @Test fun malformedOrOversizedIdentityCannotBeCapturedAsATrustCandidate() {
        val repo = RouterHostKeyRepository("owned", null)
        assertEquals(HostKeyRepository.CHANGED, repo.check("owned", byteArrayOf()))
        assertEquals(HostKeyRepository.CHANGED, repo.check("owned", ByteArray(16385)))
        assertNull(repo.observedKey())
    }
}
