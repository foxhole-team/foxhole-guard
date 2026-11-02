package com.foxhole.guard.runtime

internal fun appUpdateChecksumMatches(checksums: String, apkName: String, expectedDigest: String): Boolean {
    if (expectedDigest.length != 64 || expectedDigest.any { it !in "0123456789abcdefABCDEF" }) return false
    var matches = 0
    checksums.lineSequence().forEach { line ->
        if (line.isBlank()) return@forEach
        if (line.length < 67 || line[64] != ' ' || line[65] !in " *" ||
            line.take(64).any { it !in "0123456789abcdefABCDEF" }) return false
        if (line.substring(66) == apkName) {
            matches++
            if (!line.take(64).equals(expectedDigest, ignoreCase = true)) return false
        }
    }
    return matches == 1
}
