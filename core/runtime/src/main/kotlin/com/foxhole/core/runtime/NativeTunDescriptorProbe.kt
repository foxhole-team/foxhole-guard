package com.foxhole.core.runtime

internal enum class NativeTunDescriptorProbe {
    OPEN,
    CLOSED,
    UNKNOWN,
}

/**
 * Closing Android's master TUN is safe only after the native duplicate is
 * definitely gone. A native return code alone is insufficient: a cancelled
 * replacement may already have released its TUN before its control-plane
 * result is observed, while an early success must never hide a live native
 * descriptor.
 */
internal fun shouldCloseMasterTun(nativeTunProbe: NativeTunDescriptorProbe): Boolean =
    nativeTunProbe == NativeTunDescriptorProbe.CLOSED
