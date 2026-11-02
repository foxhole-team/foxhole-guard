package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AppUpdateChecksumsTest {
    @Test fun checksumsRequireOneExactMatchingApkDigest() {
        val digest = "a".repeat(64)
        val row = "$digest  update.apk"
        assertTrue(appUpdateChecksumMatches(row, "update.apk", digest))
        assertTrue(appUpdateChecksumMatches("$digest *update.apk\n", "update.apk", digest.uppercase()))
        assertFalse(appUpdateChecksumMatches(row, "other.apk", digest))
        assertFalse(appUpdateChecksumMatches(row, "update.apk", "b".repeat(64)))
        assertFalse(appUpdateChecksumMatches("$row\n$row", "update.apk", digest))
        assertFalse(appUpdateChecksumMatches("malformed\n$row", "update.apk", digest))
        assertFalse(appUpdateChecksumMatches("$digest  ../update.apk", "update.apk", digest))
    }
}
