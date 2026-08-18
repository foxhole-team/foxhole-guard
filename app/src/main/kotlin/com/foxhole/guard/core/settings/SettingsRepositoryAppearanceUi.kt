package com.foxhole.guard.core.settings

import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.VisualStyle

// Appearance and dialog opt-out UI settings. Extracted from SettingsRepositoryExpertUi
// (file split by domain).

suspend fun SettingsRepository.updatePanelAppearance(value: PanelAppearance) =
    update {
        val accent = if (value != PanelAppearance.AUTO && it.ui.accentColor == AccentColor.AUTO) {
            AccentColor.ORANGE
        } else {
            it.ui.accentColor
        }
        it.copy(ui = it.ui.copy(panelAppearance = value, accentColor = accent))
    }

suspend fun SettingsRepository.updateVisualStyle(value: VisualStyle) =
    update { it.copy(ui = it.ui.copy(visualStyle = value)) }

suspend fun SettingsRepository.updateAccentColor(value: AccentColor) =
    update { it.copy(ui = it.ui.copy(accentColor = value)) }

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
