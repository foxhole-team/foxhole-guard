package com.foxhole.guard.router

internal const val ROUTER_PIN_DIGITS = 6

internal fun validRouterPin(pin: CharArray): Boolean =
    pin.size == ROUTER_PIN_DIGITS && pin.all { it in '0'..'9' }

internal fun routerPinClockMs(): Long = android.os.SystemClock.elapsedRealtime()

internal fun routerPinCooldownUntil(seconds: Int, now: Long = routerPinClockMs()): Long {
    require(seconds in 1..300)
    return now + seconds * 1000L
}

internal fun routerPinCooldownRemaining(until: Long?): Int =
    if (until == null) 0 else routerPinCooldownRemaining(until, routerPinClockMs())

internal fun routerPinCooldownRemaining(until: Long?, now: Long): Int =
    if (until == null) 0 else ((until - now).coerceIn(0L, 300_000L) + 999L).div(1000L).toInt()

internal class RouterPinCooldown(val retryAfterSeconds: Int) : RouterApiFailure(RouterApiError.LOCKED) {
    init { require(retryAfterSeconds in 1..300) }
}
