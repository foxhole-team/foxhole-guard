package com.foxhole.guard.ui.cli.onboarding

import com.foxhole.core.model.UiSettings
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.withUpdateNoticeChoices
import com.foxhole.guard.ui.CURRENT_UPDATE_NOTICE
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUpdateNoticeContractTest {
    @Test fun fiveSummaryLinesRetainTheFullOwnerApprovedRouterText() {
        listOf("values", "values-ru").forEach { qualifier ->
            val strings = releaseNoteCopy(qualifier)
            val texts = listOf("router_setup", "app_updates", "notices", "help", "router_management", "pin6").map { topic ->
                strings.getValue("cli_release_notes_$topic")
            }
            val items = updateNoticeItems(texts.joinToString("\n"))
            assertEquals(texts, items.map { it.text })
        }
    }

    @Test fun currentReleasePrependsOnePointAndRetainsPublishedNotes() {
        val expected = listOf(
            R.string.cli_release_notes_translations_105,
            R.string.cli_release_notes_core_104,
            R.string.cli_release_notes_atomic_104,
            R.string.cli_release_notes_help,
        )
        listOf("values", "values-ru", "values-de", "values-fr", "values-es", "values-ja", "values-zh-rCN", "values-ar").forEach { qualifier ->
            val copy = releaseNoteCopy(qualifier)
            listOf("cli_release_notes_translations_105", "cli_release_notes_core_104", "cli_release_notes_atomic_104", "cli_release_notes_help").forEach { key ->
                assertTrue("Missing current release copy in $qualifier/$key", copy.getValue(key).isNotBlank())
            }
        }
        listOf(26, 28, 29, 33).forEach { apiLevel ->
            assertEquals(expected, releaseNoteKeys(apiLevel, "fdroid"))
            assertEquals(expected + R.string.app_update_available_release_note, releaseNoteKeys(apiLevel, "github"))
            assertFalse(releaseNoteKeys(apiLevel, "fdroid").contains(R.string.app_update_available_release_note))
        }
    }

    @Test fun noticeOnlyChangesMonochromeAndAcknowledgement() {
        val original = UiSettings(pixelArtEnabled = false, showHomeAdditionalInfo = true)
        val changed = original.withUpdateNoticeChoices(
            true,
            CURRENT_UPDATE_NOTICE.contentId,
            CURRENT_UPDATE_NOTICE.revision
        )
        assertEquals(
            original.copy(
                monochromeEnabled = true,
                updateNoticeAcknowledgedContentId = CURRENT_UPDATE_NOTICE.contentId,
                updateNoticeAcknowledgedRevision = CURRENT_UPDATE_NOTICE.revision
            ),
            changed
        )
    }

    @Test fun legacySerializedSettingsKeepChoicesAndGetSafeOptionalMarkerDefaults() {
        val decoded = Json.decodeFromString(
            UiSettings.serializer(),
            """{"alphaNoticeShownVersionCode":42,"monochromeEnabled":true,"pixelArtEnabled":false}"""
        )
        assertEquals(42, decoded.alphaNoticeShownVersionCode)
        assertTrue(decoded.monochromeEnabled)
        assertFalse(decoded.pixelArtEnabled)
        assertEquals("", decoded.updateNoticeAcknowledgedContentId)
        assertEquals(0L, decoded.updateNoticeAcknowledgedRevision)
    }

    @Test fun newMarkerRoundTripsInSerializedUiWithoutTouchingPreferences() {
        val ui = UiSettings(monochromeEnabled = true).withUpdateNoticeChoices(
            true,
            CURRENT_UPDATE_NOTICE.contentId,
            CURRENT_UPDATE_NOTICE.revision
        )
        assertEquals(
            ui,
            Json.decodeFromString(UiSettings.serializer(), Json.encodeToString(UiSettings.serializer(), ui))
        )
    }
}
