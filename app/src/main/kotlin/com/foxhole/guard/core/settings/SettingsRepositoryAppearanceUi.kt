package com.foxhole.guard.core.settings

import com.foxhole.core.model.PanelAppearance

// Appearance and dialog opt-out UI settings. Extracted from SettingsRepositoryExpertUi
// (file split by domain).

suspend fun SettingsRepository.updatePanelAppearance(value: PanelAppearance) =
    update { it.copy(ui = it.ui.copy(panelAppearance = value)) }

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
