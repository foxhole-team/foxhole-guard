package com.foxhole.guard.ui.cli.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUserNetworkIdentityPolicyTest {
    @Test
    fun initialLoadShowsSkeletonUntilTheFirstIdentityArrives() {
        val initial = present(fetched = null, refreshing = true, hydrated = false)
        assertFalse(initial.current)
        assertTrue(initial.pending)
        assertTrue(present(fetched = 1000L).current)
    }

    @Test
    fun coldStartForegroundAndGeoEnrichmentKeepTheCurrentIdentityVisible() {
        val frames = listOf(false, true, false, true, false)
        frames.forEach { refreshing -> assertTrue(present(fetched = 1000L, refreshing = refreshing).current) }
        assertTrue(present(fetched = 1100L, refreshing = true).current)
    }

    @Test
    fun failedBackgroundRefreshDoesNotInvalidateTheSameSessionIdentity() {
        assertTrue(present(fetched = 1000L, refreshing = true).current)
        assertTrue(present(fetched = 1000L, refreshing = false).current)
    }

    @Test
    fun connectAndReconnectTransitionsHideEvenAnExistingIdentity() {
        val connecting = present(fetched = 1000L, transition = true, connected = true)
        assertFalse(connecting.current)
        assertTrue(connecting.pending)
    }

    @Test
    fun anOldRouteIdentityCannotSatisfyANewVpnSession() {
        val pending = present(fetched = 1000L, epoch = 2000L, connected = true, metricsLoading = true)
        assertFalse(pending.current)
        assertTrue(pending.pending)
        assertTrue(present(fetched = 2000L, epoch = 2000L, connected = true).current)
    }

    @Test
    fun disconnectDoesNotRelabelTheOldVpnIdentityAsTheDeviceIdentity() {
        assertFalse(present(fetched = 1000L, epoch = 2000L, refreshing = true).current)
        assertTrue(present(fetched = 2100L, epoch = 2000L).current)
    }

    @Test
    fun physicalNetworkChangeStillRequiresANewerObservation() {
        assertFalse(present(fetched = 2000L, floor = 2000L).current)
        assertTrue(present(fetched = 2001L, floor = 2000L).current)
    }

    @Test
    fun missingVpnObservationRemainsPendingWithoutInventingAnAddress() {
        val pending = present(fetched = null, connected = true, metricsLoading = true)
        assertFalse(pending.current)
        assertTrue(pending.pending)
    }

    @Test
    fun settledUnknownIsNotReplacedWithAPreviousSessionValue() {
        val unknown = present(fetched = null)
        assertFalse(unknown.current)
        assertFalse(unknown.pending)
    }

    private fun present(fetched: Long?, epoch: Long = 500L, floor: Long = 0L, transition: Boolean = false,
        refreshing: Boolean = false, connected: Boolean = false, metricsLoading: Boolean = false,
        hydrated: Boolean = true): CliUserNetworkIdentityPresentation = cliUserNetworkIdentityPresentation(
        candidateFetchedAt = fetched, routeChangedAt = epoch, networkInfoFloor = floor,
        routeTransition = transition, refreshing = refreshing, connected = connected,
        connectionMetricsLoading = metricsLoading, settingsHydrated = hydrated, profilesLoaded = hydrated,
    )
}
