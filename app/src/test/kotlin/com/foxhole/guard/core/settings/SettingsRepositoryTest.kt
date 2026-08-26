package com.foxhole.guard.core.settings

import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.DEFAULT_DNS_FILTER_UPDATE_URL
import com.foxhole.core.model.DashboardCard
import com.foxhole.core.model.DiagnosticsRetention
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.I2pAddressBookEntry
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.SmartProfileProtocolMemory
import com.foxhole.core.model.SubscriptionRefreshInterval
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.core.model.TrafficChartPage
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.UiSettings
import com.foxhole.core.model.withTunnelSelection
import com.foxhole.guard.BuildConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SettingsRepositoryTest : SettingsRepositoryTestSupport() {
    @Test
    fun `vpn scenario changes listener and tunnel reach in one settings value`() {
        val selected =
            Settings().copy(
                expert = Settings().expert.withTunnelSelection(listOf("com.example.app")),
            )
        val proxy =
            selected.withVpnRoutingScenario(
                perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                localHttpProxyEnabled = true,
            )

        assertTrue(proxy.expert.localSurfaces.http.enabled)
        assertEquals(PerAppRoutingMode.FULL_TUNNEL, proxy.expert.perAppRoutingMode)

        val split =
            proxy.withVpnRoutingScenario(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                localHttpProxyEnabled = false,
            )

        assertFalse(split.expert.localSurfaces.http.enabled)
        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, split.expert.perAppRoutingMode)
    }

    @Test
    fun `selected-app scenario fails closed to whole device when selection is empty`() {
        val result =
            Settings().withVpnRoutingScenario(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                localHttpProxyEnabled = false,
            )

        assertEquals(PerAppRoutingMode.FULL_TUNNEL, result.expert.perAppRoutingMode)
    }

    @Test
    fun `always-block rule arms its firewall in the same settings value`() {
        val initial =
            Settings(
                expert =
                ExpertSettings(
                    appAssignments = mapOf("com.example.blocked" to AppTunnelLane.BLOCK),
                    blockedPackagesEnabled = true,
                    firewallEnabled = false,
                ),
            )

        val armed = initial.withBlockAppsAlways(true)

        assertTrue(armed.expert.firewallEnabled)
        assertTrue(armed.expert.blockAppsAlways)
        val disarmed = armed.withBlockAppsAlways(false)
        assertTrue("turning off the rule must not silently turn off the firewall", disarmed.expert.firewallEnabled)
        assertFalse(disarmed.expert.blockAppsAlways)
    }

    @Test
    fun `system locale uses empty appcompat tag and explicit locales keep their tags`() {
        assertEquals("", AppLocale.SYSTEM.appLanguageTags())
        assertEquals("en", AppLocale.EN.appLanguageTags())
        assertEquals("ru", AppLocale.RU.appLanguageTags())
    }

    @Test
    fun `safe mode setting keeps legacy storage key compatibility`() {
        val decoded = json.decodeFromString<ConnectionSettings>("""{"stealthModeEnabled":false}""")
        val encoded = json.encodeToString(ConnectionSettings(safeModeEnabled = false))

        assertFalse(decoded.safeModeEnabled)
        assertTrue(encoded.contains(""""stealthModeEnabled":false"""))
        assertFalse(encoded.contains("safeModeEnabled"))
    }

    @Test
    fun `migrates dead api ip sb endpoint to current default`() {
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("https://api.ip.sb/geoip"))
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("https://api.ip.sb/geoip/"))
    }

    @Test
    fun `migrates legacy api ipify endpoint to current default`() {
        assertEquals(
            BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
            normalizeIpInfoEndpoint("https://api.ipify.org?format=json"),
        )
        assertEquals(
            BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
            normalizeIpInfoEndpoint("https://api.ipify.org/?format=json"),
        )
    }

    @Test
    fun `keeps custom non legacy endpoint`() {
        assertEquals("https://ifconfig.co/json", normalizeIpInfoEndpoint(" https://ifconfig.co/json "))
    }

    @Test
    fun `uses default when endpoint blank`() {
        assertEquals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, normalizeIpInfoEndpoint("  "))
    }

    @Test
    fun `dns filter updates are opt in and use official manifest by default`() {
        val dns = DnsSettings()

        assertFalse(dns.autoUpdateFilters)
        assertEquals(DEFAULT_DNS_FILTER_UPDATE_URL, dns.dnsFilterUpdateUrl)
    }

    @Test
    fun `enabling the dns filter arms dns interception in the same transaction`() {
        val source = settingsRepositorySource()
        val updateBlock =
            source.substringAfter("fun SettingsRepository.updateDnsSettings")
                .substringBefore("fun SettingsRepository.updateDnsReplaceSystemDns")

        assertTrue(updateBlock.contains("value.filteringEnabled && !current.dns.filteringEnabled"))
        assertTrue(updateBlock.contains("interceptDnsRequests = true"))
    }

    @Test
    fun `tor bridges default on with auto transport, auto-update off and the signed source`() {
        val privacyRoute = PrivacyRouteSettings()

        assertTrue(privacyRoute.bridgesEnabled)
        assertEquals(TorBridgeTransport.AUTO, privacyRoute.bridgeTransport)
        assertFalse(privacyRoute.bridgesAutoUpdate)
        assertTrue(privacyRoute.bridgesUseFoxholeSource)
    }

    @Test
    fun `legacy anomaly settings decode with empty app exclusions`() {
        val decoded = json.decodeFromString<AnomalySettings>("""{"enabled":true}""")

        assertTrue(decoded.enabled)
        assertTrue(decoded.excludedPackages.isEmpty())
    }

    @Test
    fun `anomaly app exclusions survive a serialization round-trip`() {
        val settings = AnomalySettings(enabled = true, excludedPackages = listOf("com.chat", "com.video"))
        val decoded = json.decodeFromString<AnomalySettings>(json.encodeToString(settings))

        assertEquals(listOf("com.chat", "com.video"), decoded.excludedPackages)
    }

    @Test
    fun `legacy i2p settings decode with empty addressbook`() {
        val decoded = json.decodeFromString<I2pSettings>("""{"enabled":true}""")

        assertTrue(decoded.enabled)
        assertTrue(decoded.addressBook.isEmpty())
    }

    @Test
    fun `i2p addressbook entries survive a serialization round-trip`() {
        val settings =
            I2pSettings(
                enabled = true,
                addressBook = listOf(I2pAddressBookEntry(host = "example.i2p", destination = "A".repeat(516))),
            )
        val decoded = json.decodeFromString<I2pSettings>(json.encodeToString(settings))

        assertEquals(settings.addressBook, decoded.addressBook)
    }

    @Test
    fun `privacy route mode changes do not override tor quick launch visibility`() {
        val source = settingsRepositorySource()
        val updateBlock =
            source.substringAfter("fun SettingsRepository.updatePrivacyRouteMode")
                .substringBefore("fun SettingsRepository.updatePrivacyRouteScope")

        assertFalse(updateBlock.contains("showTorQuickLaunch"))
    }

    @Test
    fun `privacy route bypass preference is preserved while route is disabled`() {
        val source = settingsRepositorySource()
        val normalizedBlock =
            source.substringAfter("private fun PrivacyRouteSettings.normalized()")
                .substringBefore("private fun DnsSettings.normalized()")

        assertFalse(normalizedBlock.contains("bypassVpnTunnel = bypassVpnTunnel && enabled"))
    }

    @Test
    fun `enabling app traffic stats grants usage access consent in the same transaction`() {
        val source = settingsRepositorySource()
        val updateBlock =
            source.substringAfter("fun SettingsRepository.updateAppTrafficStatsEnabled")
                .substringBefore("fun SettingsRepository.recordInstalledAppInventory")

        assertTrue(updateBlock.contains("appTrafficUsageAccessConsent = value"))
        assertFalse(updateBlock.contains("current.appTrafficUsageAccessConsent"))
    }

    @Test
    fun `normalization never couples stored stats preference to a live permission read`() {
        val source = settingsNormalizationSource()
        val normalized = source.substringAfter("internal fun Settings.normalized()")
            .substringBefore("private fun ExpertSettings.normalized")

        assertFalse(normalized.contains("appTrafficStatsEnabled && appTrafficUsageAccessConsent"))
        assertFalse(normalized.contains("appTrafficUsageAccessConsent && appTrafficStatsEnabled"))
    }

    @Test
    fun `local proxy generated password has release entropy and rotates legacy defaults`() {
        val source = settingsRepositorySource()
        val normalizeBlock =
            source.substringAfter("internal fun normalizeLocalProxyAuthForStorage")
                .substringBefore("private fun randomLocalProxyPassword()")

        assertTrue(source.contains("PROXY_PASSWORD_RANDOM_LENGTH = 22"))
        assertTrue(source.contains("LEGACY_PROXY_PASSWORD_RANDOM_LENGTH = 4"))
        assertTrue(normalizeBlock.contains("normalizedPassword.isLegacyGeneratedLocalProxyPassword()"))
        assertFalse(source.contains("private const val PROXY_PASSWORD_RANDOM_LENGTH = 4"))
    }

    @Test
    fun `local proxy auth normalization rotates legacy password and preserves strong custom secret`() {
        val rotated =
            SettingsRepository.normalizeLocalProxyAuthForStorage(
                LocalAuthSettings(
                    username = " ",
                    password = "foxhole-ab12",
                    apiSecret = " ",
                ),
            )

        assertEquals("foxhole", rotated.username)
        assertTrue(rotated.password.startsWith("foxhole-"))
        assertEquals("foxhole-".length + 22, rotated.password.length)
        assertNotEquals("foxhole-ab12", rotated.password)
        assertEquals(40, rotated.apiSecret.length)

        val custom =
            SettingsRepository.normalizeLocalProxyAuthForStorage(
                LocalAuthSettings(
                    username = "  alice  ",
                    password = "custom-secret-with-enough-entropy",
                    apiSecret = "  api-secret  ",
                ),
            )

        assertEquals("alice", custom.username)
        assertEquals("custom-secret-with-enough-entropy", custom.password)
        assertEquals("api-secret", custom.apiSecret)
    }

    @Test
    fun `selected split apps restore include mode when routing was full tunnel`() {
        assertEquals(
            PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            selectedPackagesRoutingMode(
                currentMode = PerAppRoutingMode.FULL_TUNNEL,
                selectedPackages = listOf("com.example.app"),
            ),
        )
        assertEquals(
            PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
            selectedPackagesRoutingMode(
                currentMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                selectedPackages = listOf("com.example.app"),
            ),
        )
        assertEquals(
            PerAppRoutingMode.FULL_TUNNEL,
            selectedPackagesRoutingMode(
                currentMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                selectedPackages = emptyList(),
            ),
        )
    }

    @Test
    fun `split tunnel app routing forces tunnel traffic mode`() {
        assertEquals(
            TrafficMode.TUNNEL,
            splitTunnelTrafficMode(
                currentMode = TrafficMode.PROXY,
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            ),
        )
        assertEquals(
            TrafficMode.TUNNEL,
            splitTunnelTrafficMode(
                currentMode = TrafficMode.PROXY,
                perAppRoutingMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
            ),
        )
        assertEquals(
            TrafficMode.PROXY,
            splitTunnelTrafficMode(
                currentMode = TrafficMode.PROXY,
                perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
            ),
        )
    }

    @Test
    fun `dns filter update source accepts official repository and directory urls`() {
        assertEquals(
            DEFAULT_DNS_FILTER_UPDATE_URL,
            normalizeDnsFilterUpdateUrl("https://github.com/foxhole-team/foxhole-db.git"),
        )
        assertEquals(
            DEFAULT_DNS_FILTER_UPDATE_URL,
            normalizeDnsFilterUpdateUrl("https://github.com/foxhole-team/foxhole-db"),
        )
        assertEquals(
            DEFAULT_DNS_FILTER_UPDATE_URL,
            normalizeDnsFilterUpdateUrl("https://foxhole-team.github.io/foxhole-db"),
        )
        assertEquals(
            "https://example.org/rules/manifest.json",
            normalizeDnsFilterUpdateUrl("https://example.org/rules"),
        )
        assertEquals(
            "https://example.org/rules/manifest.json",
            normalizeDnsFilterUpdateUrl("https://example.org/rules/manifest.json/manifest.json"),
        )
        assertEquals(
            "https://example.org/rules/manifest.json",
            normalizeDnsFilterUpdateUrl("https://example.org/rules/manifest.json/manifest.json/"),
        )
    }

    @Test
    fun `stored theme parser falls back to system for unsupported values`() {
        assertEquals(ThemeMode.SYSTEM, parseStoredThemeMode("BROKEN_THEME"))
    }

    @Test
    fun `optional fast theme parser keeps blank and invalid values unset`() {
        assertNull(parseOptionalStoredThemeMode(null))
        assertNull(parseOptionalStoredThemeMode(" "))
        assertNull(parseOptionalStoredThemeMode("BROKEN_THEME"))
        assertEquals(ThemeMode.DARK, parseOptionalStoredThemeMode("DARK"))
        assertEquals(ThemeMode.OLED, parseOptionalStoredThemeMode(" oled "))
    }

    @Test
    fun `fast appearance parsers accept canonical values case insensitively`() {
        assertEquals(AccentColor.AUTO, parseFastAccentColor(" auto "))
        assertEquals(AccentColor.GREEN, parseFastAccentColor("GREEN"))
    }

    @Test
    fun `fast appearance parsers reject missing and unsupported values`() {
        assertNull(parseFastAccentColor(null))
        assertNull(parseFastAccentColor("BROKEN_ACCENT"))
    }

    @Test
    fun `fast ui snapshot retires the legacy visual style key`() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/foxhole/guard/core/settings/SettingsFastUiStore.kt"),
            java.io.File("app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsFastUiStore.kt"),
            java.io.File("../app/src/main/kotlin/com/foxhole/guard/core/settings/SettingsFastUiStore.kt"),
        ).first(java.io.File::isFile).readText()

        assertTrue(source.contains("remove(RETIRED_VISUAL_STYLE_KEY)"))
        assertTrue(source.contains("RETIRED_VISUAL_STYLE_KEY = \"visual_style\""))
        assertTrue(source.contains("putBoolean(FAST_PIXEL_ART_ENABLED_KEY, value.pixelArtEnabled)"))
        assertTrue(
            source.contains(
                "putBoolean(FAST_STATISTICS_DOCK_ICON_ENABLED_KEY, value.statisticsDockIconEnabled)",
            ),
        )
    }

    @Test
    fun `fast dashboard card order cache surfaces status on top and appends missing cards`() {
        assertEquals(
            listOf(
                DashboardCard.STATUS,
                DashboardCard.NETWORK,
                DashboardCard.PROFILES,
                DashboardCard.TRAFFIC_MAP,
                DashboardCard.ACTIONS,
                DashboardCard.TRAFFIC,
            ),
            parseFastDashboardCardOrder("NETWORK,PROFILES"),
        )
        assertNull(parseFastDashboardCardOrder("BROKEN"))
    }

    @Test
    fun `fast dashboard card order encoder normalizes duplicates`() {
        assertEquals(
            "STATUS,TRAFFIC,NETWORK,TRAFFIC_MAP,PROFILES,ACTIONS",
            encodeFastDashboardCardOrder(
                listOf(
                    DashboardCard.TRAFFIC,
                    DashboardCard.NETWORK,
                    DashboardCard.TRAFFIC,
                    DashboardCard.TRAFFIC_MAP,
                    DashboardCard.PROFILES,
                    DashboardCard.ACTIONS,
                ),
            ),
        )
    }

    @Test
    fun `fast home additional info category parser is case tolerant and fails closed`() {
        assertEquals(
            HomeAdditionalInfoCategory.MAP,
            parseFastHomeAdditionalInfoCategory(" map "),
        )
        assertEquals(
            HomeAdditionalInfoCategory.ROUTE,
            parseFastHomeAdditionalInfoCategory("ROUTE"),
        )
        assertNull(parseFastHomeAdditionalInfoCategory(null))
        assertNull(parseFastHomeAdditionalInfoCategory("BROKEN"))
    }

    @Test
    fun `support bot handle must match telegram bot pattern`() {
        assertEquals("@foxhole_support_bot", normalizeSupportBotHandle("  @foxhole_support_bot "))
        assertNull(normalizeSupportBotHandle("foxhole_support_bot"))
        assertNull(normalizeSupportBotHandle("@foxhole_support"))
        assertNull(normalizeSupportBotHandle("@bot"))
    }

    @Test
    fun `stored support bot override drops invalid and bundled default values`() {
        assertNull(storedSupportBotHandleOverride(null))
        assertNull(storedSupportBotHandleOverride("broken"))
        assertNull(storedSupportBotHandleOverride(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE))
        assertNull(storedSupportBotHandleOverride("@foxhole_app_support_bot"))
        assertEquals("@custom_support_bot", storedSupportBotHandleOverride("@custom_support_bot"))
    }

    @Test
    fun `sanitizes unsupported theme mode in stored payload`() {
        assertEquals(
            """{"ui":{"themeMode":"SYSTEM"}}""",
            sanitizeStoredThemeModePayload("""{"ui":{"themeMode":"BROKEN_THEME"}}"""),
        )
    }

    @Test
    fun `keeps the OLED theme mode in stored payload`() {
        assertEquals(
            """{"ui":{"themeMode":"OLED"}}""",
            sanitizeStoredThemeModePayload("""{"ui":{"themeMode":"OLED"}}"""),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects private ip info endpoint`() {
        normalizeIpInfoEndpoint("https://127.0.0.1/geo")
    }

    @Test
    fun `ui defaults to dark theme and hidden expert settings`() {
        assertEquals(ThemeMode.DARK, Settings().ui.themeMode)
        assertEquals(AccentColor.ORANGE, Settings().ui.accentColor)
        assertFalse(Settings().ui.showExpertSettings)
    }

    @Test
    fun `upgrading an older settings schema keeps the selected theme and accent`() {
        val normalized =
            Settings(
                schemaVersion = 15,
                ui = UiSettings(themeMode = ThemeMode.LIGHT, accentColor = AccentColor.PINK),
            ).normalized()

        assertEquals(ThemeMode.LIGHT, normalized.ui.themeMode)
        assertEquals(AccentColor.PINK, normalized.ui.accentColor)
    }

    @Test
    fun `canonical settings always store the selected theme explicitly`() {
        val darkPayload = ensureStoredThemeModePayload("{}", ThemeMode.DARK)
        val lightPayload = ensureStoredThemeModePayload(
            """{"ui":{"locale":"RU"}}""",
            ThemeMode.LIGHT,
        )

        assertEquals(ThemeMode.DARK, json.decodeFromString<Settings>(darkPayload).ui.themeMode)
        assertEquals(ThemeMode.LIGHT, json.decodeFromString<Settings>(lightPayload).ui.themeMode)
        assertEquals(AppLocale.RU, json.decodeFromString<Settings>(lightPayload).ui.locale)
        assertTrue(darkPayload.contains("\"themeMode\":\"DARK\""))
        assertTrue(lightPayload.contains("\"themeMode\":\"LIGHT\""))
    }

    @Test
    fun `missing current theme uses dark while explicit legacy appearance still migrates`() {
        val current = sanitizeStoredThemeModePayload("""{"ui":{"locale":"RU"}}""")
        val legacy = sanitizeStoredThemeModePayload("""{"ui":{"panelAppearance":"AUTO"}}""")

        assertEquals(ThemeMode.DARK, json.decodeFromString<Settings>(current).ui.themeMode)
        assertEquals(ThemeMode.SYSTEM, json.decodeFromString<Settings>(legacy).ui.themeMode)
    }

    @Test
    fun `traffic chart defaults to the common vpn page and five minute window`() {
        assertTrue(Settings().ui.trafficChartCombined)
        assertEquals(TrafficChartPage.VPN, Settings().ui.trafficChartPage)
        assertEquals(5, Settings().ui.trafficChartRangeMinutes)
    }

    @Test
    fun `expert insecure tls override defaults off`() {
        assertFalse(ExpertSettings().allowInsecureTls)
    }

    @Test
    fun `subscription auto refresh defaults on`() {
        assertTrue(Settings().connection.autoRefreshSubscriptions)
        assertEquals(SubscriptionRefreshInterval.HOURS_6, Settings().connection.subscriptionRefreshInterval)
    }

    @Test
    fun `latency probe method defaults to http`() {
        assertEquals(LatencyProbeMethod.HTTP, Settings().connection.latencyProbeMethod)
    }

    @Test
    fun `raw live diagnostics defaults off`() {
        assertFalse(ExpertSettings().rawLiveDiagnostics)
    }

    @Test
    fun `reset expert safe defaults disables insecure tls import exceptions`() {
        val reset =
            Settings(
                expert =
                ExpertSettings(
                    unlockedAt = 1234L,
                    warningAcknowledgedAt = 5678L,
                    allowInsecureTls = true,
                ),
            ).resetExpertSettingsToSafeDefaults()

        assertFalse(reset.expert.allowInsecureTls)
        assertNull(reset.expert.warningAcknowledgedAt)
        assertEquals(1234L, reset.expert.unlockedAt)
        assertTrue(reset.connection.safeModeEnabled)
    }

    @Test
    fun `experimental reset only clears experimental settings`() {
        val original =
            Settings(
                ui = UiSettings(themeMode = ThemeMode.DARK, locale = AppLocale.RU, showExpertSettings = true),
                connection = ConnectionSettings(autoReconnect = false),
                expert =
                ExpertSettings(
                    unlockedAt = 1234L,
                    warningAcknowledgedAt = 5678L,
                    blockScreenshots = true,
                    networkActivityLogging = true,
                    diagnosticsRetention = DiagnosticsRetention.DAYS_7,
                    rawLiveDiagnostics = true,
                    allowInsecureTls = true,
                    sniff = true,
                    bypassLan = true,
                ),
            )

        val reset = original.resetExperimentalSettingsToDefaults()

        assertEquals(original.ui, reset.ui)
        assertEquals(original.connection, reset.connection)
        assertEquals(1234L, reset.expert.unlockedAt)
        assertTrue(reset.expert.blockScreenshots)
        assertFalse(reset.expert.networkActivityLogging)
        assertFalse(reset.expert.rawLiveDiagnostics)
        assertFalse(reset.expert.allowInsecureTls)
        assertTrue(reset.expert.sniff)
        assertFalse(reset.expert.bypassLan)
    }

    @Test
    fun `application reset restores settings defaults without deleting profile-scoped data`() {
        val smartPreference =
            SmartProfilePreference(
                profileId = 42L,
                lastKnownGoodOptionId = "tcp",
                protocolMemories = listOf(SmartProfileProtocolMemory(optionId = "tcp", lastSuccessAt = 1_000L)),
            )
        val original =
            Settings(
                ui = UiSettings(themeMode = ThemeMode.DARK, locale = AppLocale.RU, showExpertSettings = true),
                connection =
                ConnectionSettings(
                    autoReconnect = false,
                    autoStartOnBoot = true,
                    ipInfoEndpoint = "https://ifconfig.co/json",
                ),
                expert =
                ExpertSettings(
                    unlockedAt = 1234L,
                    blockScreenshots = true,
                    networkActivityLogging = true,
                ),
                smartProfilePreferences = listOf(smartPreference),
            )

        val reset = original.resetApplicationSettingsToDefaults()

        assertEquals(UiSettings(), reset.ui)
        assertEquals(ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT), reset.connection)
        assertEquals(ExpertSettings(), reset.expert)
        assertEquals(listOf(smartPreference), reset.smartProfilePreferences)
    }
}
