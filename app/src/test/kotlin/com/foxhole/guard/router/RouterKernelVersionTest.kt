package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterKernelVersionTest {
    @Test fun installedQueryReadsOnlyTheExactKernelVersion() {
        assertEquals("6.12.75~fixture-r1", parseRouterKernelVersion(
            """[{"name":"kernel","version":"6.12.75~fixture-r1"}]""".toByteArray()))
    }

    @Test fun packageQueryIsInstalledAndMachineReadable() {
        assertEquals("apk query --from installed --installed --match name --fields name,version --format json kernel",
            RouterReadCommand.KERNEL_PACKAGE.script)
    }

    @Test fun wrongAmbiguousOrUnsafeQueryRecordsAreRefused() {
        for (value in listOf("[]", "{}", """[{"name":"other","version":"6.12.75-r1"}]""",
            """[{"name":"kernel","version":"6.12.75-r1"},{"name":"kernel","version":"6.12.76-r1"}]""",
            """[{"name":"kernel","version":"a;echo unsafe"}]""", """[{"name":"kernel","version":123}]""",
            """[{"name":"kernel","version":"6.12.75-r1","unexpected":true}]""")) {
            assertThrows(RouterInspectionFailure::class.java) { parseRouterKernelVersion(value.toByteArray()) }
        }
    }

    @Test fun exactInstalledKernelPackageVersionIsRetained() {
        assertEquals("6.12.75~fixture-r1", parseRouterKernelVersion("""[{"name":"kernel","version":"6.12.75~fixture-r1"}]""".toByteArray()))
    }

    @Test fun missingMultipleOrUnboundedKernelOutputsAreRefused() {
        for (value in listOf("", "6.12.75", "kernel-a\nkernel-b", "kernel-a;echo unsafe", "kernel-" + "a".repeat(1024))) {
            assertThrows(RouterInspectionFailure::class.java) { parseRouterKernelVersion(value.toByteArray()) }
        }
    }
}
