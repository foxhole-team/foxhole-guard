package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class FoxCoreRuntimeSnapshotTest {
    @Test
    fun `unavailable outbound snapshot remains typed and rejects incomplete rows`() {
        val unavailable =
            parseNativeUnavailableOutbounds(
                """
                {
                  "generation": 9,
                  "unavailable": [
                    {
                      "id": "default",
                      "kind": "tor",
                      "reason": "internal",
                      "message": "outbound 'default' failed: transport launch failed",
                      "attempts": 2,
                      "refused": 4
                    },
                    {"id": "missing-message", "kind": "tor", "reason": "config"}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                NativeUnavailableOutbound(
                    id = "default",
                    kind = "tor",
                    reason = "internal",
                    message = "outbound 'default' failed: transport launch failed",
                    attempts = 2,
                ),
            ),
            unavailable,
        )
    }
}
