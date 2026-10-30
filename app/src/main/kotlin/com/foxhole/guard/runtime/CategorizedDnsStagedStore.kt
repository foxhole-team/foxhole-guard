package com.foxhole.guard.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class VerifiedCategorizedDnsRuleSet(
    val manifest: CategorizedDnsManifest,
    val manifestBytes: ByteArray,
    val signatureBytes: ByteArray,
    val artifactBytes: ByteArray,
)

internal data class CategorizedDnsStage(
    val token: String,
    val name: String,
    val manifestSha256: String,
    val artifactSha256: String,
    val sequence: ULong,
    val directory: File,
)

internal data class CategorizedDnsState(
    val active: CategorizedDnsStage?,
    val pending: CategorizedDnsStage?,
)

internal data class CategorizedDnsBundleSnapshot(
    val manifest: ByteArray,
    val signature: ByteArray,
    val artifact: ByteArray,
)

internal class CategorizedDnsReadLease(
    val stage: CategorizedDnsStage,
    private val onRelease: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)
    suspend fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

internal enum class CategorizedDnsPromotion {
    PROMOTED,
    SUPERSEDED,
    PENDING_IO,
}

internal class CategorizedDnsStagedStore(
    private val root: File,
    private val io: CategorizedDnsStoreIo = FileCategorizedDnsStoreIo,
) {
    private val mutex = Mutex()
    private var reader: CategorizedDnsStage? = null

    suspend fun stage(
        ruleSet: VerifiedCategorizedDnsRuleSet,
        verifyExisting: ((ByteArray, ByteArray) -> CategorizedDnsManifest)? = null,
    ): CategorizedDnsStage = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manifest = ruleSet.manifest
            val parent = variantDirectory(manifest.tiers.variant(), create = true)
            recover(parent)
            require(manifest.name == CategorizedDnsContract.NAME_PREFIX + parent.name)
            requireCategorizedArtifactHeader(ruleSet.artifactBytes, manifest)
            require(ruleSet.manifestBytes.size in 1..65536 && ruleSet.signatureBytes.size in 1..72)
            val active = readPointer(parent, "active")
            val pending = readPointer(parent, "pending")
            active?.requireAccepts(manifest, ruleSet.manifestBytes)
            pending?.requireAccepts(manifest, ruleSet.manifestBytes)
            val identical = listOfNotNull(pending, active).firstOrNull {
                it.sequence == manifest.sequence && it.manifestSha256 == CategorizedDnsContract.sha256(ruleSet.manifestBytes)
            }
            if (identical != null) {
                val intact = intactCategorizedExisting(identical, manifest, verifyExisting) { snapshot(identical) }
                if (intact) return@withLock identical
            }
            val stage = newStage(parent, ruleSet)
            val previous = pending?.let(CategorizedDnsPointerCodec::pointer)
            try {
                io.replace(
                    parent,
                    "staging",
                    CategorizedDnsPointerCodec.transaction(previous, CategorizedDnsPointerCodec.pointer(stage))
                )
                writePointer(parent, "pending", stage)
                currentCoroutineContext().ensureActive()
                retire(parent, pending?.token)
                require(io.remove(File(parent, "staging")))
                stage
            } catch (failure: Throwable) {
                rollback(parent, failure)
                if (!File(parent, "staging").exists()) stage.directory.deleteRecursively()
                throw failure
            }
        }
    }

    suspend fun pendingOrNull(variant: String): CategorizedDnsStage? = state(variant).pending
    suspend fun activeOrNull(variant: String): CategorizedDnsStage? = state(variant).active

    suspend fun state(variant: String): CategorizedDnsState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = variantDirectory(variant)
            recover(parent)
            CategorizedDnsState(readPointer(parent, "active"), readPointer(parent, "pending"))
        }
    }

    suspend fun reusableArtifact(
        captured: CategorizedDnsState,
        incoming: CategorizedDnsManifest,
        manifestBytes: ByteArray,
        verifyStored: (ByteArray, ByteArray) -> CategorizedDnsManifest,
    ): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = variantDirectory(incoming.tiers.variant())
            recover(parent)
            val current = CategorizedDnsState(readPointer(parent, "active"), readPointer(parent, "pending"))
            require(current == captured) { "categorized reuse capture was superseded" }
            current.active?.requireAccepts(incoming, manifestBytes)
            current.pending?.requireAccepts(incoming, manifestBytes)
            val matching = listOfNotNull(current.pending, current.active).firstOrNull {
                it.artifactSha256 == incoming.artifact.sha256
            } ?: return@withLock null
            val bytes = try {
                snapshot(matching)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock null
            }
            val stored = try {
                verifyStored(bytes.manifest, bytes.signature)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock null
            }
            require(stored.sequence == matching.sequence && stored.name == matching.name) {
                "categorized metadata floor identity mismatch"
            }
            require(stored.name == incoming.name && stored.tiers == incoming.tiers)
            try {
                requireCategorizedArtifactHeader(bytes.artifact, stored)
                requireCategorizedArtifactHeader(bytes.artifact, incoming)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock null
            }
            currentCoroutineContext().ensureActive()
            bytes.artifact
        }
    }

    suspend fun readBundle(stage: CategorizedDnsStage): CategorizedDnsBundleSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireCurrent(stage)
            snapshot(stage)
        }
    }

    suspend fun acquireReadLease(stage: CategorizedDnsStage): CategorizedDnsReadLease? {
        var acquired: CategorizedDnsReadLease? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    requireCurrent(stage)
                    if (reader != null) return@withLock null
                    payloadFile(stage.directory, "manifest.json", 65536)
                    payloadFile(stage.directory, "manifest.json.sig", 72)
                    payloadFile(stage.directory, CategorizedDnsContract.ARTIFACT_NAME, 67108864)
                    currentCoroutineContext().ensureActive()
                    reader = stage
                    CategorizedDnsReadLease(stage) { releaseReader(stage) }.also { acquired = it }
                }
            }
        } catch (failure: Throwable) {
            try {
                acquired?.release()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private suspend fun releaseReader(stage: CategorizedDnsStage) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (reader == stage) {
                reader = null
                retire(requireNotNull(stage.directory.parentFile))
            }
        }
    }

    suspend fun promote(stage: CategorizedDnsStage, isOwnerCurrent: () -> Boolean): CategorizedDnsPromotion =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val parent = variantDirectory(stage.name.removePrefix(CategorizedDnsContract.NAME_PREFIX))
                recover(parent)
                if (readPointer(parent, "pending") != stage || !isOwnerCurrent()) {
                    return@withLock CategorizedDnsPromotion.SUPERSEDED
                }
                currentCoroutineContext().ensureActive()
                val previous = File(parent, "active").takeIf(File::isFile)?.readBytes()
                val transaction = CategorizedDnsPointerCodec.transaction(
                    previous,
                    CategorizedDnsPointerCodec.pointer(stage)
                )
                try {
                    io.replace(parent, "promotion", transaction)
                    writePointer(parent, "active", stage)
                    requireOwner(isOwnerCurrent)
                    currentCoroutineContext().ensureActive()
                    require(io.remove(File(parent, "pending")))
                    requireOwner(isOwnerCurrent)
                    currentCoroutineContext().ensureActive()
                    retire(parent, previous?.let { CategorizedDnsPointerCodec.parse(parent, it).token })
                    require(io.remove(File(parent, "promotion")))
                    CategorizedDnsPromotion.PROMOTED
                } catch (failure: Throwable) {
                    rollback(parent, failure)
                    when (failure) {
                        is CancellationException -> throw failure
                        is Error -> throw failure
                        is SupersededDnsPromotion -> CategorizedDnsPromotion.SUPERSEDED
                        is Exception -> CategorizedDnsPromotion.PENDING_IO
                        else -> throw failure
                    }
                }
            }
        }

    private fun requireOwner(isOwnerCurrent: () -> Boolean) {
        if (!isOwnerCurrent()) throw SupersededDnsPromotion()
    }

    private fun rollback(parent: File, primary: Throwable) {
        try {
            recover(parent)
        } catch (failure: Error) {
            primary.addSuppressed(failure)
            if (primary !is CancellationException && primary !is Error) throw failure
        } catch (failure: CancellationException) {
            primary.addSuppressed(failure)
            if (primary !is CancellationException && primary !is Error) throw failure
        } catch (failure: Exception) {
            primary.addSuppressed(failure)
        }
    }

    private fun recover(parent: File) {
        recoverPromotion(parent)
        recoverStaging(parent)
    }

    private fun recoverPromotion(parent: File) {
        val journal = File(parent, "promotion")
        if (!journal.exists()) return
        require(!Files.isSymbolicLink(journal.toPath()) && journal.length() in 1..2048)
        val fields = journal.readText().split('\n')
        require(fields.size == 3)
        val previous = fields[0].takeIf { it != "-" }?.let { Base64.getDecoder().decode(it) }
        val pending = Base64.getDecoder().decode(fields[1])
        if (previous == null) {
            require(io.remove(File(parent, "active")))
        } else {
            CategorizedDnsPointerCodec.parse(parent, previous)
            io.replace(parent, "active", previous)
        }
        CategorizedDnsPointerCodec.parse(parent, pending)
        io.replace(parent, "pending", pending)
        require(io.remove(journal))
    }

    private fun recoverStaging(parent: File) {
        val journal = File(parent, "staging")
        if (!journal.exists()) return
        require(!Files.isSymbolicLink(journal.toPath()) && journal.length() in 1..2048)
        val fields = journal.readText().split('\n')
        require(fields.size == 3)
        val previous = fields[0].takeIf { it != "-" }?.let { Base64.getDecoder().decode(it) }
        val incoming = CategorizedDnsPointerCodec.parse(parent, Base64.getDecoder().decode(fields[1]))
        if (previous == null) {
            require(io.remove(File(parent, "pending")))
        } else {
            CategorizedDnsPointerCodec.parse(parent, previous)
            io.replace(parent, "pending", previous)
        }
        require(io.remove(journal))
        if (incoming != reader) incoming.directory.deleteRecursively()
    }

    private fun requireCurrent(stage: CategorizedDnsStage) {
        val parent = variantDirectory(stage.name.removePrefix(CategorizedDnsContract.NAME_PREFIX))
        recover(parent)
        require(stage in listOfNotNull(readPointer(parent, "active"), readPointer(parent, "pending"), reader)) {
            "categorized capture was superseded"
        }
    }

    private fun snapshot(stage: CategorizedDnsStage): CategorizedDnsBundleSnapshot {
        val manifest = readOwnedFile(stage.directory, "manifest.json", 65536)
        val signature = readOwnedFile(stage.directory, "manifest.json.sig", 72)
        val artifact = readOwnedFile(stage.directory, CategorizedDnsContract.ARTIFACT_NAME, 67108864)
        require(CategorizedDnsContract.sha256(manifest) == stage.manifestSha256)
        require(CategorizedDnsContract.sha256(artifact) == stage.artifactSha256)
        return CategorizedDnsBundleSnapshot(manifest, signature, artifact)
    }

    private fun readOwnedFile(directory: File, name: String, limit: Long): ByteArray = io.read(
        payloadFile(directory, name, limit)
    )

    private fun payloadFile(directory: File, name: String, limit: Long): File {
        val file = File(directory, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..limit)
        return file
    }

    private suspend fun newStage(parent: File, ruleSet: VerifiedCategorizedDnsRuleSet): CategorizedDnsStage {
        val token = UUID.randomUUID().toString()
        val directory = File(parent, token)
        require(directory.mkdir())
        var complete = false
        try {
            writeNew(File(directory, "manifest.json"), ruleSet.manifestBytes)
            writeNew(File(directory, "manifest.json.sig"), ruleSet.signatureBytes)
            writeNew(File(directory, CategorizedDnsContract.ARTIFACT_NAME), ruleSet.artifactBytes)
            writeNew(File(directory, ".owned"), token.encodeToByteArray())
            currentCoroutineContext().ensureActive()
            complete = true
            return CategorizedDnsStage(
                token,
                ruleSet.manifest.name,
                CategorizedDnsContract.sha256(ruleSet.manifestBytes),
                ruleSet.manifest.artifact.sha256,
                ruleSet.manifest.sequence,
                directory
            )
        } finally {
            if (!complete) directory.deleteRecursively()
        }
    }

    private fun variantDirectory(variant: String, create: Boolean = false): File {
        require(variant in VARIANTS)
        if (create) require(root.mkdirs() || root.isDirectory)
        require(!Files.isSymbolicLink(root.toPath()))
        val parent = File(root, variant)
        if (create) require(parent.mkdirs() || parent.isDirectory)
        require(!Files.isSymbolicLink(parent.toPath()))
        return parent
    }

    private fun readPointer(parent: File, name: String): CategorizedDnsStage? {
        val pointer = File(parent, name)
        require(!Files.isSymbolicLink(pointer.toPath()))
        if (!pointer.isFile) return null
        require(pointer.length() in 1..512)
        return CategorizedDnsPointerCodec.parse(parent, pointer.readBytes())
    }

    private fun retire(parent: File, previousToken: String? = null) {
        val retain = listOfNotNull(readPointer(parent, "active"), readPointer(parent, "pending"), reader).map {
            it.token
        }.toSet() + listOfNotNull(previousToken)
        parent.listFiles().orEmpty().filter {
            CategorizedDnsPointerCodec.tokenPattern.matches(it.name) && it.name !in retain
        }.forEach { directory ->
            val marker = File(directory, ".owned")
            val regularMarker = !Files.isSymbolicLink(marker.toPath()) && marker.isFile && marker.length() == 36L
            if (!Files.isSymbolicLink(directory.toPath()) && regularMarker && marker.readText() == directory.name) {
                require(directory.deleteRecursively()) { "owned DNS generation removal failed" }
            }
        }
    }

    private fun writePointer(parent: File, name: String, stage: CategorizedDnsStage) = io.replace(
        parent,
        name,
        CategorizedDnsPointerCodec.pointer(stage)
    )

    private fun writeNew(file: File, bytes: ByteArray) {
        require(file.createNewFile())
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private class SupersededDnsPromotion : IllegalStateException()

    private companion object {
        val VARIANTS = setOf("normal-medium", "normal-full", "pro-medium", "pro-full")
    }
}

private fun intactCategorizedExisting(
    stage: CategorizedDnsStage,
    incoming: CategorizedDnsManifest,
    verifyExisting: ((ByteArray, ByteArray) -> CategorizedDnsManifest)?,
    snapshot: () -> CategorizedDnsBundleSnapshot,
): Boolean {
    return try {
        val bytes = snapshot()
        val stored = verifyExisting?.invoke(bytes.manifest, bytes.signature) ?: incoming
        require(stored.sequence == stage.sequence && stored.name == stage.name)
        requireCategorizedArtifactHeader(bytes.artifact, stored)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
