package com.foxhole.guard.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WebAppPreviewCancellationTest {
    @Test
    fun `cancelled page fetch never falls back to fetching icons`() {
        var iconCalls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                fetchWebAppPreview(
                    rawUrl = "https://example.com",
                    fetchText = { _, _ -> throw CancellationException("preview dismissed") },
                    fetchBytes = { _, _ ->
                        iconCalls += 1
                        null
                    },
                )
            }
        }
        assertEquals(0, iconCalls)
    }

    @Test
    fun `cancelled icon fetch never tries another icon candidate`() {
        var iconCalls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                fetchWebAppPreview(
                    rawUrl = "https://example.com",
                    fetchText = { _, _ -> "<link rel=\"icon\" href=\"/first.png\"><link rel=\"icon\" href=\"/second.png\">" },
                    fetchBytes = { _, _ ->
                        iconCalls += 1
                        throw CancellationException("preview dismissed")
                    },
                )
            }
        }
        assertEquals(1, iconCalls)
    }

    @Test
    fun `unavailable page still permits the existing favicon fallback`() =
        runBlocking {
            val icon = byteArrayOf(1, 2, 3)
            var iconCalls = 0
            val preview = fetchWebAppPreview(
                rawUrl = "https://example.com",
                fetchText = { _, _ -> throw IOException("page temporarily unavailable") },
                fetchBytes = { _, _ ->
                    iconCalls += 1
                    icon
                },
            ).getOrThrow()

            assertEquals("example.com", preview.name)
            assertEquals(1, iconCalls)
        }
}
