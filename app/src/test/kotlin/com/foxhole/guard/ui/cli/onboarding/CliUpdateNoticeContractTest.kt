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
    @Test fun fourSummaryLinesRetainTheFullOwnerApprovedRouterText() {
        listOf("values", "values-ru").forEach { qualifier ->
            val strings = releaseNoteCopy(qualifier)
            val texts = listOf("runtime", "sentinel_network", "backup", "core").map { topic ->
                strings.getValue("cli_release_notes_$topic")
            }
            val items = updateNoticeItems(texts.joinToString("\n"))
            assertEquals(texts, items.map { it.text })
        }
    }

    @Test fun capabilitySelectionKeepsNetworkOwnerClaimsOffOlderAndroid() {
        assertTrue(releaseNoteKeys(28).contains(R.string.cli_release_notes_sentinel_apps))
        assertFalse(releaseNoteKeys(28).contains(R.string.cli_release_notes_sentinel_network))
        assertTrue(releaseNoteKeys(29).contains(R.string.cli_release_notes_sentinel_network))
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
