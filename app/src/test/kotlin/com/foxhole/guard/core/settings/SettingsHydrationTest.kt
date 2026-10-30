package com.foxhole.guard.core.settings

import com.foxhole.core.model.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SettingsHydrationTest {
    @Test
    fun `cancelled first hydration can be retried by a later caller`() =
        runBlocking {
            val hydration = SettingsHydration()
            val entered = CompletableDeferred<Unit>()
            val first = launch {
                hydration.load {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            first.cancelAndJoin()

            val expected = Settings()
            var retryCalls = 0
            val actual = hydration.load {
                retryCalls += 1
                expected
            }

            assertSame(expected, actual)
            assertEquals(1, retryCalls)
            assertSame(expected, hydration.cached())
        }

    @Test
    fun `successful hydration is reused without another storage read`() =
        runBlocking {
            val hydration = SettingsHydration()
            val expected = Settings()
            assertSame(expected, hydration.load { expected })
            assertSame(expected, hydration.load { error("must not read twice") })
        }

    @Test
    fun `storage failure stays fail closed until an explicit checkpoint replaces it`() {
        val hydration = SettingsHydration()
        val failure = IOException("encrypted settings unavailable")
        assertSame(
            failure,
            assertThrows(IOException::class.java) {
                runBlocking { hydration.load { throw failure } }
            }
        )
        assertSame(
            failure,
            assertThrows(IOException::class.java) {
                runBlocking { hydration.load { error("must not retry an unresolved storage failure") } }
            }
        )
        val restored = Settings()
        hydration.replace(restored)
        assertSame(restored, runBlocking { hydration.load { error("checkpoint already loaded") } })
    }
}
