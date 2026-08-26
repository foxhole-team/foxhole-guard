package com.foxhole.core.runtime.network

import kotlinx.serialization.json.Json

internal open class IpInfoRepositoryTestSupport {
    protected val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
}
