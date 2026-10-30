package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class RouterPlatform(val openWrtSeries: String, val target: String, val architecture: String)
internal data class RouterSystemInspection(
    val openWrtVersion: String,
    val platform: RouterPlatform,
    val resources: RouterResourceFacts,
    val kernelVersion: String? = null,
    val compactBackupBytes: Long? = null,
)

internal class RouterInspectionFailure : IOException("router_inspection_failed")

internal fun inspectRouterSystem(client: RouterSshClient, supportedPlatforms: Set<RouterPlatform>): RouterSystemInspection {
    val board = client.read(RouterReadCommand.BOARD)
    val architecture = client.read(RouterReadCommand.ARCHITECTURE)
    val flash = client.read(RouterReadCommand.FLASH)
    val memory = client.read(RouterReadCommand.MEMORY)
    val pending = client.read(RouterReadCommand.CONFIGURATION)
    if (routerText(pending).isNotBlank()) throw RouterInspectionFailure()
    val system = parseRouterSystem(board, architecture, flash, memory, supportedPlatforms)
    val kernel = parseRouterKernelVersion(client.read(RouterReadCommand.KERNEL_PACKAGE))
    return system.copy(kernelVersion = kernel)
}

internal fun parseRouterKernelVersion(bytes: ByteArray): String {
    if (bytes.size !in 1..1024) throw RouterInspectionFailure()
    val text = routerText(bytes).trim()
    if (!text.matches(Regex("kernel-[A-Za-z0-9][A-Za-z0-9.+_~-]{0,159}"))) throw RouterInspectionFailure()
    return text.removePrefix("kernel-")
}

internal fun parseRouterSystem(
    board: ByteArray,
    architecture: ByteArray,
    flash: ByteArray,
    memory: ByteArray,
    supportedPlatforms: Set<RouterPlatform>,
): RouterSystemInspection = try {
    if (board.size > 16384) throw RouterInspectionFailure()
    val boardText = routerText(board)
    requireBoardNesting(boardText)
    val root = Json.parseToJsonElement(boardText) as? JsonObject ?: throw RouterInspectionFailure()
    val release = root["release"] as? JsonObject ?: throw RouterInspectionFailure()
    if (release.string("distribution") != "OpenWrt") throw RouterInspectionFailure()
    val version = release.string("version")
    val match = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(version)
        ?: throw RouterInspectionFailure()
    val series = match.groupValues[1] + "." + match.groupValues[2]
    val target = release.string("target")
    val architectures = routerArchitectures(routerText(architecture))
    val platform = supportedPlatforms.singleOrNull {
        it.openWrtSeries == series && it.target == target && it.architecture in architectures && routerInstallTarget(it.architecture) == target
    } ?: throw RouterInspectionFailure()
    RouterSystemInspection(version, platform, RouterResourceFacts(
        routerAvailableFlash(routerText(flash)), routerAvailableMemory(routerText(memory)),
    ))
} catch (failure: RouterInspectionFailure) {
    throw failure
} catch (_: IllegalArgumentException) {
    throw RouterInspectionFailure()
}

private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull ?: throw RouterInspectionFailure()

private fun routerArchitectures(text: String): Set<String> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size == 1 && lines[0].trim().matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) return setOf(lines[0].trim())
    val entries = lines.map { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size != 3 || fields[0] != "arch" || !fields[1].matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) throw RouterInspectionFailure()
        fields[1] to (fields[2].toLongOrNull()?.takeIf { it > 0 } ?: throw RouterInspectionFailure())
    }
    val maximum = entries.maxOfOrNull { it.second } ?: throw RouterInspectionFailure()
    return entries.filter { it.second == maximum }.map { it.first }.toSet()
}

private fun routerAvailableFlash(text: String): Long {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size != 2) throw RouterInspectionFailure()
    val fields = lines[1].trim().split(Regex("\\s+"))
    if (fields.size != 6 || fields[5] !in setOf("/", "/overlay") || !fields[4].matches(Regex("[0-9]+%"))) throw RouterInspectionFailure()
    val total = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw RouterInspectionFailure()
    val used = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: throw RouterInspectionFailure()
    val available = fields[3].toLongOrNull()?.takeIf { it >= 0 && it <= total } ?: throw RouterInspectionFailure()
    if (used > total) throw RouterInspectionFailure()
    return routerKiBytes(available)
}

private fun routerAvailableMemory(text: String): Long {
    val entries = text.lineSequence().filter { it.startsWith("MemAvailable:") }.toList()
    if (entries.size != 1) throw RouterInspectionFailure()
    val match = Regex("MemAvailable:[ \\t]+([0-9]+)[ \\t]+kB[ \\t]*").matchEntire(entries.single())
        ?: throw RouterInspectionFailure()
    return routerKiBytes(match.groupValues[1].toLongOrNull() ?: throw RouterInspectionFailure())
}

private fun routerKiBytes(value: Long): Long {
    if (value < 0 || value > Long.MAX_VALUE / 1024) throw RouterInspectionFailure()
    return value * 1024
}

private fun requireBoardNesting(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 16) throw RouterInspectionFailure() }
            '}', ']' -> { depth--; if (depth < 0) throw RouterInspectionFailure() }
        }
    }
    if (depth != 0 || quoted) throw RouterInspectionFailure()
}

private fun routerText(value: ByteArray): String {
    if (value.size > 262144) throw RouterInspectionFailure()
    return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString() }
    catch (_: java.nio.charset.CharacterCodingException) { throw RouterInspectionFailure() }
}

/** Only a bounded byte count is returned by the read-only SSH probe, never backup paths or contents. */
internal fun parseRouterCompactBackupBytes(bytes: ByteArray): Long {
    if (bytes.size !in 1..32) throw RouterInspectionFailure()
    val text = routerText(bytes).trim()
    if (!text.matches(Regex("0|[1-9][0-9]{0,9}"))) throw RouterInspectionFailure()
    return text.toLongOrNull()?.takeIf { it in 0L..536870912L } ?: throw RouterInspectionFailure()
}
