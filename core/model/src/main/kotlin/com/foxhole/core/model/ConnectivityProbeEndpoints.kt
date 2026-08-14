package com.foxhole.core.model

/** Captive-portal / connectivity probe endpoints. Pure data shared by the runtime engine and host. */
val CONNECTIVITY_PROBE_ENDPOINTS: List<String> =
    listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
    )
