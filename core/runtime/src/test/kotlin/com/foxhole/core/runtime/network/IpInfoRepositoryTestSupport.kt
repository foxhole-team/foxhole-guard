package com.foxhole.core.runtime.network

import kotlinx.serialization.json.Json

// Shared fixture of the IpInfoRepository suites. Split from IpInfoRepositoryTest.kt.
internal open class IpInfoRepositoryTestSupport {
    protected val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
}
