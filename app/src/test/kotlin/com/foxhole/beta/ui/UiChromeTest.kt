package com.foxhole.beta.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiChromeTest {
    @Test
    fun `top scrim alpha ramps continuously before the minimum scrolled state`() {
        assertEquals(0f, foxholeTopScrimAlpha(0f), 0.0001f)
        assertTrue(foxholeTopScrimAlpha(0.01f) in 0f..0.64f)
        assertEquals(0.64f, foxholeTopScrimAlpha(0.36f), 0.0001f)
        assertEquals(1f, foxholeTopScrimAlpha(1f), 0.0001f)
    }

    @Test
    fun `top chrome scrim covers the status bar area while scrolled`() {
        val source =
            listOf(
                File("src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                File("app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
                File("../app/src/main/kotlin/com/foxhole/beta/ui/UiChrome.kt"),
            ).first { file -> file.isFile }.readText()
        val scrimCall =
            source.substringAfter("FoxholeTopScrimLayer(")
                .substringBefore("progress = scrimProgress")

        assertTrue(scrimCall.contains(".align(Alignment.TopCenter)"))
        assertTrue(scrimCall.contains(".height(contentTopPadding)"))
    }

    @Test
    fun `elevation roles keep shared chrome depths explicit`() {
        assertEquals(0.dp, foxholeElevation(FoxholeElevationRole.Surface))
        assertEquals(1.dp, foxholeElevation(FoxholeElevationRole.Pill))
        assertEquals(2.dp, foxholeElevation(FoxholeElevationRole.Control))
        assertEquals(3.dp, foxholeElevation(FoxholeElevationRole.Card))
        assertEquals(8.dp, foxholeElevation(FoxholeElevationRole.Menu))
        assertEquals(8.dp, foxholeElevation(FoxholeElevationRole.Banner))
        assertEquals(12.dp, foxholeBottomDockElevation())
        assertEquals(18.dp, foxholeElevation(FoxholeElevationRole.Dialog))
        assertEquals(8.dp, foxholeElevation(FoxholeElevationRole.Dragged))
    }

    @Test
    fun `show banner gives success tone a countdown duration`() =
        runBlocking {
            val hostState = SnackbarHostState()
            val job = launch {
                hostState.showBanner("Profile imported", FoxholeBannerTone.SUCCESS)
            }
            yield()
            val visuals = hostState.currentSnackbarData?.visuals as FoxholeBannerVisuals
            assertEquals("Profile imported", visuals.message)
            assertEquals(FoxholeBannerTone.SUCCESS, visuals.tone)
            assertEquals(SnackbarDuration.Indefinite, visuals.duration)
            assertEquals(FOXHOLE_BANNER_SHORT_DURATION_MS, visuals.durationMillis)
            hostState.currentSnackbarData?.dismiss()
            job.join()
        }

    @Test
    fun `show banner gives error tone a longer countdown duration`() =
        runBlocking {
            val hostState = SnackbarHostState()
            val job = launch {
                hostState.showBanner("Profile refresh failed", FoxholeBannerTone.ERROR)
            }
            yield()
            val visuals = hostState.currentSnackbarData?.visuals as FoxholeBannerVisuals
            assertEquals("Profile refresh failed", visuals.message)
            assertEquals(FoxholeBannerTone.ERROR, visuals.tone)
            assertEquals(SnackbarDuration.Indefinite, visuals.duration)
            assertEquals(FOXHOLE_BANNER_LONG_DURATION_MS, visuals.durationMillis)
            hostState.currentSnackbarData?.dismiss()
            job.join()
        }

    @Test
    fun `timed banner carries explicit countdown deadline`() =
        runBlocking {
            val hostState = SnackbarHostState()
            val expiresAtElapsedMs = 12_345L
            val job = launch {
                hostState.showBanner(
                    message = "Recommended protocol VLESS. Connect?",
                    tone = FoxholeBannerTone.INFO,
                    actionLabel = "Connect",
                    durationMillis = 8_000L,
                    expiresAtElapsedMs = expiresAtElapsedMs,
                )
            }
            yield()
            val visuals = hostState.currentSnackbarData?.visuals as FoxholeBannerVisuals
            assertEquals(SnackbarDuration.Indefinite, visuals.duration)
            assertEquals(8_000L, visuals.durationMillis)
            assertEquals(expiresAtElapsedMs, visuals.expiresAtElapsedMs)
            hostState.currentSnackbarData?.dismiss()
            job.join()
        }

    @Test
    fun `banner deadline keeps a short requested deadline visible long enough`() {
        assertEquals(
            13_000L,
            resolvedBannerExpiresAtElapsedMs(
                nowElapsedMs = 10_000L,
                durationMillis = FOXHOLE_BANNER_SHORT_DURATION_MS,
                requestedExpiresAtElapsedMs = 10_500L,
            ),
        )
    }

    @Test
    fun `banner deadline preserves a later requested deadline`() {
        assertEquals(
            18_000L,
            resolvedBannerExpiresAtElapsedMs(
                nowElapsedMs = 10_000L,
                durationMillis = FOXHOLE_BANNER_SHORT_DURATION_MS,
                requestedExpiresAtElapsedMs = 18_000L,
            ),
        )
    }

    @Test
    fun `diagnostic message parts split structured network details`() {
        assertEquals(
            DiagnosticMessageParts(
                headline = "Default network changed",
                bulletDetails = listOf("wifi", "internet", "validated"),
            ),
            diagnosticMessageParts("default network changed: wifi • internet • validated"),
        )
    }

    @Test
    fun `diagnostic message parts humanize key value state lines`() {
        assertEquals(
            DiagnosticMessageParts(
                headline = "State: connected",
                bulletDetails = listOf("Reason: manual"),
            ),
            diagnosticMessageParts("state=connected reason=manual"),
        )
    }

    @Test
    fun `diagnostic message parts humanize app activity details`() {
        assertEquals(
            DiagnosticMessageParts(
                headline = "App connection",
                bulletDetails =
                    listOf(
                        "App: Chrome",
                        "Packages: com.android.chrome",
                        "UID: 10234",
                        "Protocol: TCP",
                        "Remote: 1.1.1.1:443",
                    ),
            ),
            diagnosticMessageParts(
                "App connection: app=Chrome • packages=com.android.chrome • uid=10234 • protocol=TCP • remote=1.1.1.1:443",
            ),
        )
    }
}
