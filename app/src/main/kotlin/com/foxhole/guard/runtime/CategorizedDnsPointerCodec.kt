package com.foxhole.guard.runtime

import java.io.File
import java.nio.file.Files
import java.util.Base64

internal object CategorizedDnsPointerCodec {
    val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val SEQUENCE = Regex("[1-9][0-9]*")

    fun transaction(previous: ByteArray?, pending: ByteArray): ByteArray {
        val encoder = Base64.getEncoder()
        return ((previous?.let(encoder::encodeToString) ?: "-") + "\n" + encoder.encodeToString(pending) + "\n")
            .encodeToByteArray()
    }

    fun parse(parent: File, bytes: ByteArray): CategorizedDnsStage {
        val fields = bytes.decodeToString().trimEnd('\n').split('\n')
        require(fields.size == 5 && tokenPattern.matches(fields[0]))
        require(fields[1] == CategorizedDnsContract.NAME_PREFIX + parent.name)
        require(fields[2].matches(HASH) && fields[3].matches(HASH) && fields[4].matches(SEQUENCE))
        val directory = File(parent, fields[0])
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        return CategorizedDnsStage(fields[0], fields[1], fields[2], fields[3], fields[4].toULong(), directory)
    }

    fun pointer(stage: CategorizedDnsStage): ByteArray =
        (
            listOf(stage.token, stage.name, stage.manifestSha256, stage.artifactSha256, stage.sequence.toString())
                .joinToString("\n") + "\n"
            ).encodeToByteArray()
}
