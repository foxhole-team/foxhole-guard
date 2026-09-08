package com.foxhole.guard.core.settings

import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.UiSettings

suspend fun SettingsRepository.updateAccentColor(value: AccentColor) =
    update { it.copy(ui = it.ui.copy(accentColor = value)) }

suspend fun SettingsRepository.updatePixelArtEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(pixelArtEnabled = value)) }

suspend fun SettingsRepository.updateShowHomeAdditionalInfo(value: Boolean) =
    update { it.copy(ui = it.ui.copy(showHomeAdditionalInfo = value)) }

suspend fun SettingsRepository.updateHomeAdditionalInfoCategory(value: HomeAdditionalInfoCategory) =
    update { it.copy(ui = it.ui.copy(homeAdditionalInfoCategory = value)) }

suspend fun SettingsRepository.updateMonochromeEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(monochromeEnabled = value)) }

internal fun UiSettings.withUpdateNoticeChoices(monochromeEnabled: Boolean, shownVersionCode: Int): UiSettings =
    copy(monochromeEnabled = monochromeEnabled, alphaNoticeShownVersionCode = shownVersionCode)

suspend fun SettingsRepository.applyUpdateNoticeChoice(monochromeEnabled: Boolean, shownVersionCode: Int) = update {
    it.copy(ui = it.ui.withUpdateNoticeChoices(monochromeEnabled, shownVersionCode))
}

suspend fun SettingsRepository.updateSuppressProfileSwipeReconnectConfirm(value: Boolean) =
    update { it.copy(ui = it.ui.copy(suppressProfileSwipeReconnectConfirm = value)) }

suspend fun SettingsRepository.updateSuppressTrafficClearConfirm(value: Boolean) =
    update { it.copy(ui = it.ui.copy(suppressTrafficClearConfirm = value)) }

suspend fun SettingsRepository.updateSuppressFirewallEnableWarning(value: Boolean) =
    update { it.copy(ui = it.ui.copy(suppressFirewallEnableWarning = value)) }

suspend fun SettingsRepository.updateBlurEffectsEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(blurEffectsEnabled = value)) }

suspend fun SettingsRepository.updateMonochromeTorTheme(value: Boolean) =
    update { it.copy(ui = it.ui.copy(monochromeTorTheme = value)) }

suspend fun SettingsRepository.updateMapWidgetMapOnRight(value: Boolean) =
    update { it.copy(ui = it.ui.copy(mapWidgetMapOnRight = value)) }

suspend fun SettingsRepository.updateInterfaceSettingsExpanded(value: Boolean) =
    update { it.copy(ui = it.ui.copy(interfaceSettingsExpanded = value)) }

suspend fun SettingsRepository.updateLayoutEditingEnabled(value: Boolean) =
    update { it.copy(ui = it.ui.copy(layoutEditingEnabled = value)) }

suspend fun SettingsRepository.updateAlphaNoticeShownVersionCode(value: Int) =
    update { it.copy(ui = it.ui.copy(alphaNoticeShownVersionCode = value)) }
