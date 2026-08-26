package com.foxhole.core.runtime.network

internal fun resolveIpInfoFetchStrategy(
    endpoint: String,
    callTimeoutMs: Long?,
    mode: IpInfoFetchMode,
    torExit: Boolean,
): IpInfoRepository.EndpointFetchStrategy =
    when (mode) {
        IpInfoFetchMode.FULL ->
            IpInfoRepository.EndpointFetchStrategy(
                endpointCandidates = withTorExitEndpoint(effectiveEndpoints(endpoint), torExit),
                callTimeoutMs = callTimeoutMs ?: IP_INFO_FULL_CALL_TIMEOUT_MS,
            )
        IpInfoFetchMode.ENTRY_QUICK ->
            IpInfoRepository.EndpointFetchStrategy(
                endpointCandidates = withTorExitEndpoint(quickEndpoints(endpoint), torExit),
                callTimeoutMs = callTimeoutMs ?: IP_INFO_ENTRY_QUICK_CALL_TIMEOUT_MS,
            )
        IpInfoFetchMode.GEO_ENRICHMENT ->
            IpInfoRepository.EndpointFetchStrategy(
                endpointCandidates = withTorExitEndpoint(geoEnrichmentEndpoints(endpoint), torExit),
                callTimeoutMs = callTimeoutMs ?: IP_INFO_GEO_ENRICHMENT_CALL_TIMEOUT_MS,
                parallelCandidates = true,
            )
    }

internal fun withTorExitEndpoint(
    candidates: List<String>,
    torExit: Boolean,
): List<String> =
    if (!torExit) {
        candidates
    } else {
        buildList {
            add(TOR_CHECK_IP_INFO_ENDPOINT)
            candidates.forEach { candidate ->
                if (!candidate.equals(TOR_CHECK_IP_INFO_ENDPOINT, ignoreCase = true)) {
                    add(candidate)
                }
            }
        }
    }

internal const val IP_INFO_ENTRY_QUICK_CALL_TIMEOUT_MS = 2_500L
internal const val IP_INFO_GEO_ENRICHMENT_CALL_TIMEOUT_MS = 8_000L
internal const val IP_INFO_FULL_CALL_TIMEOUT_MS = 4_000L
