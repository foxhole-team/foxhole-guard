package com.foxhole.guard.core.sharing

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

internal class FileShareIo(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val inputDirectory = File(appContext.noBackupFilesDir, "file-share-input")
    private val invitationDirectory = File(appContext.cacheDir, "file-share-invitations")
    val vaultRoot = File(appContext.noBackupFilesDir, "file-share-vault")

    fun cleanupStaleSessionFiles() {
        clearDirectory(inputDirectory)
        clearDirectory(invitationDirectory)
        clearDirectory(vaultRoot)
    }

    fun resetVault() = clearDirectory(vaultRoot)

    suspend fun prepareSource(uri: Uri): PreparedShareSource {
        ensurePrivateDirectory(inputDirectory)
        val metadata = queryMetadata(uri)
        rejectOversizedMetadata(metadata.sizeBytes)
        ensureCapacity(metadata.sizeBytes)
        val target = File.createTempFile("source-", ".bin", inputDirectory)
        runCatching { Os.chmod(target.absolutePath, PRIVATE_FILE_MODE) }
        var completed = false
        try {
            val copied = copyUriToTarget(uri, target)
            ensureVaultCapacity(copied)
            completed = true
            return PreparedShareSource(
                file = target,
                displayName = sanitizeFileShareDisplayName(metadata.displayName),
                mediaType = sanitizeFileShareMediaType(resolver.getType(uri)),
                sizeBytes = copied,
            )
        } catch (error: Exception) {
            throw normalizedCopyFailure(error)
        } finally {
            if (!completed) target.delete()
        }
    }

    fun freshInvitationFile(): File {
        ensurePrivateDirectory(invitationDirectory)
        val suffix = ByteArray(INVITATION_RANDOM_BYTES).also(SecureRandom()::nextBytes).toHex()
        return File(invitationDirectory, "foxhole-share-$suffix.txt")
    }

    fun delete(file: File?) {
        file?.takeIf(File::exists)?.delete()
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).takeIf { value -> value >= 0L }
                    }
                }
            }
        }
        return SourceMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    private fun ensureCapacity(knownSize: Long?) {
        if (knownSize == null) return
        val required = knownSize.coerceAtMost(FILE_SHARE_MAX_BYTES) * 2L + STORAGE_RESERVE_BYTES
        requireUsableSpace(required)
    }

    private fun ensureVaultCapacity(copiedSize: Long) {
        requireUsableSpace(copiedSize + STORAGE_RESERVE_BYTES)
    }

    private fun requireUsableSpace(required: Long) {
        if (inputDirectory.usableSpace < required) {
            throw FileShareException(FileShareFailureReason.STORAGE_UNAVAILABLE)
        }
    }

    private fun rejectOversizedMetadata(knownSize: Long?) {
        if (knownSize != null && knownSize > FILE_SHARE_MAX_BYTES) {
            throw FileShareException(FileShareFailureReason.FILE_TOO_LARGE)
        }
    }

    private suspend fun copyUriToTarget(
        uri: Uri,
        target: File,
    ): Long {
        val source =
            resolver.openInputStream(uri)
                ?: throw FileShareException(FileShareFailureReason.FILE_UNAVAILABLE)
        return source.use { input -> copyInputToTarget(input, target) }
    }

    private suspend fun copyInputToTarget(
        input: InputStream,
        target: File,
    ): Long =
        FileOutputStream(target, false).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > FILE_SHARE_MAX_BYTES) {
                    throw FileShareException(FileShareFailureReason.FILE_TOO_LARGE)
                }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
            total
        }

    private fun clearDirectory(directory: File) {
        val root = directory.toPath()
        if (Files.isSymbolicLink(root)) {
            throw FileShareException(FileShareFailureReason.STORAGE_UNAVAILABLE)
        }
        ensurePrivateDirectory(directory)
        Files.newDirectoryStream(root).use { children ->
            children.forEach(::deleteTreeNoFollow)
        }
    }

    private fun ensurePrivateDirectory(directory: File) {
        val unsafeExistingPath =
            Files.isSymbolicLink(directory.toPath()) ||
                (directory.exists() && !directory.isDirectory)
        if (unsafeExistingPath) {
            throw FileShareException(FileShareFailureReason.STORAGE_UNAVAILABLE)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw FileShareException(FileShareFailureReason.STORAGE_UNAVAILABLE)
        }
        runCatching { Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE) }
    }

    private fun deleteTreeNoFollow(root: Path) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private data class SourceMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val INVITATION_RANDOM_BYTES = 12
        const val STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L
        const val PRIVATE_DIRECTORY_MODE = 448 // 0700
        const val PRIVATE_FILE_MODE = 384 // 0600
    }
}

private fun normalizedCopyFailure(error: Exception): Exception =
    when (error) {
        is CancellationException, is FileShareException -> error
        else -> FileShareException(FileShareFailureReason.FILE_UNAVAILABLE, error)
    }

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
