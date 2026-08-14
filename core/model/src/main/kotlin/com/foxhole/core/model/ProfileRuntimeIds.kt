package com.foxhole.core.model

/**
 * Sentinel profile ids used across the runtime, UI and persistence. These are pure data constants
 * (not tied to the Android VPN service), so they live in `:core:model` — the runtime engine and the
 * app both depend on them without depending on the host `FoxholeVpnService`.
 */
const val LOCAL_GUARD_PROFILE_ID: Long = -10L

const val TOR_ONLY_PROFILE_ID: Long = -20L
