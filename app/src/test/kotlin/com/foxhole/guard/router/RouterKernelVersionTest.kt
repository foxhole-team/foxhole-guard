package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterKernelVersionTest {
    @Test fun exactInstalledKernelPackageVersionIsRetained() {
        assertEquals("6.12.75~fixture-r1", parseRouterKernelVersion("kernel-6.12.75~fixture-r1\n".toByteArray()))
    }

    @Test fun missingMultipleOrUnboundedKernelOutputsAreRefused() {
        for (value in listOf("", "6.12.75", "kernel-a\nkernel-b", "kernel-a;echo unsafe", "kernel-" + "a".repeat(1024))) {
            assertThrows(RouterInspectionFailure::class.java) { parseRouterKernelVersion(value.toByteArray()) }
        }
    }
}
