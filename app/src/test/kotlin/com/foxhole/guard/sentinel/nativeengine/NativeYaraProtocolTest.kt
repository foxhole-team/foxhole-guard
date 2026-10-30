package com.foxhole.guard.sentinel.nativeengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

class NativeYaraProtocolTest {
    private fun report(status: String = "complete", reason: String = "scanned", complete: Boolean = true): ByteArray =
        """{"protocolVersion":1,"sessionId":1,"status":"$status","reason":"$reason","engine":"$NATIVE_YARA_ENGINE","coverageComplete":$complete,"requestedBytes":8,"scannedBytes":8,"elapsedMillis":2,"reportingTruncated":false,"diagnosticCount":0,"diagnostics":[],"matches":[]}""".toByteArray()

    @Test
    fun completedNegativeContainsNoSafetyVerdict() {
        val value = NativeYaraReport.decode(report())
        assertEquals(NativeYaraStatus.COMPLETE, value.status)
        assertTrue(value.coverageComplete)
        assertTrue(value.matches.isEmpty())
    }

    @Test
    fun incompleteNegativeAndCancelRequestRemainExplicit() {
        val partial = NativeYaraReport.decode(report("partial", "caller_incomplete_input", false))
        assertFalse(partial.coverageComplete)
        val cancelled = NativeYaraReport.decode(report("cancelled", "cancel_requested_settlement_required", false))
        assertTrue(cancelled.cancellationRequiresSettlement)
        assertFalse(cancelled.coverageComplete)
    }

    @Test
    fun unknownStatusAndFalseCompletenessAreRejected() {
        assertTrue(runCatching { NativeYaraReport.decode(report("safe")) }.isFailure)
        assertTrue(runCatching { NativeYaraReport.decode(report("timeout", "engine_timeout", true)) }.isFailure)
        assertTrue(runCatching { NativeYaraReport.decode(report(reason = "unknown")) }.isFailure)
        assertTrue(
            runCatching {
                NativeYaraReport.decode(
                    report().toString(Charsets.UTF_8).replace(NATIVE_YARA_ENGINE, "unknown-engine").toByteArray()
                )
            }.isFailure
        )
        assertTrue(runCatching { NativeYaraReport.decode(ByteArray(262145)) }.isFailure)
        assertTrue(
            runCatching {
                NativeYaraReport.decode(
                    ("[".repeat(10000) + "0" + "]".repeat(10000)).toByteArray()
                )
            }.isFailure
        )
    }

    @Test
    fun extraAndDuplicateKeysAtEveryEvidenceLevelAreRejected() {
        val base = report().toString(Charsets.UTF_8)
        for (source in listOf(
            base.replace("\"matches\":[]", "\"extra\":1,\"matches\":[]"),
            base.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"),
            withMatch("\"extra\":1,"),
            withMatch("").replace("\"ruleId\":\"owned\"", "\"ruleId\":\"owned\",\"ruleId\":\"owned\""),
            withMatch("").replace("\"patternId\":\"\$a\"", "\"extra\":1,\"patternId\":\"\$a\""),
            withMatch("").replace("\"start\":0", "\"extra\":1,\"start\":0")
        )) {
            assertTrue(source, runCatching { NativeYaraReport.decode(source.toByteArray()) }.isFailure)
        }
    }

    @Test
    fun malformedUtf8AndInexactNumbersAreRejected() {
        val invalid = report().toString(Charsets.UTF_8).replace("yara-x/1.21.0", "broken-X").toByteArray()
        invalid[invalid.indexOf('X'.code.toByte())] = 0xff.toByte()
        assertTrue(runCatching { NativeYaraReport.decode(invalid) }.exceptionOrNull() is CharacterCodingException)
        for (replacement in listOf("1.0", "1e0", "\"1\"")) {
            assertTrue(
                runCatching {
                    NativeYaraReport.decode(
                        report().toString(
                            Charsets.UTF_8
                        ).replace("\"sessionId\":1", "\"sessionId\":$replacement").toByteArray()
                    )
                }.isFailure
            )
        }
    }

    @Test
    fun byteAndOffsetBoundsAndStatusReasonConsistencyAreRequired() {
        for (source in listOf(
            report(
                "partial",
                "caller_incomplete_input",
                false
            ).toString(Charsets.UTF_8).replace("\"scannedBytes\":8", "\"scannedBytes\":9"),
            withMatch("").replace("\"start\":0", "\"start\":9"),
            withMatch("").replace("\"length\":4", "\"length\":9223372036854775807"),
            report().toString(Charsets.UTF_8).replace("\"reportingTruncated\":false", "\"reportingTruncated\":true"),
            report("complete", "engine_timeout", false).toString(Charsets.UTF_8),
            withMatch(
                ""
            ).replace(
                "\"status\":\"complete\"",
                "\"status\":\"timeout\""
            ).replace(
                "\"reason\":\"scanned\"",
                "\"reason\":\"engine_timeout\""
            ).replace("\"coverageComplete\":true", "\"coverageComplete\":false")
        )) {
            assertTrue(source, runCatching { NativeYaraReport.decode(source.toByteArray()) }.isFailure)
        }
    }

    @Test
    fun compilationAndOwnedRuleReuseAreSeparateFromCoverage() {
        for (reason in listOf("compiled", "owned_compiled_reused")) {
            val source = report(
                "complete",
                reason,
                false
            ).toString(
                Charsets.UTF_8
            ).replace(
                "\"requestedBytes\":8",
                "\"requestedBytes\":0"
            ).replace("\"scannedBytes\":8", "\"scannedBytes\":0")
            val value = NativeYaraReport.decode(source.toByteArray())
            assertEquals(1, value.sessionId)
            assertFalse(value.coverageComplete)
            assertTrue(value.matches.isEmpty())
        }
    }

    @Test
    fun realJniFramesDecodeWithTheSameClosedContract() {
        val supplied = System.getProperty("native.jni.frames")
        val lines = if (supplied != null) {
            Files.readAllLines(Path.of(supplied))
        } else {
            requireNotNull(requireNotNull(javaClass.classLoader).getResourceAsStream("sentinel-native-owned-jni.txt"))
                .bufferedReader().use { it.readLines() }
        }
        assertTrue(lines.contains("JNI_FACADE_PASS"))
        val reports = lines.filter { it.startsWith("{") }.map { NativeYaraReport.decode(it.toByteArray()) }
        assertEquals(14, reports.size)
        assertTrue(reports.any { it.matches.singleOrNull()?.ruleId == "owned_marker" })
        assertTrue(reports.any { it.reason == NativeYaraReason.OWNED_COMPILED_REUSED })
        assertTrue(reports.any { it.reason == NativeYaraReason.TRUNCATED_DESCRIPTOR })
        assertTrue(reports.any { it.cancellationRequiresSettlement })
    }

    private fun withMatch(extra: String): String = report().toString(Charsets.UTF_8).replace(
        "\"matches\":[]",
        "\"matches\":[{$extra\"ruleId\":\"owned\",\"patterns\":[{\"patternId\":\"\$a\",\"offsets\":[{\"start\":0,\"length\":4}]}]}]"
    )
}
