package com.foxhole.guard.ui

import com.foxhole.guard.runtime.GeoIpUpdateStatus
import com.foxhole.guard.runtime.RemoteUpdatePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelOnboardingSupportTest {
    @Test
    fun `fresh wizard downloads the geo database and the fingerprint tables by default`() {
        assertEquals(
            listOf(OnboardingDownload.GEOIP, OnboardingDownload.TLS_FINGERPRINTS),
            onboardingDownloadPlan(
                geoIp = true,
                tlsFingerprints = true,
                dnsFilter = false,
                torBridges = false,
                threatIntel = false,
            ),
        )
    }

    @Test
    fun `all FoxHole DB data sets have stable visible order`() {
        assertEquals(
            listOf(
                OnboardingDownload.GEOIP,
                OnboardingDownload.TLS_FINGERPRINTS,
                OnboardingDownload.DNS_FILTER,
                OnboardingDownload.TOR_BRIDGES,
                OnboardingDownload.THREAT_INTEL,
            ),
            onboardingDownloadPlan(
                geoIp = true,
                tlsFingerprints = true,
                dnsFilter = true,
                torBridges = true,
                threatIntel = true,
            ),
        )
    }

    @Test
    fun `wizard persists dns filtering only after the verified download completed`() {
        val ready =
            OnboardingProgress(
                finished = true,
                items = listOf(OnboardingDownloadState(OnboardingDownload.DNS_FILTER, phase = null, done = true)),
            )
        val failed =
            OnboardingProgress(
                finished = true,
                items = listOf(OnboardingDownloadState(OnboardingDownload.DNS_FILTER, phase = null, failed = true)),
            )

        assertTrue(onboardingDnsFilterCanEnable(requested = true, progress = ready))
        assertFalse(onboardingDnsFilterCanEnable(requested = true, progress = failed))
        assertFalse(onboardingDnsFilterCanEnable(requested = true, progress = OnboardingProgress(finished = true)))
        assertFalse(onboardingDnsFilterCanEnable(requested = false, progress = ready))
    }

    @Test
    fun `wizard mirrors Tor and Sentinel verified activation policy`() {
        val verified =
            OnboardingProgress(
                finished = true,
                items = listOf(
                    OnboardingDownloadState(OnboardingDownload.TOR_BRIDGES, phase = null, done = true),
                    OnboardingDownloadState(OnboardingDownload.THREAT_INTEL, phase = null, done = true),
                ),
            )
        val failed =
            OnboardingProgress(
                finished = true,
                items = listOf(
                    OnboardingDownloadState(OnboardingDownload.TOR_BRIDGES, phase = null, failed = true),
                    OnboardingDownloadState(OnboardingDownload.THREAT_INTEL, phase = null, failed = true),
                ),
            )

        assertTrue(onboardingTorBridgesCanEnable(true, useFoxholeSource = false, progress = failed))
        assertFalse(onboardingTorBridgesCanEnable(true, useFoxholeSource = true, progress = failed))
        assertTrue(onboardingTorBridgesCanEnable(true, useFoxholeSource = true, progress = verified))
        assertFalse(onboardingSentinelCanEnable(requested = true, progress = failed))
        assertTrue(onboardingSentinelCanEnable(requested = true, progress = verified))
        assertFalse(onboardingSentinelCanEnable(requested = false, progress = verified))
    }

    @Test
    fun `traffic map remains off when geo download is skipped or verification failed`() {
        val skipped = OnboardingProgress(finished = true)
        val failed =
            OnboardingProgress(
                finished = true,
                items = listOf(OnboardingDownloadState(OnboardingDownload.GEOIP, phase = null, failed = true)),
            )
        val verified =
            OnboardingProgress(
                finished = true,
                items = listOf(OnboardingDownloadState(OnboardingDownload.GEOIP, phase = null, done = true)),
            )

        assertFalse(onboardingGeoIpCanEnable(skipped))
        assertFalse(onboardingGeoIpCanEnable(failed))
        assertTrue(onboardingGeoIpCanEnable(verified))
        assertFalse(
            onboardingGeoIpDownloadVerified(
                status = GeoIpUpdateStatus.UPDATED,
                hasInstalledDatabase = false,
            ),
        )
        assertFalse(
            onboardingGeoIpDownloadVerified(
                status = GeoIpUpdateStatus.FAILED,
                hasInstalledDatabase = true,
            ),
        )
    }

    @Test
    fun `overall progress includes byte fraction and verification phase`() {
        val downloading =
            OnboardingProgress(
                running = true,
                items =
                listOf(
                    OnboardingDownloadState(OnboardingDownload.GEOIP, phase = null, done = true),
                    OnboardingDownloadState(
                        OnboardingDownload.DNS_FILTER,
                        phase = RemoteUpdatePhase.DOWNLOADING,
                        downloadedBytes = 25L,
                        totalBytes = 100L,
                    ),
                ),
            )
        val verifying =
            downloading.copy(
                items =
                listOf(
                    downloading.items.first(),
                    downloading.items.last().copy(
                        phase = RemoteUpdatePhase.VERIFYING,
                    ),
                ),
            )

        assertEquals(0.625f, downloading.fraction, 0.0001f)
        assertEquals(1f, verifying.fraction, 0.0001f)
    }
}
