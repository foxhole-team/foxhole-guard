package com.foxhole.beta.vpn

import android.net.Network
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger

internal interface RuntimeDiagnosticsSink {
    fun record(tag: String, message: String)

    fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    )

    fun recordThrottled(
        tag: String,
        throttleKey: String,
        windowMs: Long,
        message: String,
    ) {
        record(tag, message)
    }
}

internal class DiagnosticsLoggerRuntimeDiagnosticsSink(
    private val logger: DiagnosticsLogger,
) : RuntimeDiagnosticsSink {
    override fun record(tag: String, message: String) {
        logger.record(tag, message)
    }

    override fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) {
        logger.recordStructured(tag, headline, *details)
    }

    override fun recordThrottled(
        tag: String,
        throttleKey: String,
        windowMs: Long,
        message: String,
    ) {
        logger.recordThrottled(
            tag = tag,
            throttleKey = throttleKey,
            windowMs = windowMs,
            message = message,
        )
    }
}

@Suppress("TooManyFunctions")
internal interface LibboxRuntimeNative {
    fun isAvailable(): Boolean

    fun setupIfNeeded()

    fun commandServerHandlerProxy(
        onReload: () -> Unit,
        onStop: () -> Unit,
        onDebug: (String) -> Unit,
    ): Any

    fun platformProxy(
        host: RuntimeServiceHost,
        defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
        openTun: (RuntimeServiceHost, Any) -> Int,
    ): Any

    fun newCommandServer(handler: Any, platform: Any): Any

    fun startServer(commandServer: Any)

    fun closeServer(commandServer: Any)

    fun closeService(commandServer: Any)

    fun checkConfig(commandServer: Any, config: String)

    fun startOrReloadService(commandServer: Any, config: String)

    fun resetNetwork(commandServer: Any)

    fun call(target: Any?, name: String, vararg args: Any?): Any?

    fun callBoolean(target: Any?, name: String): Boolean

    fun callInt(target: Any?, name: String): Int

    fun collectStrings(iterator: Any?): List<String>

    fun collectStringBoxOrIterator(value: Any?): List<String>

    fun forEachRoutePrefix(
        iterator: Any?,
        block: (ReflectedRoutePrefix) -> Unit,
    )
}

internal interface RuntimeDefaultNetworkMonitor {
    fun start()

    fun stop()

    fun setListener(listener: Any?)

    fun requireNetwork(): Network

    fun isCurrentNetworkMetered(): Boolean

    fun bindSocketToDefaultNetwork(fd: Int)

    fun dispatchListenerUpdate()
}
