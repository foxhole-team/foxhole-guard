package com.foxhole.guard

import android.content.Intent
import android.net.Network
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.core.sharing.FILE_SHARE_MIN_LIFETIME_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Live onion file sharing on hardware: publish a file, fetch it back over Tor, revoke it.
 *
 * Everything below the Kotlin controller is a Rust component nothing on device has ever
 * exercised — the encrypted vault, the loopback server, Arti's onion service and the
 * invitation writer. The unit suites cover their pieces in isolation; what has never been
 * shown is that the four of them, on a phone, produce an address a stranger can actually
 * download from.
 *
 * **The assertion is the payload coming back, byte for byte, and then not coming back.**
 * A publication that returns an address is worth nothing on its own: the descriptor may never
 * publish, the loopback server may serve a truncated body, the capability may not authorise.
 * So the test re-downloads its own file through the Tor route and compares the bytes it wrote
 * — and after `revoke` requires the same URL to stop answering, because a share that stays
 * reachable after the user withdraws it is the failure that matters most and the one the UI
 * cannot show.
 *
 * The invitation is read the way a recipient gets it: through the production share Intent and
 * its FileProvider URI, not from a private path. Its URL shape is asserted too — `.onion` and
 * nothing else — because "publish only through Tor" is a contract that a clearnet fallback
 * would silently break.
 *
 * Web apps are switched on deliberately: `torOnlyTunInbound` leaves this app's own package
 * outside the Tor-only TUN unless they are, and an excluded test process could not resolve or
 * reach a `.onion` name at all.
 *
 * **What this does not prove:** that a device other than this one can reach the address. The
 * download leg runs on the same phone that publishes, so it shares the Tor client, and a
 * defect that only affects foreign clients (a descriptor published to the wrong HSDirs, say)
 * would not be caught here.
 *
 * Manual gate: `-Pandroid.testInstrumentationRunnerArguments.foxhole.liveOnionShare=1`.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveOnionFileShareAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualOnionShareRoundTripsTheFileAndDiesOnRevoke() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveOnionShare") != "1") {
            Log.d(TEST_TAG, "live onion file share skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live onion share gate", false)
                return@runBlocking
            }
            val settings = app.container.settingsRepository
            val previous = settings.current()
            val source = File(app.cacheDir, "live-onion-share-source.bin")
            var publishedId: String? = null
            try {
                resetRelevantSettings(app)
                baselineRuntimeSettings(app)
                settings.updateWebAppsEnabled(true)
                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                // Tor WITHOUT a VPN profile is the bypass-the-tunnel shape: with bypass off the
                // app reads the configuration as "VPN + TOR", demands a profile it does not have,
                // and ends the session with an error. Measured on the bench Pixel:
                // `session ended … reason=VPN + TOR needs a VPN profile`.
                settings.updatePrivacyRouteBypassVpnTunnel(true)

                app.container.connectionController.connectTorOnly()
                val state = waitForTerminalState(app)
                assertEquals("the Tor-only runtime did not connect: $state", ConnectionState.CONNECTED, state)
                assertTrue(
                    "the connected runtime does not claim a Tor route, so publishing would be refused",
                    app.container.connectionController.snapshot.value.torActive,
                )
                assertTrue(
                    "the native engine handle is missing, so the share vault cannot be opened",
                    app.container.runtimeInstanceStore.nativeSnapshot().hasEngineHandle,
                )
                val vpnNetwork =
                    app.container.connectionController.currentVpnNetwork()
                        ?: error("the Tor-only runtime published no VPN network")

                val payload = ByteArray(PAYLOAD_BYTES).also(SecureRandom()::nextBytes)
                withContext(Dispatchers.IO) { source.writeBytes(payload) }

                val item =
                    app.container.fileShareController.publish(
                        uri = Uri.fromFile(source),
                        lifetimeMs = FILE_SHARE_MIN_LIFETIME_MS,
                        maxDownloads = MAX_DOWNLOADS,
                        password = null,
                    )
                publishedId = item.id
                Log.d(TEST_TAG, "liveOnionShare published id=${item.id} bytes=${item.sizeBytes}")
                assertEquals("the published share reports a different size than the file", payload.size.toLong(), item.sizeBytes)
                assertEquals("the published share lost its download limit", MAX_DOWNLOADS, item.maxDownloads)
                assertFalse("the share was marked password-protected without a password", item.passwordProtected)
                assertTrue(
                    "the published share is missing from the controller state",
                    app.container.fileShareController.state.value.active.any { active -> active.id == item.id },
                )

                val invitation = readInvitation(app, item.id)
                Log.d(TEST_TAG, "liveOnionShare invitation lines=${invitation.lineSequence().count()}")
                assertTrue(
                    "the invitation is not a FOXHOLE-SHARE document: ${invitation.take(40)}",
                    invitation.startsWith("FOXHOLE-SHARE/"),
                )
                val url = invitation.invitationField("url")
                val capability = invitation.invitationField("username")
                // The share password channel: with no password set the recipient sends an empty
                // Basic password, which is what the server distinguishes from "no password".
                assertEquals(
                    "an unprotected share asked the recipient for a separate-channel password",
                    "leave-empty",
                    invitation.invitationField("password"),
                )
                Log.d(TEST_TAG, "liveOnionShare url=$url")
                assertTrue(
                    "the invitation URL is not an onion-only locator: $url",
                    ONION_INVITATION_URL.matches(url),
                )
                assertTrue(
                    "the download capability is not a 32-byte hex token",
                    CAPABILITY_TOKEN.matches(capability),
                )

                val budgetMs = longArgument("foxhole.onionFetchBudgetMs", ONION_FETCH_BUDGET_MS)
                val downloaded = downloadOverOnion(vpnNetwork, url, capability, budgetMs)
                assertTrue(
                    "the published onion service never served the file within ${budgetMs}ms",
                    downloaded != null,
                )
                val response = downloaded!!
                Log.d(
                    TEST_TAG,
                    "liveOnionShare downloaded bytes=${response.body.size} sha256=${response.body.sha256Hex()} " +
                        "expected=${payload.sha256Hex()}",
                )
                assertEquals(
                    "the onion download returned a different length than was published",
                    payload.size,
                    response.body.size,
                )
                assertArrayEquals("the onion download returned different bytes than were published", payload, response.body)

                // Revocation, and the only proof of it that matters: the address stops serving.
                assertTrue("revoking the share reported failure", app.container.fileShareController.revoke(item.id))
                publishedId = null
                assertTrue(
                    "the revoked share is still listed as active",
                    app.container.fileShareController.state.value.active.none { active -> active.id == item.id },
                )
                delay(REVOKE_SETTLE_MS)
                val afterRevoke =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            overlayHttpGet(
                                url = url,
                                timeoutMs = REVOKED_FETCH_TIMEOUT_MS,
                                network = vpnNetwork,
                                basicAuth = capability to "",
                            )
                        }
                    }
                val afterRevokeStatus = afterRevoke.getOrNull()?.statusCode
                Log.d(
                    TEST_TAG,
                    "liveOnionShare afterRevoke status=$afterRevokeStatus " +
                        "error=${afterRevoke.exceptionOrNull()?.javaClass?.simpleName}",
                )
                assertTrue(
                    "the revoked share still served a successful response (status=$afterRevokeStatus)",
                    afterRevokeStatus == null || afterRevokeStatus != HTTP_OK,
                )
            } finally {
                publishedId?.let { id -> runCatching { app.container.fileShareController.revoke(id) } }
                app.container.connectionController.disconnectTorOnly()
                waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
                    app.container.connectionController.snapshot.value.state == ConnectionState.IDLE
                }
                withContext(Dispatchers.IO) { source.delete() }
                restoreSettings(settings, previous)
            }
        }
    }

    /**
     * Retried rather than fetched once: `publishShare` returns as soon as Arti has an identity
     * key, which is well before the descriptor reaches the hidden-service directories. A single
     * early failure would be a clock reading, not a defect.
     */
    private suspend fun downloadOverOnion(
        network: Network,
        url: String,
        capability: String,
        budgetMs: Long,
    ): OverlayHttpResponse? {
        val deadline = System.currentTimeMillis() + budgetMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        overlayHttpGet(
                            url = url,
                            timeoutMs = ONION_FETCH_TIMEOUT_MS,
                            network = network,
                            basicAuth = capability to "",
                        )
                    }
                }
            result.getOrNull()?.let { response ->
                Log.d(
                    TEST_TAG,
                    "liveOnionShare fetch attempt=$attempt status=${response.statusCode} bytes=${response.body.size}",
                )
                if (response.statusCode == HTTP_OK) {
                    return response
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.d(
                    TEST_TAG,
                    "liveOnionShare fetch attempt=$attempt error=${error.javaClass.simpleName}:" +
                        error.message.orEmpty().take(120),
                )
            }
            delay(ONION_RETRY_DELAY_MS)
        }
        return null
    }

    /** The invitation exactly as a recipient receives it: through the share Intent's FileProvider URI. */
    private suspend fun readInvitation(
        app: FoxholeApplication,
        id: String,
    ): String {
        val intent =
            app.container.fileShareController.invitationIntent(id)
                ?: error("the controller produced no invitation intent for $id")
        @Suppress("DEPRECATION")
        val uri =
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: error("the invitation intent carries no stream")
        return withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        } ?: error("the invitation file could not be read through the FileProvider")
    }

    private fun String.invitationField(key: String): String =
        lineSequence()
            .firstOrNull { line -> line.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            ?: error("the invitation has no $key field")

    private companion object {
        const val PAYLOAD_BYTES = 96 * 1024
        const val MAX_DOWNLOADS = 3
        const val ONION_FETCH_BUDGET_MS = 300_000L
        const val ONION_FETCH_TIMEOUT_MS = 60_000
        const val ONION_RETRY_DELAY_MS = 10_000L
        const val REVOKE_SETTLE_MS = 3_000L
        const val REVOKED_FETCH_TIMEOUT_MS = 30_000
        const val RUNTIME_STOP_TIMEOUT_MS = 30_000L

        /** v3 onion, no scheme other than plain HTTP behind it, and two hex locators. */
        val ONION_INVITATION_URL = Regex("""^http://[a-z2-7]{56}\.onion(?::\d{1,5})?/[0-9a-f]+/[0-9a-f]+$""")
        val CAPABILITY_TOKEN = Regex("""^[0-9a-f]{64}$""")
    }
}
