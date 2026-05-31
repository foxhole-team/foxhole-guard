package com.foxhole.beta.vpn

internal class RuntimeInstanceStore(
    private val createRuntime: () -> VpnCoreRuntime,
) {
    private val lock = Any()

    @Volatile
    private var instance: VpnCoreRuntime? = null

    fun get(): VpnCoreRuntime {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: createRuntime().also { runtime -> instance = runtime }
        }
    }

    fun current(): VpnCoreRuntime? = instance

    fun nativeSnapshot(): NativeRuntimeSnapshot =
        current()?.nativeSnapshot() ?: NativeRuntimeSnapshot.NONE

    fun clear() {
        synchronized(lock) {
            instance = null
        }
    }
}
