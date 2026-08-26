package com.foxhole.guard.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.runtime.network.IpInfoFetchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.startPublicDnsIdentityRefresh(minimumLoadingDurationMs: Long) {
    val generation = ++publicDnsIdentityRefreshGeneration
    publicDnsIdentityRefreshJob?.cancel()
    publicDnsIdentityMutable.value = PublicDnsIdentity(phase = PublicDnsIdentityPhase.LOADING)
    publicDnsIdentityRefreshJob =
        viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                val result = publicDnsIdentityResolver.resolve()
                awaitPublicDnsMinimumLoadingDuration(startedAtMs, minimumLoadingDurationMs)
                if (generation == publicDnsIdentityRefreshGeneration) {
                    publicDnsIdentityMutable.value =
                        PublicDnsIdentity(
                            serverAddress = result.serverAddress,
                            countryCode = result.countryCode,
                            phase = PublicDnsIdentityPhase.RESOLVED,
                        )
                    container.diagnosticsLogger.record(
                        "dns",
                        "public resolver identity refreshed country=${result.countryCode.orEmpty()}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                awaitPublicDnsMinimumLoadingDuration(startedAtMs, minimumLoadingDurationMs)
                if (generation == publicDnsIdentityRefreshGeneration) {
                    publicDnsIdentityMutable.value = PublicDnsIdentity(phase = PublicDnsIdentityPhase.FAILED)
                    container.diagnosticsLogger.recordFailure(
                        "dns",
                        "public resolver identity unavailable: ${error.javaClass.simpleName}",
                    )
                }
            } finally {
                if (generation == publicDnsIdentityRefreshGeneration) {
                    publicDnsIdentityRefreshJob = null
                }
            }
        }
}

internal fun HomeViewModel.invalidatePublicDnsIdentityRefresh() {
    publicDnsIdentityRefreshGeneration += 1
    publicDnsIdentityRefreshJob?.cancel()
    publicDnsIdentityRefreshJob = null
    publicDnsIdentityMutable.value = PublicDnsIdentity(phase = PublicDnsIdentityPhase.LOADING)
}

internal fun shouldRefreshPublicDnsIdentity(
    fetchMode: IpInfoFetchMode,
    reason: IpInfoRefreshReason,
): Boolean = fetchMode != IpInfoFetchMode.GEO_ENRICHMENT && reason != IpInfoRefreshReason.TOR_ROUTE

private suspend fun awaitPublicDnsMinimumLoadingDuration(
    startedAtMs: Long,
    minimumLoadingDurationMs: Long,
) {
    val remainingMs = minimumLoadingDurationMs - (SystemClock.elapsedRealtime() - startedAtMs)
    if (remainingMs > 0L) delay(remainingMs)
}
