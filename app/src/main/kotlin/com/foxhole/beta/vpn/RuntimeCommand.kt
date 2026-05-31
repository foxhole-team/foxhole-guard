package com.foxhole.beta.vpn

internal sealed interface RuntimeCommand {
    val source: RuntimeCommandSource

    data class StartTunnel(
        val profileId: Long,
        val optionId: String? = null,
        val previousVpnNetworkHandle: Long? = null,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class StartProxy(
        val profileId: Long,
        val optionId: String? = null,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data object StartTorOnly : RuntimeCommand {
        override val source: RuntimeCommandSource = RuntimeCommandSource.USER
    }

    data class StartLocalGuard(
        val mode: LocalGuardMode,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Reload(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Stop(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand

    data class Kill(
        val reason: String,
        override val source: RuntimeCommandSource,
    ) : RuntimeCommand
}

internal enum class RuntimeCommandSource {
    USER,
    SERVICE,
    TILE,
    BOOT,
    AUTO_RECONNECT,
    SMART_START,
    SYSTEM,
}

internal val RuntimeCommand.priority: RuntimeCommandPriority
    get() =
        when (this) {
            is RuntimeCommand.Kill -> RuntimeCommandPriority.KILL
            is RuntimeCommand.Stop -> RuntimeCommandPriority.USER_STOP
            is RuntimeCommand.Reload -> RuntimeCommandPriority.SWITCH
            is RuntimeCommand.StartLocalGuard,
            is RuntimeCommand.StartProxy,
            is RuntimeCommand.StartTunnel,
            RuntimeCommand.StartTorOnly,
            -> RuntimeCommandPriority.NORMAL
        }

internal val RuntimeCommand.queueReason: String
    get() =
        when (this) {
            is RuntimeCommand.Kill -> "kill:$reason"
            is RuntimeCommand.Reload -> "reload:$reason"
            is RuntimeCommand.StartLocalGuard -> "local_guard:${mode.name.lowercase()}"
            is RuntimeCommand.StartProxy -> "proxy:$profileId:${optionId.orEmpty()}"
            is RuntimeCommand.StartTunnel -> "tunnel:$profileId:${optionId.orEmpty()}"
            RuntimeCommand.StartTorOnly -> "tor_only"
            is RuntimeCommand.Stop -> "stop:$reason"
        }
