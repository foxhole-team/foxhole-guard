package com.foxhole.core.runtime

internal enum class NativeTunDescriptorProbe {
    OPEN,
    CLOSED,
    UNKNOWN,
}

internal fun shouldCloseMasterTun(nativeTunProbe: NativeTunDescriptorProbe): Boolean =
    nativeTunProbe == NativeTunDescriptorProbe.CLOSED
