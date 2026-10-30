package com.foxhole.guard.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun requireCategorizedArtifactHeader(bytes: ByteArray, manifest: CategorizedDnsManifest) {
    require(bytes.size.toULong() == manifest.artifact.size && bytes.isFoxCoreDnsFst())
    require(CategorizedDnsContract.sha256(bytes) == manifest.artifact.sha256)
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    require(header.getLong(8).toULong() == manifest.artifact.blockEntries)
    require(header.getLong(16).toULong() == manifest.artifact.allowEntries)
    val blockSize = header.getLong(24).toULong()
    val allowSize = header.getLong(32).toULong()
    val totalSize = bytes.size.toULong()
    require(blockSize <= totalSize && allowSize <= totalSize)
    require(80uL + blockSize + allowSize == totalSize)
    val sourceHash = bytes.copyOfRange(40, 72).joinToString("") { "%02x".format(it) }
    require(sourceHash == manifest.taggedInputSha256)
    require(bytes.copyOfRange(72, 80).all { it == 0.toByte() })
    // Native admission also validates suffix-only FST values and category membership counts.
}

internal fun CategorizedDnsStage.requireAccepts(manifest: CategorizedDnsManifest, bytes: ByteArray) {
    require(name == manifest.name)
    require(manifest.sequence >= sequence) { "categorized rollback refused" }
    if (manifest.sequence == sequence) {
        require(manifestSha256 == CategorizedDnsContract.sha256(bytes) && artifactSha256 == manifest.artifact.sha256) {
            "same sequence changed categorized identity"
        }
    }
}
