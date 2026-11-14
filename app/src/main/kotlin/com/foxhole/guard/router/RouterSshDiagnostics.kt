package com.foxhole.guard.router

internal enum class RouterSshFailureCode {
    PACKAGE_AUTHENTICATION, BUNDLE_TRANSPORT, INSTALL_PLAN, APK_SIGNATURE, APK_RESOLUTION,
    MISSING_FILE_OR_TOOL, MEMORY, EXISTING_STATE, SYNTAX, OUTPUT_LIMIT, TIMEOUT, CONNECTION_CLOSED,
}

/** Expose fixed failure categories, never remote text, commands, URLs or credentials. */
internal fun routerSshFailureCodes(bytes: ByteArray): Set<RouterSshFailureCode> {
    require(bytes.size <= 16384)
    val text = bytes.toString(Charsets.UTF_8).lowercase()
    val markers = mapOf(
        RouterSshFailureCode.PACKAGE_AUTHENTICATION to listOf("indexed package authentication failed"),
        RouterSshFailureCode.BUNDLE_TRANSPORT to listOf("invalid authenticated bundle transport", "invalid private bundle transport"),
        RouterSshFailureCode.INSTALL_PLAN to listOf("invalid install plan", "invalid compact"),
        RouterSshFailureCode.APK_SIGNATURE to listOf("bad signature", "untrusted signature", "signature verification failed"),
        RouterSshFailureCode.APK_RESOLUTION to listOf("unable to select packages", "conflicts:", "unsatisfiable constraints"),
        RouterSshFailureCode.MISSING_FILE_OR_TOOL to listOf("no such file", "not found"),
        RouterSshFailureCode.MEMORY to listOf("insufficient ram", "insufficient memory", "out of memory", "cannot allocate memory"),
        RouterSshFailureCode.EXISTING_STATE to listOf("existing foxhole state requires paired management"),
        RouterSshFailureCode.SYNTAX to listOf("syntax error"),
    )
    return markers.filterValues { values -> values.any { it in text } }.keys.toSet()
}
