package com.foxhole.guard.core.sentinel.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolatedScannerProcessPolicyTest {
    @Test fun ordinaryAndApplicationZygoteProcessesCannotLoadScannerEngine() {
        listOf(-1, 0, 1000, 10000, 19999, 90000, 98999, 100000, 1011630).forEach {
            assertFalse(isStandardIsolatedScannerUid(it))
        }
    }

    @Test fun isolatedRangeIsRecognizedAcrossAndroidUsers() {
        listOf(99000, 99999, 199000, 199999, 1099000, 1099999).forEach {
            assertTrue(isStandardIsolatedScannerUid(it))
        }
    }

    @Test fun installationRuleAndRequestIdentityAllFencePublication() {
        val owner = ScannerRequestOwner("owned-request", "org.owned:1", "rules:1")
        val lease = ScannerPublicationLease(owner)
        assertTrue(lease.mayPublish(owner, owner))
        assertFalse(lease.mayPublish(owner.copy(requestId = "later-request"), owner))
        assertFalse(lease.mayPublish(owner, owner.copy(installationId = "org.owned:2")))
        assertFalse(lease.mayPublish(owner, owner.copy(ruleEpoch = "rules:2")))
        lease.cancel()
        assertFalse(lease.mayPublish(owner, owner))
    }

    @Test fun opaqueOwnerFieldsRemainBoundedAndDoNotAcceptPaths() {
        assertThrows(IllegalArgumentException::class.java) { ScannerRequestOwner("x".repeat(65), "owned:1", "rules:1") }
        assertThrows(IllegalArgumentException::class.java) { ScannerRequestOwner("owned", "../../secret", "rules:1") }
    }
}
