package com.foxhole.guard.core.sentinel.scanner

import android.os.ParcelFileDescriptor
import com.foxhole.guard.sentinel.nativeengine.NativeYara

internal fun requireScannerReadonlyRegularFd(descriptor: ParcelFileDescriptor) {
    NativeYara.loadInIsolatedScannerProcess()
    require(NativeYara.nativeIsReadonlyRegularFd(descriptor.fd)) { "Read-only regular scanner descriptor required" }
}
