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
    val firstInstallTime: Long = 0L,
)

@Serializable
data class GuardInventorySnapshot(
    val capturedAt: Long = 0L,
    val apps: List<GuardInventoryApp> = emptyList(),
)

internal class GuardInventorySnapshotStore(
    private val file: File,
    private val cipher: FileCipher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): GuardInventorySnapshot {
        if (!file.isFile) {
            return GuardInventorySnapshot()
        }
        return json.decodeFromString(
            GuardInventorySnapshot.serializer(),
            cipher.readBytes(file).decodeToString(),
        )
    }

    fun write(snapshot: GuardInventorySnapshot) {
        cipher.writeBytesAtomic(
            file,
            json.encodeToString(GuardInventorySnapshot.serializer(), snapshot).encodeToByteArray(),
        )
    }

    fun delete() {
        file.delete()
    }
}

internal object GuardInventoryDiff {
    fun diff(
        previous: GuardInventorySnapshot,
        current: GuardInventorySnapshot,
    ): List<GuardEvent> {
        if (previous.capturedAt == 0L) {
            return emptyList()
        }
        if (current.apps.isEmpty() && previous.apps.isNotEmpty()) {
            return emptyList()
        }
        val before = previous.apps.associateBy(GuardInventoryApp::packageName)
        val after = current.apps.associateBy(GuardInventoryApp::packageName)
        val events = mutableListOf<GuardEvent>()
        for ((packageName, app) in after) {
            val old = before[packageName]
            when {
                old == null -> events += app.reconciledEvent(GuardEventType.PACKAGE_ADDED)
                old.differsFrom(app) -> events += app.reconciledEvent(GuardEventType.PACKAGE_REPLACED)
            }
        }
        for (packageName in before.keys - after.keys) {
            events += before.getValue(packageName).reconciledEvent(GuardEventType.PACKAGE_REMOVED)
        }
        return events.sortedBy { event -> event.packageName.orEmpty() }
    }

    private fun GuardInventoryApp.differsFrom(current: GuardInventoryApp): Boolean =
        versionCode != current.versionCode ||
            (uid != null && uid != current.uid) ||
            (signerSha256 != null && signerSha256 != current.signerSha256) ||
            (firstInstallTime > 0L && firstInstallTime != current.firstInstallTime)

    private fun GuardInventoryApp.reconciledEvent(type: GuardEventType): GuardEvent =
        GuardEvent(
            type = type,
            packageName = packageName,
            uid = uid,
            versionCode = versionCode,
            signerSha256 = signerSha256,
            firstInstallTime = firstInstallTime.takeIf { value -> value > 0L },
            detail = RECONCILED_DETAIL,
        )

    const val RECONCILED_DETAIL = "reconciled"
}
