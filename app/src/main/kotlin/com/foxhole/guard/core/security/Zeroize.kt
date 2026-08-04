package com.foxhole.guard.core.security

import java.util.Arrays

internal fun ByteArray.zeroize() {
    Arrays.fill(this, 0)
}
