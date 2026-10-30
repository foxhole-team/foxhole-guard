package com.foxhole.guard.core.backup

import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.ProfileEntity
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

internal class OwnedProfileFiles(private val root: File) : ProfileBackupCheckpointAccess {
    private val rows = File(root, "rows.tsv")
    private val json = Json

    override suspend fun profiles(): List<ProfileEntity> =
        if (!rows.exists()) {
            emptyList()
        } else {
            rows.readLines().filter(String::isNotBlank).map { line ->
                val parts = line.split('\t')
                ProfileEntity(
                    parts[0].toLong(),
                    parts[1],
                    "RAW_CONFIG_JSON",
                    parts[2],
                    "SOCKS",
                    1L,
                    null,
                    parts[3].toBoolean()
                )
            }
        }

    suspend fun insert(row: ProfileEntity, secret: StoredProfileSecret) {
        writeSecret(row.secretRef, secret)
        replaceProfiles((profiles().filterNot { it.id == row.id } + row).sortedBy(ProfileEntity::id))
    }

    override suspend fun readSecret(ref: String): StoredProfileSecret? {
        val file = File(root, "$ref.secret")
        return if (file.exists()) json.decodeFromString(StoredProfileSecret.serializer(), file.readText()) else null
    }

    override suspend fun writeSecret(ref: String, secret: StoredProfileSecret) {
        write(File(root, "$ref.secret"), json.encodeToString(StoredProfileSecret.serializer(), secret))
    }

    override suspend fun replaceProfiles(rows: List<ProfileEntity>) {
        write(this.rows, rows.joinToString("\n") { "${it.id}\t${it.name}\t${it.secretRef}\t${it.isActive}" })
    }

    override suspend fun cleanupSecret(ref: String) {
        File(root, "$ref.secret").delete()
    }

    private fun write(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.encodeToByteArray())
            output.fd.sync()
        }
    }
}
