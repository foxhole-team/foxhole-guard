package com.foxhole.guard.guardian

import com.foxhole.guard.core.sentinel.FileCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GuardInventoryApp(
    val packageName: String,
    val versionCode: Long = 0L,
    val uid: Int? = null,
    val signerSha256: String? = null,
)

@Serializable
data class GuardInventorySnapshot(
    val capturedAt: Long = 0L,
    val apps: List<GuardInventoryApp> = emptyList(),
)

/**
 * Last-seen package inventory used to reconcile missed broadcasts while locked.
 * Keystore-encrypted (readable without the password by design): a root attacker can
 * read the app list here, but cannot erase reconciliation *history* undetectably -
 * that lands in the sealed journal.
 */
internal class GuardInventorySnapshotStore(
    private val file: File,
    private val cipher: FileCipher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): GuardInventorySnapshot {
        if (!file.isFile) {
            return GuardInventorySnapshot()
        }
        return runCatching {
            json.decodeFromString(GuardInventorySnapshot.serializer(), cipher.readBytes(file).decodeToString())
        }.getOrElse { GuardInventorySnapshot() }
    }

    fun write(snapshot: GuardInventorySnapshot) {
        runCatching {
            cipher.writeBytesAtomic(
                file,
                json.encodeToString(GuardInventorySnapshot.serializer(), snapshot).encodeToByteArray()
            )
        }
    }

    fun delete() {
        file.delete()
    }
}

/** Pure diff between two inventories -> journal events for missed changes. */
internal object GuardInventoryDiff {
    fun diff(
        previous: GuardInventorySnapshot,
        current: GuardInventorySnapshot,
    ): List<GuardEvent> {
        if (previous.capturedAt == 0L) {
            // First capture: nothing to compare against, do not fabricate installs.
            return emptyList()
        }
        val before = previous.apps.associateBy(GuardInventoryApp::packageName)
        val after = current.apps.associateBy(GuardInventoryApp::packageName)
        val events = mutableListOf<GuardEvent>()
        for ((packageName, app) in after) {
            val old = before[packageName]
            when {
                old == null ->
                    events +=
                        GuardEvent(
                            type = GuardEventType.PACKAGE_ADDED,
                            packageName = packageName,
                            uid = app.uid,
                            signerSha256 = app.signerSha256,
                            detail = RECONCILED_DETAIL,
                        )
                old.versionCode != app.versionCode ->
                    events +=
                        GuardEvent(
                            type = GuardEventType.PACKAGE_REPLACED,
                            packageName = packageName,
                            uid = app.uid,
                            signerSha256 = app.signerSha256,
                            detail = RECONCILED_DETAIL,
                        )
            }
        }
        for (packageName in before.keys) {
            if (packageName !in after) {
                events +=
                    GuardEvent(
                        type = GuardEventType.PACKAGE_REMOVED,
                        packageName = packageName,
                        detail = RECONCILED_DETAIL,
                    )
            }
        }
        return events.sortedBy { event -> event.packageName.orEmpty() }
    }

    const val RECONCILED_DETAIL = "reconciled"
}
