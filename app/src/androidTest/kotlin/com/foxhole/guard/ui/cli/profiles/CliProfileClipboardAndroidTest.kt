package com.foxhole.guard.ui.cli.profiles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.importer.ProfileImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CliProfileClipboardAndroidTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun copyingProfilesWritesAllConfigsAndKeepsTheSensitiveClipboardMarker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { Text("Owned clipboard fixture") }
        compose.waitForIdle()
        val clipboard = withContext(Dispatchers.Main.immediate) {
            requireNotNull(context.getSystemService(ClipboardManager::class.java))
        }
        val previous = withContext(Dispatchers.Main.immediate) { clipboard.primaryClip }
        try {
            val parser = ProfileImportParser(Json)
            val first = "vless://00000000-0000-4000-8000-000000000001@1.1.1.1:443?security=tls&sni=example.com"
            val second = "trojan://owned-fixture@8.8.8.8:443?security=tls&sni=example.com"
            val configs = listOf(first, second).map {
                requireNotNull(parser.parseUserInput(it, allowPrivateOutboundHosts = true).normalizedConfigJson)
            }
            val lookupsOnMain = mutableListOf<Boolean>()
            val recording = object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    if (name == Context.CLIPBOARD_SERVICE) {
                        val onMain = Looper.myLooper() == Looper.getMainLooper()
                        lookupsOnMain += onMain
                        assertTrue("Clipboard lookup must run on Main", onMain)
                    }
                    return super.getSystemService(name)
                }
            }
            val copiedSuccessfully = withContext(Dispatchers.Default) {
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                cliCopyConfigsToClipboard(recording, listOf(export(configs)))
            }
            assertTrue(copiedSuccessfully)
            assertEquals(listOf(true), lookupsOnMain)
            val copied = withContext(Dispatchers.Main.immediate) { requireNotNull(clipboard.primaryClip) }
            val imported = parser.parseUserInput(copied.getItemAt(0).text.toString(), allowPrivateOutboundHosts = true)
            assertEquals(2, imported.protocolOptions.size)
            assertTrue(copied.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        } finally {
            withContext(Dispatchers.Main.immediate) { restoreClipboard(clipboard, previous) }
        }
    }

    @Test
    fun anEmptyExportOrMissingClipboardCannotReportSuccessOrEraseTheClipboard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { Text("Owned clipboard fixture") }
        compose.waitForIdle()
        val clipboard = withContext(Dispatchers.Main.immediate) {
            requireNotNull(context.getSystemService(ClipboardManager::class.java))
        }
        val previous = withContext(Dispatchers.Main.immediate) { clipboard.primaryClip }
        try {
            withContext(Dispatchers.Main.immediate) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Owned fixture", "Keep this value"))
            }
            assertFalse(cliCopyConfigsToClipboard(context, emptyList()))
            assertEquals(
                "Keep this value",
                withContext(Dispatchers.Main.immediate) { clipboard.primaryClip?.getItemAt(0)?.text.toString() },
            )
            var missingLookupOnMain: Boolean? = null
            val unavailable = object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    if (name != Context.CLIPBOARD_SERVICE) return super.getSystemService(name)
                    missingLookupOnMain = Looper.myLooper() == Looper.getMainLooper()
                    return null
                }
            }
            val canonical = requireNotNull(ProfileImportParser(Json).parseUserInput(
                "vless://00000000-0000-4000-8000-000000000001@1.1.1.1:443?security=tls&sni=example.com",
                allowPrivateOutboundHosts = true,
            ).normalizedConfigJson)
            assertFalse(
                withContext(Dispatchers.Default) { cliCopyConfigsToClipboard(unavailable, listOf(export(listOf(canonical)))) },
            )
            assertTrue(missingLookupOnMain == true)
            assertEquals(
                "Keep this value",
                withContext(Dispatchers.Main.immediate) { clipboard.primaryClip?.getItemAt(0)?.text.toString() },
            )
        } finally {
            withContext(Dispatchers.Main.immediate) { restoreClipboard(clipboard, previous) }
        }
    }

    @Test
    fun aLossyPolicyNeverOverwritesTheClipboardOrReportsSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { Text("Owned clipboard fixture") }
        compose.waitForIdle()
        val clipboard = withContext(Dispatchers.Main.immediate) {
            requireNotNull(context.getSystemService(ClipboardManager::class.java))
        }
        val previous = withContext(Dispatchers.Main.immediate) { clipboard.primaryClip }
        try {
            withContext(Dispatchers.Main.immediate) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Owned fixture", "Keep this value"))
            }
            val parser = ProfileImportParser(Json)
            val canonical = requireNotNull(parser.parseUserInput(
                "vless://00000000-0000-4000-8000-000000000001@1.1.1.1:443?security=tls&sni=example.com",
                allowPrivateOutboundHosts = true,
            ).normalizedConfigJson)
            val custom = canonical.replace("prefer_ipv4", "prefer_ipv6")
            assertTrue(custom != canonical)
            assertFalse(cliCopyConfigsToClipboard(context, listOf(export(listOf(custom)))))
            assertEquals(
                "Keep this value",
                withContext(Dispatchers.Main.immediate) { clipboard.primaryClip?.getItemAt(0)?.text.toString() },
            )
        } finally {
            withContext(Dispatchers.Main.immediate) { restoreClipboard(clipboard, previous) }
        }
    }

    private fun export(configs: List<String>) = CliProfileConfigExport(1L, "Owned profile", "owned-profile", configs, false)

    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        if (previous != null) clipboard.setPrimaryClip(previous) else if (Build.VERSION.SDK_INT >= 28) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
